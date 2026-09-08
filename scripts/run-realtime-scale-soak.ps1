param(
    [string]$JarPath = "",
    [int]$Points = 100000,
    [int]$Devices = 100,
    [int]$DurationSeconds = 900,
    [int]$Clients = 3,
    [int]$PollIntervalSeconds = 5,
    [int]$Port = 19091,
    [string]$Token = "ops-token",
    [int]$StartupTimeoutSeconds = 120,
    [string]$DevicePrefix = "realtime-scale-soak-"
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

$script:RepoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $script:RepoRoot "collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar"
}
$script:ResolvedJarPath = [System.IO.Path]::GetFullPath($JarPath)
$script:BaseUrl = "http://127.0.0.1:$Port/collector"
$script:RunId = (Get-Date).ToString("yyyyMMdd-HHmmss")
$script:OutputDir = Join-Path $script:RepoRoot "target/realtime-scale-soak/$($script:RunId)"
$script:StdoutLog = Join-Path $script:OutputDir "backend-stdout.log"
$script:StderrLog = Join-Path $script:OutputDir "backend-stderr.log"
$script:BackendProcess = $null
$script:BackendReady = $false
$script:FailureCount = 0
$script:CreatedDevices = New-Object System.Collections.Generic.HashSet[string]
$script:RequestRows = New-Object System.Collections.ArrayList
$script:ResourceRows = New-Object System.Collections.ArrayList
$script:PrometheusIndex = 0
$script:FullBytes = New-Object System.Collections.ArrayList
$script:DeltaBytes = New-Object System.Collections.ArrayList
$script:TotalRequests = 0
$script:FullRequests = 0
$script:DeltaRequests = 0
$script:Http2xx = 0
$script:Http4xx = 0
$script:Http5xx = 0
$script:JsonFailures = 0
$script:UnexpectedResets = 0
$script:ExpectedResets = 0
$script:ProcessCrashes = 0
$script:PointsPerDevice = [Math]::Floor($Points / $Devices)

function Write-Pass([string]$Name, [string]$Message = "") { Write-Host "[PASS] $Name$(if ($Message) { ' - ' + $Message } else { '' })" }
function Write-Warn([string]$Name, [string]$Message = "") { Write-Host "[WARN] $Name$(if ($Message) { ' - ' + $Message } else { '' })" }
function Write-Metric([string]$Name, [string]$Message = "") { Write-Host "[METRIC] $Name$(if ($Message) { ' - ' + $Message } else { '' })" }
function Write-Fail([string]$Name, [string]$Message) { $script:FailureCount += 1; Write-Host "[FAIL] $Name - $Message" }
function Fail-Soak([string]$Name, [string]$Message) { Write-Fail $Name $Message; throw "${Name}: $Message" }
function Assert-True([bool]$Condition, [string]$Name, [string]$Message) { if (-not $Condition) { Fail-Soak $Name $Message } }
function Get-JsonProperty($Object, [string]$Name) { if ($null -eq $Object) { return $null }; $p = $Object.PSObject.Properties[$Name]; if ($null -eq $p) { return $null }; return $p.Value }
function Has-JsonProperty($Object, [string]$Name) { return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name] }
function To-Array($Value) { if ($null -eq $Value) { return @() }; if ($Value -is [System.Array]) { return @($Value) }; if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) { return @($Value) }; return @($Value) }
function Get-ArrayCount($Value) { return @(To-Array $Value).Count }
function Get-TextBytes([string]$Text) { return [System.Text.Encoding]::UTF8.GetByteCount($Text) }
function Format-MiB([long]$Bytes) { return [Math]::Round($Bytes / 1024.0 / 1024.0, 4) }
function Percentile([double[]]$Values, [double]$P) { if ($Values.Count -eq 0) { return 0 }; $sorted = @($Values | Sort-Object); $idx = [int][Math]::Ceiling(($P / 100.0) * $sorted.Count) - 1; if ($idx -lt 0) { $idx = 0 }; if ($idx -ge $sorted.Count) { $idx = $sorted.Count - 1 }; return $sorted[$idx] }
function Median([double[]]$Values) { return Percentile $Values 50 }
function Test-PortAvailable([int]$CheckPort) {
    foreach ($endpoint in [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()) {
        if ($endpoint.Port -eq $CheckPort) { return $false }
    }
    $listener = $null
    try { $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $CheckPort); $listener.Start(); return $true }
    catch { return $false }
    finally { if ($null -ne $listener) { $listener.Stop() } }
}
function Resolve-JavaExecutable() { if ($env:JAVA_HOME) { $candidate = Join-Path $env:JAVA_HOME "bin/java.exe"; if (Test-Path $candidate) { return $candidate } }; return (Get-Command java.exe -ErrorAction Stop).Source }
function Read-ErrorStatusCode($Exception) { try { if ($Exception.Response -and $Exception.Response.PSObject.Properties["StatusCode"]) { return [int]$Exception.Response.StatusCode } } catch {}; return 0 }
function Read-ErrorResponseText($Exception) { try { if ($Exception.Response -and $Exception.Response.PSObject.Properties["Content"] -and $Exception.Response.Content) { return $Exception.Response.Content.ReadAsStringAsync().GetAwaiter().GetResult() } } catch {}; try { if ($Exception.Response) { $s = $Exception.Response.GetResponseStream(); if ($s) { return ([System.IO.StreamReader]::new($s)).ReadToEnd() } } } catch {}; return "" }
function Convert-ToJsonBody([string]$Text, [string]$Name) { try { return $Text | ConvertFrom-Json -ErrorAction Stop } catch { $script:JsonFailures += 1; Fail-Soak $Name "response body is not valid JSON: $($_.Exception.Message)" } }

