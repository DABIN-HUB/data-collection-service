param(
    [int]$Port = 19094,
    [string]$JarPath = "collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar"
)

$ErrorActionPreference = "Stop"
$process = $null
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("collector-observability-pipeline-" + [System.Guid]::NewGuid().ToString("N"))
$logDir = Join-Path $tempRoot "logs"
$stdoutPath = Join-Path $tempRoot "stdout.log"
$stderrPath = Join-Path $tempRoot "stderr.log"
$fileLogPath = Join-Path $logDir "collector.log"
$baseUrl = "http://127.0.0.1:$Port/collector"
$opsToken = "ops" + "-" + "token"

function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Convert-ResponseContent($Content) {
    if ($null -eq $Content) {
        return ""
    }
    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Content)
    }
    return [string]$Content
}

function Invoke-CollectorRequest {
    param(
        [string]$Method = "GET",
        [Parameter(Mandatory = $true)][string]$Path,
        [hashtable]$Headers = @{},
        [AllowNull()][object]$Body = $null
    )

    $parameters = @{
        Uri = "$baseUrl$Path"
        Method = $Method
        Headers = $Headers
        TimeoutSec = 15
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
            Body = Convert-ResponseContent $response.Content
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
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            throw "collector process exited before startup. stderr=$stderrPath stdout=$stdoutPath"
        }
        try {
            $response = Invoke-CollectorRequest -Path "/actuator/health/liveness"
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "collector did not become live on port $Port"
}

function Assert-JsonStatusUp([string]$Body, [string]$Name) {
    Assert-True ($Body -match '"status"\s*:\s*"UP"') "$Name expected UP, actual body=$Body"
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
        "--collector.report.outbox.enabled=false",
        "--telemetry.tdengine.enabled=false",
        "--spring.data.redis.stream.enabled=false",
        "--logging.file.name=$fileLogPath"
    )
    $javaCommand = "java " + (Join-ProcessArguments $javaArgs) + " > " + (Quote-CmdArgument $stdoutPath) + " 2> " + (Quote-CmdArgument $stderrPath)
    $process = Start-Process -FilePath "cmd.exe" -ArgumentList @("/d", "/c", $javaCommand) -WorkingDirectory (Get-Location).Path `
        -PassThru -WindowStyle Hidden

    Wait-Started

    $viewHeaders = @{ "X-Collector-Token" = $opsToken; "X-Request-Id" = "obs-053-pipeline-view" }

    $health = Invoke-CollectorRequest -Path "/actuator/health"
    Assert-True ($health.StatusCode -eq 200) "anonymous /actuator/health should be 200"
    $liveness = Invoke-CollectorRequest -Path "/actuator/health/liveness"
    Assert-True ($liveness.StatusCode -eq 200) "anonymous liveness should be 200"
    Assert-JsonStatusUp $liveness.Body "liveness"
    $readiness = Invoke-CollectorRequest -Path "/actuator/health/readiness"
    Assert-True ($readiness.StatusCode -eq 200) "anonymous readiness should be 200"
    Assert-JsonStatusUp $readiness.Body "readiness"

    $metricsNoAuth = Invoke-CollectorRequest -Path "/actuator/metrics"
    Assert-True ($metricsNoAuth.StatusCode -eq 401) "anonymous metrics should be 401"
    Assert-True ([string]::IsNullOrWhiteSpace($metricsNoAuth.RequestId) -eq $false) "anonymous metrics 401 should include X-Request-Id"
    $memoryNoAuth = Invoke-CollectorRequest -Path "/actuator/metrics/jvm.memory.used"
    Assert-True ($memoryNoAuth.StatusCode -eq 401) "anonymous metric detail should be 401"
    $prometheusNoAuth = Invoke-CollectorRequest -Path "/actuator/prometheus"
    Assert-True ($prometheusNoAuth.StatusCode -eq 401) "anonymous prometheus should be 401"
    Assert-True ([string]::IsNullOrWhiteSpace($prometheusNoAuth.RequestId) -eq $false) "anonymous prometheus 401 should include X-Request-Id"

    $metricsView = Invoke-CollectorRequest -Path "/actuator/metrics" -Headers $viewHeaders
    Assert-True ($metricsView.StatusCode -eq 200) "VIEW metrics should be 200"
    $prometheusView = Invoke-CollectorRequest -Path "/actuator/prometheus" -Headers $viewHeaders
    Assert-True ($prometheusView.StatusCode -eq 200) "VIEW prometheus should be 200"
    Assert-True ($prometheusView.Body -like "*collector_pipeline_*") "prometheus scrape missing collector_pipeline_* metrics"
    Assert-True ($prometheusView.Body -notmatch 'collector_pipeline_[^\r\n]*deviceId=') "collector_pipeline_* exposed forbidden deviceId label"
    Assert-True ($prometheusView.Body -notmatch 'collector_pipeline_[^\r\n]*pointId=') "collector_pipeline_* exposed forbidden pointId label"
    Assert-True ($prometheusView.Body -notmatch 'collector_pipeline_[^\r\n]*requestId=') "collector_pipeline_* exposed forbidden requestId label"
    Assert-True ($prometheusView.Body -notmatch 'collector_pipeline_[^\r\n]*messageId=') "collector_pipeline_* exposed forbidden messageId label"

    $pipelineNoAuth = Invoke-CollectorRequest -Path "/monitor/pipeline"
    Assert-True ($pipelineNoAuth.StatusCode -eq 401) "anonymous pipeline should be 401"
    $pipeline = Invoke-CollectorRequest -Path "/monitor/pipeline" -Headers $viewHeaders
    Assert-True ($pipeline.StatusCode -eq 200) "VIEW pipeline should be 200"
    $pipelineJson = $pipeline.Body | ConvertFrom-Json
    Assert-True ([string]::IsNullOrWhiteSpace([string]$pipelineJson.status) -eq $false) "pipeline status missing"
    Assert-True ($null -ne $pipelineJson.generatedAt) "pipeline generatedAt missing"
    Assert-True ($null -ne $pipelineJson.ingress) "pipeline ingress missing"
    Assert-True ($null -ne $pipelineJson.stream) "pipeline stream missing"
    Assert-True ($null -ne $pipelineJson.history) "pipeline history missing"
    Assert-True ($null -ne $pipelineJson.cloud) "pipeline cloud missing"
    Assert-True ($null -ne $pipelineJson.executors.cache) "pipeline cache executor missing"
    Assert-True ($null -ne $pipelineJson.executors.stream) "pipeline stream executor missing"
    Assert-True ($null -ne $pipelineJson.executors.streamWriter) "pipeline stream writer executor missing"
    Assert-True ($null -ne $pipelineJson.executors.history) "pipeline history executor missing"
    Assert-True ($null -ne $pipelineJson.executors.report) "pipeline report executor missing"
    Assert-True ($pipelineJson.history.status -eq "DISABLED") "TDengine disabled should make history stage DISABLED, actual $($pipelineJson.history.status)"
    Assert-True ($pipelineJson.cloud.status -eq "DISABLED") "cloud disabled should make cloud stage DISABLED, actual $($pipelineJson.cloud.status)"

    for ($i = 0; $i -lt 20; $i++) {
        $scrape = Invoke-CollectorRequest -Path "/actuator/prometheus" -Headers $viewHeaders
        Assert-True ($scrape.StatusCode -eq 200) "prometheus storm request $i returned $($scrape.StatusCode)"
    }

    Write-Host "OBSERVABILITY PIPELINE SMOKE PASSED"
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
