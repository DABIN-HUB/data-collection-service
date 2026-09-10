param(
    [int]$Port = 19093,
    [string]$JarPath = "collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar"
)

$ErrorActionPreference = "Stop"
$process = $null
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("collector-observability-correlation-" + [System.Guid]::NewGuid().ToString("N"))
$logDir = Join-Path $tempRoot "logs"
$stdoutPath = Join-Path $tempRoot "stdout.log"
$stderrPath = Join-Path $tempRoot "stderr.log"
$fileLogPath = Join-Path $logDir "collector.log"
$baseUrl = "http://127.0.0.1:$Port/collector"
$opsToken = "ops" + "-" + "token"
$secretSentinel = "OBS_SECRET_052"

function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Invoke-CollectorRequest {
    param(
        [string]$Method = "GET",
        [Parameter(Mandatory = $true)][string]$Path,
        [hashtable]$Headers = @{},
        [AllowNull()][object]$Body = $null
    )

    $uri = "$baseUrl$Path"
    $requestHeaders = @{}
    foreach ($key in $Headers.Keys) {
        $requestHeaders[$key] = [string]$Headers[$key]
    }

    $parameters = @{
        Uri = $uri
        Method = $Method
        Headers = $requestHeaders
        TimeoutSec = 10
        UseBasicParsing = $true
        ErrorAction = "Stop"
    }
    if ($PSBoundParameters.ContainsKey("Body")) {
        $parameters["Body"] = [string]$Body
        $parameters["ContentType"] = "application/json"
    }

    try {
        $response = Invoke-WebRequest @parameters
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Body = [string]$response.Content
            RequestId = [string]$response.Headers["X-Request-Id"]
        }
    } catch {
        $httpResponse = $_.Exception.Response
        if ($null -eq $httpResponse) {
            throw
        }
        $bodyText = ""
        try {
            $stream = $httpResponse.GetResponseStream()
            if ($null -ne $stream) {
                $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
                $bodyText = $reader.ReadToEnd()
                $reader.Dispose()
            }
        } catch {
            $bodyText = ""
        }
        return [pscustomobject]@{
            StatusCode = [int]$httpResponse.StatusCode
            Body = $bodyText
            RequestId = [string]$httpResponse.Headers["X-Request-Id"]
        }
    }
}