function Invoke-ScaleRequest([string]$Name, [string]$Path, [string]$Method = "GET", [AllowNull()]$Body = $null, [int]$TimeoutSeconds = 120, [bool]$ParseJson = $true) {
    if ($script:BackendProcess -and $script:BackendProcess.HasExited) { $script:ProcessCrashes += 1; Fail-Soak "backend process" "owned JVM exited with code $($script:BackendProcess.ExitCode)" }
    $url = "$($script:BaseUrl)$Path"
    $headers = @{ Accept = "application/json"; "X-Collector-Token" = $Token; "X-Request-Id" = "realtime-scale-$Name-$([Guid]::NewGuid().ToString('N'))" }
    $params = @{ Uri = $url; Method = $Method; Headers = $headers; TimeoutSec = $TimeoutSeconds; ErrorAction = "Stop" }
    if ($PSVersionTable.PSVersion.Major -lt 6) { $params["UseBasicParsing"] = $true }
    if ($null -ne $Body) { $params["ContentType"] = "application/json; charset=utf-8"; $params["Body"] = ($Body | ConvertTo-Json -Depth 40) }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest @params
        $sw.Stop()
        $statusCode = [int]$response.StatusCode
        $text = [string]$response.Content
    } catch {
        $sw.Stop()
        $statusCode = Read-ErrorStatusCode $_.Exception
        $text = Read-ErrorResponseText $_.Exception
        if ([string]::IsNullOrWhiteSpace($text)) { $text = $_.Exception.Message }
    }
    $script:TotalRequests += 1
    if ($statusCode -ge 200 -and $statusCode -lt 300) { $script:Http2xx += 1 } elseif ($statusCode -ge 400 -and $statusCode -lt 500) { $script:Http4xx += 1 } elseif ($statusCode -ge 500) { $script:Http5xx += 1 }
    $bytes = Get-TextBytes $text
    $json = $null
    if ($ParseJson) { $json = Convert-ToJsonBody $text $Name }
    [void]$script:RequestRows.Add([pscustomobject]@{ timestamp = (Get-Date).ToString("o"); name = $Name; method = $Method; path = $Path; status = $statusCode; bytes = $bytes; durationMs = [Math]::Round($sw.Elapsed.TotalMilliseconds, 2) })
    return [pscustomobject]@{ Name = $Name; StatusCode = $statusCode; BodyText = $text; Bytes = $bytes; DurationMs = $sw.Elapsed.TotalMilliseconds; Json = $json }
}

