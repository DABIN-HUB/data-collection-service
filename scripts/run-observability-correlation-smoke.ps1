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

function Mark-Step([string]$Name) {
    Set-Content -Path (Join-Path $tempRoot "step.txt") -Value $Name -Encoding UTF8
}

function Assert-True($Condition, $Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Invoke-CollectorRequest {
    param(
        [string]$Method = "GET",
        [string]$Path,
        [hashtable]$Headers = @{},
        [string]$Body = $null
    )

    $request = [System.Net.HttpWebRequest]::Create("$baseUrl$Path")
    $request.Method = $Method
    $request.Proxy = $null
    $request.Timeout = 10000
    $request.ReadWriteTimeout = 10000
    $request.Accept = "application/json"
    foreach ($key in $Headers.Keys) {
        $request.Headers[$key] = [string]$Headers[$key]
    }
    if ($null -ne $Body) {
        $payload = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $request.ContentType = "application/json"
        $request.ContentLength = $payload.Length
        $stream = $request.GetRequestStream()
        $stream.Write($payload, 0, $payload.Length)
        $stream.Dispose()
    }
    try {
        $response = $request.GetResponse()
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) {
            $response = $_.Exception.Response
        } else {
            throw
        }
    }
    try {
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $bodyText = $reader.ReadToEnd()
        $reader.Dispose()
        [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $bodyText; RequestId = [string]$response.Headers["X-Request-Id"] }
    } finally {
        $response.Dispose()
    }
}

function Wait-Started {
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            throw "collector process exited before startup. stderr=$stderrPath stdout=$stdoutPath"
        }
        try {
            $response = Invoke-CollectorRequest -Path "/actuator"
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
        Start-Sleep -Milliseconds 500
    }
    throw "collector did not start on port $Port"
}

function Join-ProcessArguments([string[]]$Arguments) {
    $escaped = @()
    foreach ($arg in $Arguments) {
        if ($arg -match '[\s"]') {
            $escaped += '"' + ($arg -replace '"', '`"') + '"'
        } else {
            $escaped += $arg
        }
    }
    return ($escaped -join ' ')
}

New-Item -ItemType Directory -Path $logDir -Force | Out-Null
try {
    Mark-Step "assert-jar"
    Assert-True (Test-Path $JarPath) "JAR not found: $JarPath"
    Mark-Step "build-java-args"
    $javaArgs = @(
        "-jar", $JarPath,
        "--server.port=$Port",
        "--collector.config.sync-enabled=false",
        "--collector.report.enabled=false",
        "--telemetry.tdengine.enabled=false",
        "--logging.file.name=$fileLogPath"
    )
    Mark-Step "process-start"
    $process = Start-Process -FilePath "java" -ArgumentList $javaArgs -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath -PassThru -WindowStyle Hidden
    Mark-Step "wait-started"

    Wait-Started
    Mark-Step "started"

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

    Start-Sleep -Milliseconds 1000
    Assert-True (Test-Path $fileLogPath) "collector file log was not created"
    $stdoutText = if (Test-Path $stdoutPath) { Get-Content -Raw -Path $stdoutPath } else { "" }
    $fileText = Get-Content -Raw -Path $fileLogPath
    $opsAfterSecret = (Invoke-CollectorRequest -Path "/api/ops/logs?keyword=token&limit=50" -Headers $authHeaders).Body

    Assert-True ($stdoutText.Length -gt 0) "stdout log is empty"
    Assert-True ($fileText.Length -gt 0) "file log is empty"
    Assert-True ($stdoutText -like "*config_access*") "stdout does not contain access log"
    Assert-True ($fileText -like "*config_access*") "file log does not contain access log"
    Assert-True ($stdoutText -like "*requestId=obs-052-runtime-001*") "stdout does not contain incoming request id access log"
    Assert-True ($fileText -like "*requestId=obs-052-runtime-001*") "file log does not contain incoming request id access log"
    Assert-True ($stdoutText -like "*risk=HIGH*" -and $stdoutText -like "*状态=401*") "stdout high-risk 401 access log missing"
    Assert-True ($fileText -like "*risk=HIGH*" -and $fileText -like "*状态=401*") "file high-risk 401 access log missing"
    Assert-True ($stdoutText -like "*token=****") "stdout does not show redacted query token"
    Assert-True ($fileText -like "*token=****") "file does not show redacted query token"
    Assert-True ($opsAfterSecret -like "*token=****") "operation log response does not show redacted query token"
    Assert-True ($stdoutText -notlike "*$secretSentinel*") "stdout leaked query sentinel"
    Assert-True ($fileText -notlike "*$secretSentinel*") "file log leaked query sentinel"
    Assert-True ($opsAfterSecret -notlike "*$secretSentinel*") "operation log response leaked query sentinel"

    Write-Host "OBSERVABILITY CORRELATION SMOKE PASSED"
    exit 0
} catch {
    Write-Error $_
    exit 1
} finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        $process.WaitForExit(5000) | Out-Null
    }

    if (Test-Path $tempRoot) {
        Remove-Item -Recurse -Force -Path $tempRoot -ErrorAction SilentlyContinue
    }
}