function Wait-Started {
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            throw "collector process exited before startup. stderr=$stderrPath stdout=$stdoutPath"
        }
        try {
            $client = [System.Net.Sockets.TcpClient]::new()
            try {
                $connect = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
                if ($connect.AsyncWaitHandle.WaitOne(1000, $false)) {
                    $client.EndConnect($connect)
                    return
                }
            } finally {
                $client.Dispose()
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "collector did not start on port $Port"
}

function Contains-Text([string]$Path, [string]$Needle) {
    if (-not (Test-Path $Path)) {
        return $false
    }
    return (Select-String -Path $Path -SimpleMatch -Pattern $Needle -Quiet)
}

function Join-ProcessArguments([string[]]$Arguments) {
    $escaped = @()
    foreach ($arg in $Arguments) {
        if ($arg -match '[\s"]') {
            $escaped += '"' + ($arg -replace '"', '\"') + '"'
        } else {
            $escaped += $arg
        }
    }
    return ($escaped -join ' ')
}

function Quote-CmdArgument([string]$Argument) {
    return '"' + ($Argument -replace '"', '\"') + '"'
}

New-Item -ItemType Directory -Path $logDir -Force | Out-Null
try {
    Assert-True (Test-Path $JarPath) "JAR not found: $JarPath"
    $javaArgs = @(
        "-jar", $JarPath,
        "--server.port=$Port",
        "--collector.config.sync-enabled=false",
        "--collector.report.enabled=false",
        "--telemetry.tdengine.enabled=false",
        "--logging.file.name=$fileLogPath"
    )
    $javaCommand = "java " + (Join-ProcessArguments $javaArgs) + " > " + (Quote-CmdArgument $stdoutPath) + " 2> " + (Quote-CmdArgument $stderrPath)
    $process = Start-Process -FilePath "cmd.exe" -ArgumentList @("/d", "/c", $javaCommand) -WorkingDirectory (Get-Location).Path `
        -PassThru -WindowStyle Hidden

    Wait-Started

    $authHeaders = @{ "X-Collector-Token" = $opsToken }
    $incomingHeaders = @{ "X-Collector-Token" = $opsToken; "X-Request-Id" = "obs-052-runtime-001" }

    $incoming = Invoke-CollectorRequest -Path "/api/ops/logs?limit=1" -Headers $incomingHeaders
    Assert-True ($incoming.StatusCode -eq 200) "incoming request id request failed"
    Assert-True ($incoming.RequestId -eq "obs-052-runtime-001") "incoming request id was not preserved"

    $generated = Invoke-CollectorRequest -Path "/health"
    Assert-True ($generated.StatusCode -eq 200) "generated request id probe failed"
    Assert-True ([string]::IsNullOrWhiteSpace($generated.RequestId) -eq $false) "generated request id response header missing"

    $opsSearch = Invoke-CollectorRequest -Path "/api/ops/logs?keyword=obs-052-runtime-001&limit=20" -Headers $authHeaders
    Assert-True ($opsSearch.Body -like "*obs-052-runtime-001*") "ops logs did not find request id"

    $highRisk = Invoke-CollectorRequest -Method "POST" -Path "/api/config/import" -Body "{}"
    Assert-True ($highRisk.StatusCode -eq 401) "high-risk unauthenticated request should be 401"

    $querySecret = Invoke-CollectorRequest -Path "/api/ops/logs?limit=1&token=$secretSentinel" -Headers $authHeaders
    Assert-True ($querySecret.StatusCode -eq 200) "query redaction probe failed"

    Start-Sleep -Milliseconds 1500
    Assert-True (Test-Path $fileLogPath) "collector file log was not created"

    $opsAfterSecret = (Invoke-CollectorRequest -Path "/api/ops/logs?keyword=token&limit=50" -Headers $authHeaders).Body

    Assert-True ((Get-Item $stdoutPath).Length -gt 0) "stdout log is empty"
    Assert-True ((Get-Item $fileLogPath).Length -gt 0) "file log is empty"
    Assert-True (Contains-Text $stdoutPath "config_access") "stdout does not contain access log"
    Assert-True (Contains-Text $fileLogPath "config_access") "file log does not contain access log"
    Assert-True (Contains-Text $stdoutPath "requestId=obs-052-runtime-001") "stdout does not contain incoming request id access log"
    Assert-True (Contains-Text $fileLogPath "requestId=obs-052-runtime-001") "file log does not contain incoming request id access log"
    Assert-True ((Contains-Text $stdoutPath "risk=HIGH") -and (Contains-Text $stdoutPath "401")) "stdout high-risk 401 access log missing"
    Assert-True ((Contains-Text $fileLogPath "risk=HIGH") -and (Contains-Text $fileLogPath "401")) "file high-risk 401 access log missing"
    Assert-True (Contains-Text $stdoutPath "token=***") "stdout does not show redacted query token"
    Assert-True (Contains-Text $fileLogPath "token=***") "file does not show redacted query token"
    Assert-True ($opsAfterSecret -like "*token=****") "operation log response does not show redacted query token"
    Assert-True (-not (Contains-Text $stdoutPath $secretSentinel)) "stdout leaked query sentinel"
    Assert-True (-not (Contains-Text $fileLogPath $secretSentinel)) "file log leaked query sentinel"
    Assert-True ($opsAfterSecret -notlike "*$secretSentinel*") "operation log response leaked query sentinel"

    Write-Host "OBSERVABILITY CORRELATION SMOKE PASSED"
    exit 0
} catch {
    $line = $_.InvocationInfo.ScriptLineNumber
    $message = $_.Exception.Message
    Write-Error "line=$line message=$message"
    exit 1
} finally {
    if ($process) {
        taskkill.exe /T /F /PID $process.Id | Out-Null
    }
    if (Test-Path $tempRoot) {
        Remove-Item -Recurse -Force -Path $tempRoot -ErrorAction SilentlyContinue
    }
}