function Invoke-PublicGet([string]$Path, [int]$TimeoutSeconds = 5) {
    try { $params = @{ Uri = "$($script:BaseUrl)$Path"; Method = "GET"; TimeoutSec = $TimeoutSeconds; ErrorAction = "Stop" }; if ($PSVersionTable.PSVersion.Major -lt 6) { $params["UseBasicParsing"] = $true }; return Invoke-WebRequest @params } catch { return $null }
}
function Wait-BackendReady() {
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($script:BackendProcess -and $script:BackendProcess.HasExited) { Fail-Soak "backend ready" "Java exited with code $($script:BackendProcess.ExitCode)" }
        $probe = Invoke-PublicGet "/health" 2
        if ($probe -and [int]$probe.StatusCode -ge 200 -and [int]$probe.StatusCode -lt 500) { $script:BackendReady = $true; Write-Pass "backend ready" "HTTP $($probe.StatusCode)"; return }
        Start-Sleep -Milliseconds 500
    }
    Fail-Soak "backend ready" "startup timeout"
}
function Start-Backend() {
    Assert-True (Test-Path $script:ResolvedJarPath) "executable jar" "jar not found: $script:ResolvedJarPath"
    Assert-True (-not (([System.IO.Path]::GetFileName($script:ResolvedJarPath)) -like "*.original")) "executable jar" "refuse original jar"
    if (-not (Test-PortAvailable $Port)) { Fail-Soak "port" "$Port already LISTENING or unavailable; refusing to kill unknown process" }
    New-Item -ItemType Directory -Force -Path $script:OutputDir | Out-Null
    $java = Resolve-JavaExecutable
    $args = @(
        "-jar", $script:ResolvedJarPath,
        "--spring.profiles.active=dev",
        "--server.port=$Port",
        "--telemetry.tdengine.enabled=false",
        "--collector.report.enabled=false",
        "--collector.report.mqtt.enabled=false",
        "--collector.report.shadow.persistence-enabled=false",
        "--collector.alarm.state.enabled=false",
        "--collector.cache.type=local",
        "--spring.data.redis.stream.enabled=false",
        "--collector.config.loader=file",
        "--logging.level.com.wangbin.collector=ERROR",
        "--logging.access.enabled=false"
    )
    $script:BackendProcess = Start-Process -FilePath $java -ArgumentList $args -RedirectStandardOutput $script:StdoutLog -RedirectStandardError $script:StderrLog -PassThru -WindowStyle Hidden
    Write-Pass "backend process started" "PID $($script:BackendProcess.Id), jar=$([System.IO.Path]::GetFileName($script:ResolvedJarPath))"
    Wait-BackendReady
}
function Stop-Backend() {
    if ($null -eq $script:BackendProcess) { return }
    try {
        if (-not $script:BackendProcess.HasExited) {
            Stop-Process -Id $script:BackendProcess.Id -ErrorAction Stop
            [void]$script:BackendProcess.WaitForExit(15000)
            if (-not $script:BackendProcess.HasExited) { Write-Fail "backend cleanup" "owned PID $($script:BackendProcess.Id) did not exit" } else { Write-Pass "backend cleanup" "stopped owned PID $($script:BackendProcess.Id)" }
        }
    } catch { Write-Fail "backend cleanup" $_.Exception.Message }
}

function DeviceId([int]$Index) { return "$DevicePrefix$($Index.ToString('000'))" }
function PointId([int]$Index) { return "p$($Index.ToString('000000'))" }
function Build-DevicePayload([int]$DeviceIndex, [string]$PointNamePrefix = "Scale Point") {
    $deviceId = DeviceId $DeviceIndex
    $points = New-Object System.Collections.ArrayList
    for ($i = 1; $i -le $script:PointsPerDevice; $i += 1) {
        $pointId = PointId $i
        [void]$points.Add(@{ pointId = $pointId; pointCode = $pointId; pointName = "$PointNamePrefix $pointId"; deviceId = $deviceId; address = "/value/$pointId"; dataType = "DOUBLE"; readWrite = "R"; scalingFactor = 1.0; unit = "unit"; status = 1 })
    }
    return @{ device = @{ id = $deviceId; deviceId = $deviceId; deviceName = $deviceId; protocolType = "HTTP"; connectionType = "HTTP"; ipAddress = "127.0.0.1"; port = 9; collectionInterval = 5000; status = "OFFLINE"; configSource = "local"; temporaryConfig = $true }; connection = @{ connectionType = "HTTP"; connectionKey = $deviceId; deviceId = $deviceId; host = "127.0.0.1"; port = 9; url = "http://127.0.0.1:9/realtime-scale"; connectTimeoutMs = 500; readTimeoutMs = 500; writeTimeoutMs = 500; retries = 0; extJson = @{ configSource = "local"; temporaryConfig = $true } }; points = @($points); overwrite = $true; startAfterSave = $false }
}
function Cleanup-ScaleDevices() {
    if (-not $script:BackendReady) { return }
    for ($i = $Devices; $i -ge 1; $i -= 1) {
        $deviceId = DeviceId $i
        if (-not $deviceId.StartsWith($DevicePrefix)) { continue }
        try { $r = Invoke-ScaleRequest "cleanup-$deviceId" "/api/config/local/device/$deviceId" "DELETE" $null 30 $true; if ($r.StatusCode -eq 200 -or $r.StatusCode -eq 400 -or $r.StatusCode -eq 404) { [void]$script:CreatedDevices.Remove($deviceId) } else { Write-Fail "cleanup $deviceId" "HTTP $($r.StatusCode)" } } catch { Write-Fail "cleanup $deviceId" $_.Exception.Message }
    }
}
function Create-DevicesTo([int]$TargetDevices) {
    for ($i = 1; $i -le $TargetDevices; $i += 1) {
        $deviceId = DeviceId $i
        if ($script:CreatedDevices.Contains($deviceId)) { continue }
        $response = Invoke-ScaleRequest "create-$deviceId" "/api/config/local/devices" "POST" (Build-DevicePayload $i) 180 $true
        Assert-True ($response.StatusCode -eq 200) "create $deviceId" "HTTP $($response.StatusCode)"
        [void]$script:CreatedDevices.Add($deviceId)
        if (($i % 10) -eq 0) { Write-Pass "create devices" "$i/$TargetDevices" }
    }
}
function Assert-RawCompactContract($Json, [string]$Name) {
    Assert-True (([string](Get-JsonProperty $Json "status")) -eq "success") $Name "status must be success"
    Assert-True (-not (Has-JsonProperty $Json "code")) $Name "RAW DTO must not be ApiResult envelope"
    Assert-True (-not (Has-JsonProperty $Json "data")) $Name "RAW DTO must not contain ApiResult.data envelope"
    Assert-True (-not [string]::IsNullOrWhiteSpace([string](Get-JsonProperty $Json "snapshotId"))) $Name "snapshotId nonblank"
    Assert-True ([long](Get-JsonProperty $Json "configEpoch") -ge 1) $Name "configEpoch >= 1"
    Assert-True ([long](Get-JsonProperty $Json "revision") -ge 0) $Name "revision >= 0"
    Assert-True (Has-JsonProperty $Json "rows") $Name "rows array missing"
    Assert-True (Has-JsonProperty $Json "devices") $Name "devices array missing"
}
function Assert-CompactRowBoundary($Row, [string]$Name) {
    foreach ($field in @("pointId", "pointCode", "pointName", "address", "dataType", "readWrite", "scalingFactor", "unit", "status")) { Assert-True (Has-JsonProperty $Row $field) $Name "missing $field" }
    foreach ($field in @("deviceName", "additionalConfig", "metadata", "currentCollectionInterval", "stableCount", "lastValue", "changeRate", "lastAdjustTime", "processorName")) { Assert-True (-not (Has-JsonProperty $Row $field)) $Name "compact row must not contain $field" }
}
function Assert-FullStage([string]$StageName, [int]$ExpectedDevices, [int]$ExpectedPoints) {
    $latencies = New-Object System.Collections.ArrayList
    $last = $null
    for ($i = 1; $i -le 3; $i += 1) {
        $script:FullRequests += 1
        $r = Invoke-ScaleRequest "$StageName-full-$i" "/api/data/realtime/compact" "GET" $null 300 $true
        Assert-True ($r.StatusCode -eq 200) "$StageName full" "HTTP $($r.StatusCode)"
        Assert-RawCompactContract $r.Json "$StageName full contract"
        Assert-True ([int](Get-JsonProperty $r.Json "deviceCount") -eq $ExpectedDevices) "$StageName deviceCount" "expected $ExpectedDevices"
        Assert-True ([int](Get-JsonProperty $r.Json "dataCount") -eq $ExpectedPoints) "$StageName dataCount" "expected $ExpectedPoints"
        Assert-True ((Get-ArrayCount (Get-JsonProperty $r.Json "rows")) -eq $ExpectedPoints) "$StageName rows" "expected $ExpectedPoints"
        Assert-True ((Get-ArrayCount (Get-JsonProperty $r.Json "devices")) -eq $ExpectedDevices) "$StageName devices" "expected $ExpectedDevices"
        foreach ($device in (To-Array (Get-JsonProperty $r.Json "devices"))) { Assert-True (([string](Get-JsonProperty $device "status")) -eq "success") "$StageName device status" "expected success"; Assert-True ([int](Get-JsonProperty $device "dataCount") -eq $script:PointsPerDevice) "$StageName device points" "expected $($script:PointsPerDevice)" }
        $rows = To-Array (Get-JsonProperty $r.Json "rows")
        Assert-CompactRowBoundary $rows[0] "$StageName first row"
        Assert-CompactRowBoundary $rows[[int][Math]::Floor($rows.Count / 2)] "$StageName middle row"
        Assert-CompactRowBoundary $rows[$rows.Count - 1] "$StageName last row"
        [void]$latencies.Add([double]$r.DurationMs)
        [void]$script:FullBytes.Add([long]$r.Bytes)
        $last = $r
    }
    $metric = [pscustomobject]@{ stage = $StageName; devices = $ExpectedDevices; points = $ExpectedPoints; bytes = [long]$last.Bytes; mib = Format-MiB $last.Bytes; minMs = [Math]::Round((Percentile ([double[]]$latencies.ToArray([double])) 0),2); medianMs = [Math]::Round((Median ([double[]]$latencies.ToArray([double]))),2); maxMs = [Math]::Round((Percentile ([double[]]$latencies.ToArray([double])) 100),2); p95Ms = [Math]::Round((Percentile ([double[]]$latencies.ToArray([double])) 95),2); result = "PASS" }
    Write-Metric "$StageName full" "devices=$ExpectedDevices dataCount=$ExpectedPoints bytes=$($metric.bytes) MiB=$($metric.mib) medianMs=$($metric.medianMs) maxMs=$($metric.maxMs)"
    return [pscustomobject]@{ Metric = $metric; Cursor = @{ snapshotId = [string](Get-JsonProperty $last.Json "snapshotId"); configEpoch = [long](Get-JsonProperty $last.Json "configEpoch"); revision = [long](Get-JsonProperty $last.Json "revision") } }
}
function Build-DeltaPath($Cursor) { return "/api/data/realtime/compact/delta?snapshotId=$([System.Uri]::EscapeDataString([string]$Cursor.snapshotId))&configEpoch=$($Cursor.configEpoch)&sinceRevision=$($Cursor.revision)" }
function Invoke-Delta($ClientName, $Cursor, [bool]$ExpectReset, [string]$ExpectedReason = "") {
    $script:DeltaRequests += 1
    $r = Invoke-ScaleRequest "$ClientName-delta" (Build-DeltaPath $Cursor) "GET" $null 120 $true
    Assert-True ($r.StatusCode -eq 200) "$ClientName delta" "HTTP $($r.StatusCode)"
    Assert-True (([string](Get-JsonProperty $r.Json "status")) -eq "success") "$ClientName delta" "status=success expected"
    Assert-True (-not (Has-JsonProperty $r.Json "code")) "$ClientName delta" "RAW DTO must not be ApiResult envelope"
    $reset = [bool](Get-JsonProperty $r.Json "resetRequired")
    if ($ExpectReset) { Assert-True $reset "$ClientName delta reset" "expected reset"; if ($ExpectedReason) { Assert-True (([string](Get-JsonProperty $r.Json "resetReason")) -eq $ExpectedReason) "$ClientName reset reason" "expected $ExpectedReason" }; $script:ExpectedResets += 1 } else { Assert-True (-not $reset) "$ClientName delta reset" "unexpected resetReason=$([string](Get-JsonProperty $r.Json 'resetReason'))"; Assert-True ([int](Get-JsonProperty $r.Json "changedCount") -eq 0) "$ClientName changedCount" "expected empty delta"; Assert-True ((Get-ArrayCount (Get-JsonProperty $r.Json "rows")) -eq 0) "$ClientName rows" "expected no rows" }
    [void]$script:DeltaBytes.Add([long]$r.Bytes)
    return [pscustomobject]@{ Response = $r; Cursor = @{ snapshotId = [string](Get-JsonProperty $r.Json "snapshotId"); configEpoch = [long](Get-JsonProperty $r.Json "configEpoch"); revision = [long](Get-JsonProperty $r.Json "revision") } }
}
function Sample-Resources([string]$Phase) {
    $raw = ""
    try { $raw = [string](Invoke-PublicGet "/actuator/prometheus" 10).Content } catch { $raw = "" }
    $script:PrometheusIndex += 1
    $path = Join-Path $script:OutputDir ("prometheus-$($script:PrometheusIndex.ToString('0000'))-$Phase.txt")
    Set-Content -Path $path -Value $raw -Encoding UTF8
    function MetricValue([string]$Pattern) { $m = [regex]::Match($raw, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline); if ($m.Success) { return [double]::Parse($m.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture) }; return 0 }
    $heap = 0.0; $nonheap = 0.0
    foreach ($line in ($raw -split "`n")) {
        if ($line -match '^jvm_memory_used_bytes\{[^}]*area="heap"[^}]*\}\s+([0-9.Ee+-]+)') { $heap += [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture) }
        if ($line -match '^jvm_memory_used_bytes\{[^}]*area="nonheap"[^}]*\}\s+([0-9.Ee+-]+)') { $nonheap += [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture) }
    }
    $resident = MetricValue '^process_resident_memory_bytes\s+([0-9.Ee+-]+)'
    $threads = MetricValue '^jvm_threads_live_threads\s+([0-9.Ee+-]+)'
    $cpu = MetricValue '^process_cpu_usage\s+([0-9.Ee+-]+)'
    $uptime = MetricValue '^process_uptime_seconds\s+([0-9.Ee+-]+)'
    $gcCount = 0.0; $gcTime = 0.0
    foreach ($line in ($raw -split "`n")) { if ($line -match '^jvm_gc_pause_seconds_count\{.*\}\s+([0-9.Ee+-]+)') { $gcCount += [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture) }; if ($line -match '^jvm_gc_pause_seconds_sum\{.*\}\s+([0-9.Ee+-]+)') { $gcTime += [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture) } }
    [void]$script:ResourceRows.Add([pscustomobject]@{ timestamp = (Get-Date).ToString("o"); phase = $Phase; heapUsedBytes = [long]$heap; nonHeapUsedBytes = [long]$nonheap; processResidentBytes = [long]$resident; gcCount = $gcCount; gcTimeSeconds = $gcTime; liveThreads = [long]$threads; processCpu = $cpu; uptimeSeconds = $uptime; rawSnapshot = [System.IO.Path]::GetFileName($path) })
}
function Run-Soak($InitialCursor) {
    $clientsState = @()
    for ($client = 1; $client -le $Clients; $client += 1) {
        $script:FullRequests += 1
        $full = Invoke-ScaleRequest "client-$client-initial-full" "/api/data/realtime/compact" "GET" $null 300 $true
        Assert-RawCompactContract $full.Json "client $client initial full"
        $clientsState += [pscustomobject]@{ name = "client-$client"; cursor = @{ snapshotId = [string](Get-JsonProperty $full.Json "snapshotId"); configEpoch = [long](Get-JsonProperty $full.Json "configEpoch"); revision = [long](Get-JsonProperty $full.Json "revision") }; deltaCycles = 0; lastRevision = [long](Get-JsonProperty $full.Json "revision") }
    }
    Write-Pass "initial client load" "$Clients independent local cursors; server tracker state is not per-client"
    $deadline = (Get-Date).AddSeconds($DurationSeconds)
    $nextSample = Get-Date
    $pollCycles = 0
    while ((Get-Date) -lt $deadline) {
        foreach ($client in $clientsState) {
            if ($client.deltaCycles -ge 12) {
                $script:FullRequests += 1
                $full = Invoke-ScaleRequest "$($client.name)-periodic-full" "/api/data/realtime/compact" "GET" $null 300 $true
                Assert-RawCompactContract $full.Json "$($client.name) periodic full"
                $client.cursor = @{ snapshotId = [string](Get-JsonProperty $full.Json "snapshotId"); configEpoch = [long](Get-JsonProperty $full.Json "configEpoch"); revision = [long](Get-JsonProperty $full.Json "revision") }
                $client.deltaCycles = 0
            } else {
                $beforeRevision = [long]$client.cursor.revision
                $delta = Invoke-Delta $client.name $client.cursor $false
                $newRevision = [long]$delta.Cursor.revision
                Assert-True ($newRevision -ge $beforeRevision) "$($client.name) revision monotonicity" "revision moved backwards"
                Assert-True ($delta.Cursor.snapshotId -eq $client.cursor.snapshotId) "$($client.name) snapshot stable" "snapshot drift"
                Assert-True ([long]$delta.Cursor.configEpoch -eq [long]$client.cursor.configEpoch) "$($client.name) epoch stable" "config epoch drift"
                $client.cursor = $delta.Cursor
                $client.deltaCycles += 1
                $client.lastRevision = $newRevision
            }
        }
        $pollCycles += 1
        if ((Get-Date) -ge $nextSample) { Sample-Resources "soak"; $nextSample = (Get-Date).AddSeconds(30) }
        Start-Sleep -Seconds $PollIntervalSeconds
    }
    Write-Pass "100k empty delta soak" "pollCycles=$pollCycles durationSeconds=$DurationSeconds clients=$Clients"
    return $clientsState[0].cursor
}
function Run-RecoveryChecks($CursorBeforeChange) {
    $oldCursor = $CursorBeforeChange
    $update = Build-DevicePayload $Devices "Scale Point Updated"
    $update.points[0].pointName = "Scale Point Updated p000001"
    $r = Invoke-ScaleRequest "config-update-device-$Devices" "/api/config/local/device/$(DeviceId $Devices)" "PUT" $update 180 $true
    Assert-True ($r.StatusCode -eq 200) "config update" "HTTP $($r.StatusCode)"
    $delta = Invoke-Delta "expected-config-change" $oldCursor $true "CONFIG_CHANGED"
    $script:FullRequests += 1
    $full = Invoke-ScaleRequest "full-after-config-change" "/api/data/realtime/compact" "GET" $null 300 $true
    $rows = To-Array (Get-JsonProperty $full.Json "rows")
    $updatedRows = @($rows | Where-Object { (Get-JsonProperty $_ "deviceId") -eq (DeviceId $Devices) -and (Get-JsonProperty $_ "pointId") -eq "p000001" -and (Get-JsonProperty $_ "pointName") -eq "Scale Point Updated p000001" })
    Assert-True ($updatedRows.Count -eq 1) "full resync config update" "updated pointName not visible"
    Write-Pass "config change recovery" "CONFIG_CHANGED then full sees updated pointName"

    $deleteCursor = @{ snapshotId = [string](Get-JsonProperty $full.Json "snapshotId"); configEpoch = [long](Get-JsonProperty $full.Json "configEpoch"); revision = [long](Get-JsonProperty $full.Json "revision") }
    $del = Invoke-ScaleRequest "delete-device-$Devices" "/api/config/local/device/$(DeviceId $Devices)" "DELETE" $null 60 $true
    Assert-True ($del.StatusCode -eq 200) "device delete" "HTTP $($del.StatusCode)"
    [void]$script:CreatedDevices.Remove((DeviceId $Devices))
    $deleteDelta = Invoke-Delta "expected-delete-config-change" $deleteCursor $true "CONFIG_CHANGED"
    $script:FullRequests += 1
    $fullAfterDelete = Invoke-ScaleRequest "full-after-device-delete" "/api/data/realtime/compact" "GET" $null 300 $true
    Assert-True ([int](Get-JsonProperty $fullAfterDelete.Json "deviceCount") -eq ($Devices - 1)) "full after delete deviceCount" "expected $($Devices - 1)"
    Assert-True ([int](Get-JsonProperty $fullAfterDelete.Json "dataCount") -eq ($Points - $script:PointsPerDevice)) "full after delete dataCount" "expected deleted device removed"
    $deletedDeviceRows = @((To-Array (Get-JsonProperty $fullAfterDelete.Json "rows")) | Where-Object { (Get-JsonProperty $_ "deviceId") -eq (DeviceId $Devices) })
    Assert-True ($deletedDeviceRows.Count -eq 0) "full after delete rows" "deleted device rows remain"
    Write-Pass "device delete recovery" "CONFIG_CHANGED then full has deviceCount=$($Devices - 1) dataCount=$($Points - $script:PointsPerDevice)"
    $recreate = Invoke-ScaleRequest "recreate-device-$Devices" "/api/config/local/devices" "POST" (Build-DevicePayload $Devices) 180 $true
    Assert-True ($recreate.StatusCode -eq 200) "device recreate" "HTTP $($recreate.StatusCode)"
    [void]$script:CreatedDevices.Add((DeviceId $Devices))

    $script:FullRequests += 1
    $restartFull = Invoke-ScaleRequest "restart-before-full" "/api/data/realtime/compact" "GET" $null 300 $true
    $cursorA = @{ snapshotId = [string](Get-JsonProperty $restartFull.Json "snapshotId"); configEpoch = [long](Get-JsonProperty $restartFull.Json "configEpoch"); revision = [long](Get-JsonProperty $restartFull.Json "revision") }
    Cleanup-ScaleDevices
    Stop-Backend
    $script:BackendReady = $false
    Start-Backend
    $smoke = Invoke-ScaleRequest "restart-smoke-create" "/api/config/local/devices" "POST" (Build-DevicePayload 1) 120 $true
    Assert-True ($smoke.StatusCode -eq 200) "restart smoke fixture" "HTTP $($smoke.StatusCode)"
    [void]$script:CreatedDevices.Add((DeviceId 1))
    $mismatch = Invoke-Delta "restart-old-cursor" $cursorA $true "SNAPSHOT_MISMATCH"
    $script:FullRequests += 1
    $fullB = Invoke-ScaleRequest "restart-full-b" "/api/data/realtime/compact" "GET" $null 120 $true
    $snapshotB = [string](Get-JsonProperty $fullB.Json "snapshotId")
    Assert-True ($snapshotB -ne $cursorA.snapshotId) "server restart snapshot" "snapshotId did not change after restart"
    Write-Pass "server restart recovery" "old snapshot=$($cursorA.snapshotId) new snapshot=$snapshotB mismatch reset verified"
}
function Write-RunInfo() {
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $javaExecutable = Resolve-JavaExecutable
    $javaVersion = (& cmd.exe /c "`"$javaExecutable`" -version 2>&1") -join "`n"
    $nodeVersion = try { (& node --version 2>$null) } catch { "unavailable" }
    $info = [pscustomobject]@{ os = $os.Caption; architecture = $os.OSArchitecture; cpu = $cpu.Name; logicalCores = $cpu.NumberOfLogicalProcessors; ramBytes = [long]($os.TotalVisibleMemorySize * 1024); javaVersion = $javaVersion; nodeVersion = $nodeVersion; points = $Points; devices = $Devices; durationSeconds = $DurationSeconds; clients = $Clients; pollIntervalSeconds = $PollIntervalSeconds; port = $Port; jarFileName = [System.IO.Path]::GetFileName($script:ResolvedJarPath); runId = $script:RunId }
    $info | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $script:OutputDir "run-info.json") -Encoding UTF8
}
function Write-Outputs($StageMetrics) {
    $script:RequestRows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $script:OutputDir "requests.csv")
    $script:ResourceRows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $script:OutputDir "resources.csv")
    $resources = @($script:ResourceRows)
    $heapStart = if ($resources.Count) { [long]$resources[0].heapUsedBytes } else { 0 }
    $heapEnd = if ($resources.Count) { [long]$resources[$resources.Count - 1].heapUsedBytes } else { 0 }
    $heapPeak = if ($resources.Count) { [long](($resources | Measure-Object -Property heapUsedBytes -Maximum).Maximum) } else { 0 }
    $threadsStart = if ($resources.Count) { [long]$resources[0].liveThreads } else { 0 }
    $threadsEnd = if ($resources.Count) { [long]$resources[$resources.Count - 1].liveThreads } else { 0 }
    $threadsPeak = if ($resources.Count) { [long](($resources | Measure-Object -Property liveThreads -Maximum).Maximum) } else { 0 }
    $summary = [pscustomobject]@{ result = if ($script:FailureCount -eq 0) { "PASS" } else { "FAIL" }; outputDir = $script:OutputDir; stages = $StageMetrics; durationSeconds = $DurationSeconds; clients = $Clients; totalRequests = $script:TotalRequests; fullRequests = $script:FullRequests; deltaRequests = $script:DeltaRequests; http2xx = $script:Http2xx; http4xx = $script:Http4xx; http5xx = $script:Http5xx; jsonFailures = $script:JsonFailures; unexpectedResets = $script:UnexpectedResets; expectedResets = $script:ExpectedResets; processCrashes = $script:ProcessCrashes; fullResponseBytesMin = if ($script:FullBytes.Count) { ($script:FullBytes | Measure-Object -Minimum).Minimum } else { 0 }; fullResponseBytesMax = if ($script:FullBytes.Count) { ($script:FullBytes | Measure-Object -Maximum).Maximum } else { 0 }; deltaResponseBytesMin = if ($script:DeltaBytes.Count) { ($script:DeltaBytes | Measure-Object -Minimum).Minimum } else { 0 }; deltaResponseBytesMax = if ($script:DeltaBytes.Count) { ($script:DeltaBytes | Measure-Object -Maximum).Maximum } else { 0 }; heapStartBytes = $heapStart; heapPeakBytes = $heapPeak; heapEndBytes = $heapEnd; threadsStart = $threadsStart; threadsPeak = $threadsPeak; threadsEnd = $threadsEnd }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $script:OutputDir "summary.json") -Encoding UTF8
}

$stageMetrics = @()
try {
    New-Item -ItemType Directory -Force -Path $script:OutputDir | Out-Null
    Write-RunInfo
    Assert-True ($Points -eq ($Devices * $script:PointsPerDevice)) "scale parameters" "Points must equal Devices × points/device"
    Start-Backend
    Cleanup-ScaleDevices
    Sample-Resources "start"
    Create-DevicesTo 10
    $stageA = Assert-FullStage "10k" 10 10000
    $stageMetrics += $stageA.Metric
    Sample-Resources "after-10k"
    Create-DevicesTo 50
    $stageB = Assert-FullStage "50k" 50 50000
    $stageMetrics += $stageB.Metric
    Sample-Resources "after-50k"
    Create-DevicesTo $Devices
    $stageC = Assert-FullStage "100k" $Devices $Points
    $stageMetrics += $stageC.Metric
    Sample-Resources "after-100k"
    $cursorAfterSoak = Run-Soak $stageC.Cursor
    Run-RecoveryChecks $cursorAfterSoak
    Sample-Resources "end"
} catch {
    Write-Fail "realtime scale soak" $_.Exception.Message
} finally {
    try { Cleanup-ScaleDevices } catch { Write-Fail "cleanup" $_.Exception.Message }
    Stop-Backend
    try { Write-Outputs $stageMetrics } catch { Write-Fail "write outputs" $_.Exception.Message }
}

if ($script:FailureCount -eq 0 -and $script:Http5xx -eq 0 -and $script:JsonFailures -eq 0 -and $script:ProcessCrashes -eq 0) {
    Write-Host "REALTIME SCALE SOAK PASSED"
    Write-Host "[METRIC] outputDir=$script:OutputDir"
    exit 0
}
Write-Host "REALTIME SCALE SOAK FAILED"
Write-Host "[METRIC] outputDir=$script:OutputDir"
exit 1
