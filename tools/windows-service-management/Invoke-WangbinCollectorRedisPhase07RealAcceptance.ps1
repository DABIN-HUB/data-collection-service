#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('PRECHECK', 'EXECUTE')][string]$Mode = 'PRECHECK',
    [ValidateRange(1, 65535)][int]$Port = 16379,
    [string]$EvidenceRoot = (Join-Path $env:LOCALAPPDATA 'hermes\cache\scratch\wangbin-redis-phase07-real-20261103'),
    [string]$ReportPath,
    [string]$AuthorizationPhrase,
    [switch]$RecoverPartial,
    [switch]$ElevatedChild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ExpectedAuthorizationPhrase = 'WangbinCollector-Phase07-16379-APPROVED'
$script:ExpectedRedisArchiveSha256 = 'af16df340525b431e9ec8dabfd625d5e7a233f82805b5418819904ea909c447f'
$script:ExpectedWinSwSha256 = '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da'
$script:ServiceName = 'WangbinCollector-Redis'
$script:RedisArchiveName = 'Redis-8.10.3-Windows-x64-msys2.zip'
$script:WinSwName = 'WinSW-x64.exe'
$script:SourceDescription = 'GitHub Release redis-windows/redis-windows 8.10.3; owner-authorized isolated Phase 07 acceptance.'
$script:WinSwSourceDescription = 'GitHub Release winsw/winsw v2.12.0; owner-authorized isolated Phase 07 acceptance.'
$script:Checks = New-Object System.Collections.Generic.List[object]
$script:Actions = New-Object System.Collections.Generic.List[object]
$script:ProbeKeys = $null
$script:Module = $null
$script:ForeignBaseline = $null
$script:RequestedReportPath = $ReportPath
$script:ReportPath = $null

function Add-Check {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][ValidateSet('PASS', 'FAIL', 'BLOCKED')][string]$Status,
        [Parameter(Mandatory)][string]$Detail,
        $Evidence
    )
    $script:Checks.Add([pscustomobject][ordered]@{
        Id = $Id
        Status = $Status
        Detail = $Detail
        Evidence = $Evidence
    })
}

function Add-Action {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][ValidateSet('PASS', 'FAIL', 'BLOCKED', 'NOT_RUN')][string]$Status,
        [Parameter(Mandatory)][string]$Detail,
        $Evidence
    )
    $script:Actions.Add([pscustomobject][ordered]@{
        Id = $Id
        Status = $Status
        Detail = $Detail
        Evidence = $Evidence
    })
}

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function ConvertTo-RedactedCommandLine {
    param([AllowNull()][string]$CommandLine)
    if ([string]::IsNullOrEmpty($CommandLine)) { return $null }
    return ($CommandLine -replace '(?i)(--?(?:pass|password|auth|token|secret)(?:=|\s+))[^\s]+', '$1[REDACTED]' -replace '(?i)(requirepass\s+)[^\s]+', '$1[REDACTED]')
}

function Get-ReportPathValue {
    if (-not [string]::IsNullOrWhiteSpace($script:RequestedReportPath)) {
        return [IO.Path]::GetFullPath($script:RequestedReportPath)
    }
    $stamp = [datetime]::Now.ToString('yyyyMMdd-HHmmss')
    return [IO.Path]::Combine([IO.Path]::GetFullPath($EvidenceRoot), ('Phase07-RealAcceptance-{0}.json' -f $stamp))
}

function Write-Report {
    param([int]$ExitCode = 1)
    $blocked = @($script:Checks | Where-Object { $_.Status -eq 'BLOCKED' }).Count -gt 0
    $failed = @($script:Checks | Where-Object { $_.Status -eq 'FAIL' }).Count -gt 0
    $actionBlocked = @($script:Actions | Where-Object { $_.Status -eq 'BLOCKED' }).Count -gt 0
    $actionFailed = @($script:Actions | Where-Object { $_.Status -eq 'FAIL' }).Count -gt 0
    $verdict = if ($blocked -or $actionBlocked) { 'BLOCKED' } elseif ($failed -or $actionFailed) { 'FAIL' } elseif ($Mode -eq 'PRECHECK') { 'PRECHECK_PASS_EXECUTION_NOT_REQUESTED' } else { 'PASS' }
    $report = [ordered]@{
        Schema = 1
        Phase = '07'
        TestRunner = 'Invoke-WangbinCollectorRedisPhase07RealAcceptance.ps1'
        Mode = $Mode
        Scope = 'WANGBINCOLLECTOR_REDIS_ONLY_NO_R2A2_NO_REISUN_NO_APPLICATION_YAML_NO_HERMES_EDIT'
        ServiceName = $script:ServiceName
        Port = $Port
        Host = $env:COMPUTERNAME
        User = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        Administrator = (Test-IsAdministrator)
        StartedUtc = $script:StartedUtc
        CompletedUtc = [datetime]::UtcNow.ToString('o')
        Verdict = $verdict
        Checks = @($script:Checks.ToArray())
        Actions = @($script:Actions.ToArray())
        TerminationRules = @(
            '现有 Redis 身份未知时保持只读保护；只有无法证明与目标服务、目标目录或 16379 完全隔离时才 BLOCKED。',
            '目标服务、目标 Redis 子目录、目标端口或 R2A2/REISUN 资源冲突立即 BLOCKED。',
            '任何受控接口返回 UNKNOWN、超时或所有权校验失败立即停止。',
            '禁止 taskkill、Stop-Process、按端口杀进程、FLUSHALL 和 FLUSHDB。',
            '清理失败时保留现场，不删除用户目录，不接管未知资源。'
        )
        ReportPath = $script:ReportPath
    }
    $json = $report | ConvertTo-Json -Depth 20
    try {
        $parent = Split-Path -Parent $script:ReportPath
        if (-not [IO.Directory]::Exists($parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
        [IO.File]::WriteAllText($script:ReportPath, $json, (New-Object Text.UTF8Encoding($true)))
    } catch {
        $json = ($report + [ordered]@{ ReportWriteError = $_.Exception.Message }) | ConvertTo-Json -Depth 20
        try { [IO.File]::WriteAllText($script:ReportPath, $json, (New-Object Text.UTF8Encoding($true))) } catch { }
    }
    Write-Output $json
}

function Get-ArtifactPath {
    param([Parameter(Mandatory)][string]$Name)
    return [IO.Path]::Combine([IO.Path]::GetFullPath($EvidenceRoot), $Name)
}

function Test-ExistingPathIsSafeFile {
    param([Parameter(Mandatory)][string]$Path)
    if (-not [IO.File]::Exists($Path)) { return $false }
    $item = Get-Item -LiteralPath $Path -Force
    return (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0)
}

function Get-SafeDirectoryEvidence {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return [pscustomobject]@{ Path = $Path; Exists = $false } }
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (-not $item.PSIsContainer) { throw ('目标路径不是目录：{0}' -f $Path) }
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw ('目录是重解析点：{0}' -f $Path) }
    $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
    $sddl = $acl.Sddl
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        $aclHash = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($sddl)))).Replace('-', '').ToLowerInvariant()
    } finally { $hash.Dispose() }
    return [pscustomobject]@{
        Path = $Path
        Exists = $true
        IsReparsePoint = $false
        Owner = [string]$acl.Owner
        AccessRulesProtected = [bool]$acl.AreAccessRulesProtected
        AclSha256 = $aclHash
    }
}

function Get-ProcessOwnerText {
    param([Parameter(Mandatory)]$Process)
    try {
        $owner = Invoke-CimMethod -InputObject $Process -MethodName GetOwner -ErrorAction Stop
        if ($owner.ReturnValue -eq 0) { return ('{0}\{1}' -f $owner.Domain, $owner.User) }
    } catch { }
    return 'UNKNOWN'
}

function Get-RedisProcessSnapshot {
    param([Parameter(Mandatory)]$AllProcesses, [Parameter(Mandatory)]$AllServices)
    $redisProcesses = @($AllProcesses | Where-Object { [string]$_.Name -ieq 'redis-server.exe' })
    $result = New-Object System.Collections.Generic.List[object]
    foreach ($process in $redisProcesses) {
        $parent = @($AllProcesses | Where-Object { [int]$_.ProcessId -eq [int]$process.ParentProcessId }) | Select-Object -First 1
        $services = @($AllServices | Where-Object { [int]$_.ProcessId -eq [int]$process.ProcessId } | Select-Object -ExpandProperty Name)
        $listeners = @()
        try {
            if ($null -eq (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) { throw 'Get-NetTCPConnection 不可用。' }
            $listeners = @(Get-NetTCPConnection -OwningProcess ([int]$process.ProcessId) -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,RemoteAddress,RemotePort,State)
        } catch {
            throw ('无法读取 redis-server PID {0} 的监听归属：{1}' -f $process.ProcessId, $_.Exception.Message)
        }
        $result.Add([pscustomobject][ordered]@{
            ProcessId = [int]$process.ProcessId
            ParentProcessId = [int]$process.ParentProcessId
            ExecutablePath = $process.ExecutablePath
            CommandLine = ConvertTo-RedactedCommandLine $process.CommandLine
            CommandLinePresent = -not [string]::IsNullOrWhiteSpace([string]$process.CommandLine)
            Owner = Get-ProcessOwnerText -Process $process
            ParentExecutablePath = if ($null -ne $parent) { $parent.ExecutablePath } else { $null }
            ParentCommandLine = if ($null -ne $parent) { ConvertTo-RedactedCommandLine $parent.CommandLine } else { $null }
            ServiceNames = @($services)
            Listeners = @($listeners)
        })
    }
    return $result.ToArray()
}

function ConvertTo-AuditPath {
    param([AllowNull()][string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $null }
    try {
        $candidate = $Path.Trim().Trim('"')
        if ($candidate -match '^/cygdrive/([A-Za-z])/(.+)$') { $candidate = ('{0}:\{1}' -f $Matches[1], ($Matches[2] -replace '/', '\')) }
        $full = [IO.Path]::GetFullPath($candidate)
        if ($full.Length -gt 3) { return $full.TrimEnd('\') }
        return $full
    } catch { return $null }
}

function Test-AuditPathOverlap {
    param([AllowNull()][string]$Left, [AllowNull()][string]$Right)
    $leftPath = ConvertTo-AuditPath $Left
    $rightPath = ConvertTo-AuditPath $Right
    if ([string]::IsNullOrWhiteSpace($leftPath) -or [string]::IsNullOrWhiteSpace($rightPath)) { return $false }
    return $leftPath.Equals($rightPath, [StringComparison]::OrdinalIgnoreCase) -or
        $leftPath.StartsWith($rightPath + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $rightPath.StartsWith($leftPath + '\', [StringComparison]::OrdinalIgnoreCase)
}

function Get-AuditExecutablePath {
    param([AllowNull()][string]$CommandLine)
    if ([string]::IsNullOrWhiteSpace($CommandLine)) { return $null }
    $match = [regex]::Match($CommandLine, '^\s*"([^"]+\.exe)"', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if ($match.Success) { return ConvertTo-AuditPath $match.Groups[1].Value }
    $match = [regex]::Match($CommandLine, '^\s*([^\s]+\.exe)(?:\s|$)', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if ($match.Success) { return ConvertTo-AuditPath $match.Groups[1].Value }
    return $null
}

function Get-AuditPathCandidates {
    param([AllowNull()][string]$Text)
    $paths = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    foreach ($match in [regex]::Matches($Text, '"((?:[A-Za-z]:|\\\\|/cygdrive/[A-Za-z]/)[^"\r\n]+)"|((?:[A-Za-z]:|\\\\|/cygdrive/[A-Za-z]/)[^\s\r\n]+)', [Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        $value = if ($match.Groups[1].Success) { $match.Groups[1].Value } else { $match.Groups[2].Value }
        $path = ConvertTo-AuditPath $value.TrimEnd(',', ';')
        if ($null -ne $path -and -not $paths.Contains($path)) { $paths.Add($path) }
    }
    foreach ($match in [regex]::Matches($Text, '(?:file:)?((?:[A-Za-z]:|/cygdrive/[A-Za-z]/)[^"\s,;]+)', [Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        $path = ConvertTo-AuditPath $match.Groups[1].Value.TrimEnd(',', ';')
        if ($null -ne $path -and -not $paths.Contains($path)) { $paths.Add($path) }
    }
    return @($paths)
}

function Get-AuditServiceSecurity {
    param([Parameter(Mandatory)][string]$Name)
    $raw = @(& sc.exe sdshow $Name 2>$null)
    $sddl = @($raw | Where-Object { [string]$_ -match '^\s*D:' } | Select-Object -First 1)
    if ($sddl.Count -ne 1) { throw ('无法读取服务 {0} 的 SCM DACL；归属状态 UNKNOWN。' -f $Name) }
    $sddlText = ([string]$sddl[0]).Trim()
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $sddlHash = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($sddlText)))).Replace('-', '').ToLowerInvariant() } finally { $hash.Dispose() }
    return [pscustomobject]@{ Sddl = $sddlText; SddlSha256 = $sddlHash }
}

function Get-AuditPathSecurity {
    param([Parameter(Mandatory)][string]$Path)
    $full = ConvertTo-AuditPath $Path
    if ([string]::IsNullOrWhiteSpace($full)) { throw '发现无法解析的关键路径。' }
    if (-not (Test-Path -LiteralPath $full)) { throw ('关键路径不存在：{0}' -f $full) }
    $item = Get-Item -LiteralPath $full -Force -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw ('关键路径是重解析点：{0}' -f $full) }
    $acl = Get-Acl -LiteralPath $full -ErrorAction Stop
    $sddl = [string]$acl.Sddl
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $aclHash = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($sddl)))).Replace('-', '').ToLowerInvariant() } finally { $hash.Dispose() }
    return [pscustomobject][ordered]@{
        Path = $full
        IsDirectory = [bool]$item.PSIsContainer
        Owner = [string]$acl.Owner
        AccessRulesProtected = [bool]$acl.AreAccessRulesProtected
        AclSha256 = $aclHash
    }
}

function Get-AuditServiceSid {
    param([Parameter(Mandatory)][string]$Account)
    if ($Account -notmatch '^NT SERVICE\\(.+)$') { return $null }
    try {
        $sid = (New-Object Security.Principal.NTAccount('NT SERVICE', $Matches[1])).Translate([Security.Principal.SecurityIdentifier]).Value
        return $sid
    } catch { throw ('无法解析服务账号 {0} 的 Service SID。' -f $Account) }
}

function Get-AuditServiceRegistry {
    param([Parameter(Mandatory)][string]$Name)
    if ($Name -notmatch '^[A-Za-z0-9._-]{1,256}$') { throw ('服务名格式不安全：{0}' -f $Name) }
    $keyPath = 'HKLM:\SYSTEM\CurrentControlSet\Services\' + $Name
    if (-not (Test-Path -LiteralPath $keyPath)) { throw ('服务注册项不存在：{0}' -f $Name) }
    $item = Get-ItemProperty -LiteralPath $keyPath -ErrorAction Stop
    $dependencies = @()
    if ($item.PSObject.Properties.Name -contains 'DependOnService') { $dependencies = @($item.DependOnService) }
    return [pscustomobject]@{
        ServiceSidType = [int]$item.ServiceSidType
        Start = [int]$item.Start
        DependOnService = $dependencies
        FailureActionsPresent = $item.PSObject.Properties.Name -contains 'FailureActions'
    }
}

function Get-AuditDescendants {
    param([Parameter(Mandatory)]$Root, [Parameter(Mandatory)]$AllProcesses)
    $found = New-Object System.Collections.Generic.List[object]
    $frontier = @($Root)
    for ($depth = 0; $depth -lt 32 -and $frontier.Count -gt 0; $depth++) {
        $next = @($AllProcesses | Where-Object { $frontier.ProcessId -contains $_.ParentProcessId })
        foreach ($process in $next) {
            if (-not (@($found | Where-Object { [int]$_.ProcessId -eq [int]$process.ProcessId }).Count -gt 0)) { $found.Add($process) }
        }
        $frontier = $next
    }
    return $found.ToArray()
}

function Get-AuditConfigAndDataPaths {
    param([Parameter(Mandatory)][string[]]$Texts, [Parameter(Mandatory)][string[]]$Candidates)
    $config = New-Object System.Collections.Generic.List[string]
    $data = New-Object System.Collections.Generic.List[string]
    foreach ($path in $Candidates) {
        if (-not (Test-Path -LiteralPath $path) -and $path -notmatch '(?i)\.(conf|config|xml|yml|yaml|json|properties)$') { continue }
        if ($path -match '(?i)\.(conf|config|xml|yml|yaml|json|properties)$' -or $path -match '(?i)(config|application)') { if (-not $config.Contains($path)) { $config.Add($path) } }
        if ($path -match '(?i)(\\|/)(data|database|db|sqlite|redis|state|log|logs)(\\|/|$)') { if (-not $data.Contains($path)) { $data.Add($path) } }
        $dataRootMatch = [regex]::Match($path, '(?i)^(.+?)[\\/](data|database|db|sqlite|state|log|logs)(?:[\\/]|$)')
        if ($dataRootMatch.Success) {
            $dataRoot = ConvertTo-AuditPath ($dataRootMatch.Groups[1].Value + '\' + $dataRootMatch.Groups[2].Value)
            if ($null -ne $dataRoot -and -not $data.Contains($dataRoot)) { $data.Add($dataRoot) }
        }
    }
    foreach ($configPath in @($config.ToArray())) {
        if (-not [IO.File]::Exists($configPath)) { continue }
        try {
            foreach ($line in @(Get-Content -LiteralPath $configPath -ErrorAction Stop)) {
                if ($line -match '^\s*dir\s+"?([^"\s]+)"?\s*$' -or $line -match '^\s*(?:data|database|sqlite|state|logdir)\s*[:=]\s*"?([^"\s]+)"?\s*$') {
                    $value = $Matches[1]
                    if (-not [IO.Path]::IsPathRooted($value)) { $value = Join-Path ([IO.Path]::GetDirectoryName($configPath)) $value }
                    $resolved = ConvertTo-AuditPath $value
                    if ($null -ne $resolved -and -not $data.Contains($resolved)) { $data.Add($resolved) }
                }
            }
        } catch { throw ('无法只读读取关键配置路径 {0}：{1}' -f $configPath, $_.Exception.Message) }
    }
    return [pscustomobject]@{ ConfigurationPaths = @($config.ToArray()); DataPaths = @($data.ToArray()) }
}

function Get-ForeignServiceAudit {
    param([Parameter(Mandatory)][string[]]$Names, [Parameter(Mandatory)]$AllProcesses, [Parameter(Mandatory)]$AllServices, [Parameter(Mandatory)]$AllListeners, [Parameter(Mandatory)]$Layout)
    $audits = New-Object System.Collections.Generic.List[object]
    $blockers = New-Object System.Collections.Generic.List[string]
    foreach ($name in $Names) {
        $service = @($AllServices | Where-Object { $_.Name -ceq $name }) | Select-Object -First 1
        if ($null -eq $service) { $blockers.Add(('SCM 服务缺失：{0}' -f $name)); continue }
        $registry = Get-AuditServiceRegistry -Name $name
        $serviceSecurity = Get-AuditServiceSecurity -Name $name
        $serviceExecutable = Get-AuditExecutablePath -CommandLine ([string]$service.PathName)
        $serviceProcess = @($AllProcesses | Where-Object { [int]$_.ProcessId -eq [int]$service.ProcessId }) | Select-Object -First 1
        if ($service.State -eq 'Running' -and ($null -eq $serviceProcess -or [string]::IsNullOrWhiteSpace([string]$serviceProcess.ExecutablePath))) { $blockers.Add(('服务进程路径不可证明：{0}' -f $name)) }
        if ($service.State -eq 'Running' -and $null -eq $serviceExecutable) { $blockers.Add(('SCM wrapper 路径不可解析：{0}' -f $name)) }
        if ($null -ne $serviceProcess -and $null -ne $serviceExecutable -and (ConvertTo-AuditPath $serviceProcess.ExecutablePath) -ine $serviceExecutable) { $blockers.Add(('SCM wrapper 与服务进程路径不一致：{0}' -f $name)) }
        $descendants = if ($null -ne $serviceProcess) { @(Get-AuditDescendants -Root $serviceProcess -AllProcesses $AllProcesses) } else { @() }
        $chain = @($serviceProcess) + @($descendants)
        $missingOwner = @($chain | Where-Object { [string]::IsNullOrWhiteSpace((Get-ProcessOwnerText -Process $_)) -or (Get-ProcessOwnerText -Process $_) -eq 'UNKNOWN' })
        if ($missingOwner.Count -gt 0) { $blockers.Add(('服务进程树所有者不可证明：{0}' -f $name)) }
        $expectedOwner = switch -Regex ([string]$service.StartName) {
            '^LocalSystem$' { 'NT AUTHORITY\SYSTEM'; break }
            '^NT AUTHORITY\SYSTEM$' { 'NT AUTHORITY\SYSTEM'; break }
            '^LocalService$' { 'NT AUTHORITY\LOCAL SERVICE'; break }
            '^NetworkService$' { 'NT AUTHORITY\NETWORK SERVICE'; break }
            default { [string]$service.StartName }
        }
        foreach ($process in $chain) {
            $owner = Get-ProcessOwnerText -Process $process
            if ($owner -ne 'UNKNOWN' -and $owner -ine $expectedOwner) { $blockers.Add(('服务进程所有者与 SCM 服务账号不一致：{0}/PID {1}' -f $name, $process.ProcessId)) }
            if ([string]::IsNullOrWhiteSpace([string]$process.ExecutablePath) -or [string]::IsNullOrWhiteSpace([string]$process.CommandLine)) { $blockers.Add(('服务进程路径或命令行不可证明：{0}/PID {1}' -f $name, $process.ProcessId)) }
        }
        $texts = New-Object System.Collections.Generic.List[string]
        if ($service.PathName) { $texts.Add([string]$service.PathName) }
        foreach ($process in $chain) { if ($process.CommandLine) { $texts.Add([string]$process.CommandLine) } }
        if ($null -ne $serviceExecutable) {
            $xml = [IO.Path]::ChangeExtension($serviceExecutable, '.xml')
            if ([IO.File]::Exists($xml)) { $texts.Add([IO.File]::ReadAllText($xml)) }
        }
        $candidates = New-Object System.Collections.Generic.List[string]
        foreach ($text in $texts) { foreach ($candidate in @(Get-AuditPathCandidates -Text $text)) { if (-not $candidates.Contains($candidate)) { $candidates.Add($candidate) } } }
        foreach ($process in $chain) { $exe = ConvertTo-AuditPath $process.ExecutablePath; if ($null -ne $exe -and -not $candidates.Contains($exe)) { $candidates.Add($exe) } }
        $paths = Get-AuditConfigAndDataPaths -Texts $texts.ToArray() -Candidates $candidates.ToArray()
        if ($paths.ConfigurationPaths.Count -eq 0) { $blockers.Add(('无法证明配置路径：{0}' -f $name)) }
        if ($paths.DataPaths.Count -eq 0) { $blockers.Add(('无法证明数据目录：{0}' -f $name)) }
        $pathSecurity = New-Object System.Collections.Generic.List[object]
        $securityCandidates = @($candidates | Where-Object { (Test-Path -LiteralPath $_ -ErrorAction SilentlyContinue) -or $_ -match '(?i)\.(conf|config|xml|yml|yaml|json|properties)$' } | Select-Object -Unique)
        foreach ($path in $securityCandidates) {
            try { $pathSecurity.Add((Get-AuditPathSecurity -Path $path)) } catch { $blockers.Add(('{0} 关键路径安全证据失败：{1}' -f $name, $_.Exception.Message)) }
        }
        foreach ($path in @($pathSecurity | Select-Object -ExpandProperty Path)) {
            foreach ($targetRoot in @($Layout.ProductCodeRoot, $Layout.ProductDataRoot)) {
                if (Test-AuditPathOverlap -Left $path -Right $targetRoot) { $blockers.Add(('外部服务路径与阶段 07 目标根重叠：{0}' -f $path)) }
            }
        }
        $serviceSid = Get-AuditServiceSid -Account ([string]$service.StartName)
        $redisChildren = @($descendants | Where-Object { [string]$_.Name -ieq 'redis-server.exe' })
        if ($name -like '*-Redis' -and $redisChildren.Count -ne 1) { $blockers.Add(('Redis wrapper 子进程数量不是 1：{0}' -f $name)) }
        $listeners = @($AllListeners | Where-Object { @($chain.ProcessId) -contains $_.OwningProcess } | Select-Object OwningProcess,LocalAddress,LocalPort,RemoteAddress,RemotePort,State)
        if (@($listeners | Where-Object { [int]$_.LocalPort -eq [int]$Port }).Count -gt 0) { $blockers.Add(('外部服务占用阶段 07 端口：{0}' -f $name)) }
        $audits.Add([pscustomobject][ordered]@{
            ServiceName = $name
            State = [string]$service.State
            ProcessId = [int]$service.ProcessId
            StartName = [string]$service.StartName
            ExpectedProcessOwner = $expectedOwner
            ServiceSid = $serviceSid
            Registry = $registry
            ServiceDaclSha256 = $serviceSecurity.SddlSha256
            ServicePathName = ConvertTo-RedactedCommandLine ([string]$service.PathName)
            WrapperExecutable = $serviceExecutable
            ProcessTree = @($chain | Select-Object ProcessId,ParentProcessId,Name,ExecutablePath,CreationDate,@{n='Owner';e={ Get-ProcessOwnerText -Process $_ }},@{n='CommandLine';e={ ConvertTo-RedactedCommandLine $_.CommandLine }})
            RedisChildren = @($redisChildren | Select-Object ProcessId,ParentProcessId,ExecutablePath,CreationDate,@{n='Owner';e={ Get-ProcessOwnerText -Process $_ }},@{n='CommandLine';e={ ConvertTo-RedactedCommandLine $_.CommandLine }})
            Listeners = $listeners
            ConfigurationPaths = @($paths.ConfigurationPaths)
            DataPaths = @($paths.DataPaths)
            PathSecurity = @($pathSecurity.ToArray())
        })
    }
    return [pscustomobject]@{ Audits = @($audits.ToArray()); Blockers = @($blockers.ToArray()) }
}

function Get-ForeignBaselineFingerprint {
    param([Parameter(Mandatory)]$Audit)
    $stableAudits = @(
        foreach ($entry in @($Audit.Audits | Sort-Object ServiceName)) {
            [pscustomobject][ordered]@{
                ServiceName = [string]$entry.ServiceName
                State = [string]$entry.State
                ProcessId = [int]$entry.ProcessId
                StartName = [string]$entry.StartName
                ExpectedProcessOwner = [string]$entry.ExpectedProcessOwner
                ServiceSid = [string]$entry.ServiceSid
                Registry = [pscustomobject][ordered]@{
                    ServiceSidType = [int]$entry.Registry.ServiceSidType
                    Start = [int]$entry.Registry.Start
                    DependOnService = @($entry.Registry.DependOnService | Sort-Object)
                    FailureActionsPresent = [bool]$entry.Registry.FailureActionsPresent
                }
                ServiceDaclSha256 = [string]$entry.ServiceDaclSha256
                ServicePathName = [string]$entry.ServicePathName
                WrapperExecutable = [string]$entry.WrapperExecutable
                ProcessTree = @($entry.ProcessTree | Sort-Object ProcessId | ForEach-Object {
                    [pscustomobject][ordered]@{
                        ProcessId = [int]$_.ProcessId
                        ParentProcessId = [int]$_.ParentProcessId
                        Name = [string]$_.Name
                        ExecutablePath = [string]$_.ExecutablePath
                        CreationDate = [string]$_.CreationDate
                        Owner = [string]$_.Owner
                        CommandLine = [string]$_.CommandLine
                    }
                })
                RedisChildren = @($entry.RedisChildren | Sort-Object ProcessId | ForEach-Object {
                    [pscustomobject][ordered]@{
                        ProcessId = [int]$_.ProcessId
                        ParentProcessId = [int]$_.ParentProcessId
                        ExecutablePath = [string]$_.ExecutablePath
                        CreationDate = [string]$_.CreationDate
                        Owner = [string]$_.Owner
                        CommandLine = [string]$_.CommandLine
                    }
                })
                # Established TCP sessions contain ephemeral remote ports and are expected to change
                # while the protected service is running. The stable boundary is the local bind set.
                StableListeners = @($entry.Listeners | Where-Object { [int]$_.State -eq 2 } | Sort-Object OwningProcess,LocalAddress,LocalPort | ForEach-Object {
                    [pscustomobject][ordered]@{
                        OwningProcess = [int]$_.OwningProcess
                        LocalAddress = [string]$_.LocalAddress
                        LocalPort = [int]$_.LocalPort
                        State = [int]$_.State
                    }
                })
                ConfigurationPaths = @($entry.ConfigurationPaths | Sort-Object)
                DataPaths = @($entry.DataPaths | Sort-Object)
                PathSecurity = @($entry.PathSecurity | Sort-Object Path | ForEach-Object {
                    [pscustomobject][ordered]@{
                        Path = [string]$_.Path
                        IsDirectory = [bool]$_.IsDirectory
                        Owner = [string]$_.Owner
                        AccessRulesProtected = [bool]$_.AccessRulesProtected
                        AclSha256 = [string]$_.AclSha256
                    }
                })
            }
        }
    )
    $canonical = $stableAudits | ConvertTo-Json -Depth 20 -Compress
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-', '').ToLowerInvariant() } finally { $hash.Dispose() }
}

function Write-ForeignBaseline {
    param([Parameter(Mandatory)]$Audit)
    $baseline = [pscustomobject][ordered]@{ CreatedUtc = [datetime]::UtcNow.ToString('o'); Fingerprint = Get-ForeignBaselineFingerprint -Audit $Audit; Audits = $Audit.Audits }
    $path = Join-Path ([IO.Path]::GetFullPath($EvidenceRoot)) ('r2a2-baseline-' + [datetime]::Now.ToString('yyyyMMdd-HHmmss-fff') + '.json')
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
    [IO.File]::WriteAllText($path, ($baseline | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding($true)))
    return [pscustomobject]@{ Path = $path; Fingerprint = $baseline.Fingerprint; CreatedUtc = $baseline.CreatedUtc; ServiceNames = @($Audit.Audits | Select-Object -ExpandProperty ServiceName) }
}

function Test-Phase07OperationScope {
    param([Parameter(Mandatory)]$Layout)
    $modulePath = Join-Path $PSScriptRoot 'WangbinCollectorRedis.psm1'
    $source = [IO.File]::ReadAllText($modulePath)
    $forbidden = @()
    if ($source -match 'Set-ControlledDirectoryAcl\s+-Path\s+\$layout\.(ProductCodeParentRoot|ProductDataParentRoot)') { $forbidden += '公共父目录存在直接 ACL 设置调用。' }
    if ($source -match '(?i)(Remove-Item|\[IO\.Directory\]::Delete)') { $forbidden += '受控模块包含可能删除目录的调用。' }
    if ($forbidden.Count -gt 0) { return [pscustomobject]@{ Safe = $false; ModulePath = $modulePath; Reasons = $forbidden } }
    return [pscustomobject]@{ Safe = $true; ModulePath = $modulePath; ManagedRoots = @($Layout.ProductCodeRoot, $Layout.ProductDataRoot, $Layout.CodeRoot, $Layout.DataRoot, $Layout.ConfigRoot, $Layout.RedisDataRoot, $Layout.RedisLogRoot, $Layout.StateRoot); PublicParents = @($Layout.ProductCodeParentRoot, $Layout.ProductDataParentRoot); Note = '公共父目录仅做重解析检查并在缺失时创建；ACL、文件和 SCM 操作均限定在阶段 07 专用根及 WangbinCollector-Redis。' }
}

function Assert-ForeignBaselineUnchanged {
    param([Parameter(Mandatory)]$Baseline, [Parameter(Mandatory)]$AllProcesses, [Parameter(Mandatory)]$AllServices, [Parameter(Mandatory)]$AllListeners, [Parameter(Mandatory)]$Layout, [Parameter(Mandatory)][string[]]$Names)
    $current = Get-ForeignServiceAudit -Names $Names -AllProcesses $AllProcesses -AllServices $AllServices -AllListeners $AllListeners -Layout $Layout
    if ($current.Blockers.Count -gt 0 -or (Get-ForeignBaselineFingerprint -Audit $current) -cne [string]$Baseline.Fingerprint) { throw 'R2-A2 只读基线发生变化或隔离证据失效；拒绝继续阶段 07 生命周期操作。' }
    return $current
}

function Confirm-ForeignBaselineBeforeLifecycle {
    if ($null -eq $script:ForeignBaseline -or @($script:ForeignBaseline.ServiceNames).Count -eq 0) { return }
    try {
        $processes = @(Get-CimInstance Win32_Process -ErrorAction Stop)
        $services = @(Get-CimInstance Win32_Service -ErrorAction Stop)
        $listeners = @(Get-NetTCPConnection -ErrorAction Stop | Select-Object OwningProcess,LocalAddress,LocalPort,RemoteAddress,RemotePort,State)
        $layout = Get-WangbinCollectorRedisLayout -Port $Port
        $null = Assert-ForeignBaselineUnchanged -Baseline $script:ForeignBaseline -AllProcesses $processes -AllServices $services -AllListeners $listeners -Layout $layout -Names @($script:ForeignBaseline.ServiceNames)
        Add-Action -Id 'FOREIGN_BASELINE_READBACK' -Status PASS -Detail 'R2-A2 服务、进程、端口、路径和 ACL 基线在阶段 07 生命周期边界保持不变。' -Evidence ([pscustomobject]@{ Fingerprint = $script:ForeignBaseline.Fingerprint; ServiceNames = @($script:ForeignBaseline.ServiceNames) })
    } catch {
        Add-Action -Id 'FOREIGN_BASELINE_READBACK' -Status BLOCKED -Detail ('R2-A2 只读基线发生变化；停止阶段 07 生命周期：{0}' -f $_.Exception.Message)
        throw
    }
}

function Invoke-AuthorizedPartialInstallRecovery {
    if (-not $RecoverPartial) { return }
    try {
    if ($Mode -ne 'EXECUTE') { throw '部分安装恢复仅允许在 EXECUTE 模式使用。' }
    $layout = & $script:Module { param($port) Get-WangbinCollectorRedisLayout -Port $port } $Port
    $evidenceRootPath = [IO.Path]::GetFullPath($EvidenceRoot)
    $reportCandidates = New-Object System.Collections.Generic.List[object]
    foreach ($candidatePath in @(Get-ChildItem -LiteralPath $evidenceRootPath -Filter 'execute-*.json' -File -ErrorAction Stop | Select-Object -ExpandProperty FullName)) {
        try {
            $candidate = Get-Content -LiteralPath $candidatePath -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
            $stopGate = @($candidate.Actions | Where-Object { [string]$_.Id -eq 'ACCEPTANCE_STOP_GATE' }) | Select-Object -First 1
            $recoverableRollback = $null -ne $stopGate -and (
                [string]$stopGate.Detail -like '*NO_SERVICE_REGISTERED*' -or
                [string]$stopGate.Detail -like '*SERVICE_RETAINED_UNVERIFIED*'
            )
            if ([string]$candidate.Mode -ceq 'EXECUTE' -and [string]$candidate.Verdict -ceq 'BLOCKED' -and [string]$candidate.ServiceName -ceq $script:ServiceName -and [int]$candidate.Port -eq $Port -and $recoverableRollback) {
                $reportCandidates.Add([pscustomobject]@{ Path = $candidatePath; Report = $candidate; CompletedUtc = ([datetime]::Parse([string]$candidate.CompletedUtc)).ToUniversalTime() })
            }
        } catch { }
    }
    $priorCandidate = @($reportCandidates | Sort-Object CompletedUtc -Descending) | Select-Object -First 1
    if ($null -eq $priorCandidate) { throw ('缺少可验证的本阶段失败 EXECUTE 报告：{0}' -f $evidenceRootPath) }
    $priorReportPath = [string]$priorCandidate.Path
    $priorReport = $priorCandidate.Report
    $partialMarker = & $script:Module { param($l) Get-ManagedMarker $l } $layout
    if ($null -eq $partialMarker -or [string]$partialMarker.RegistrationState -cne 'Installing') { throw '部分安装管理记录不是本阶段未完成 INSTALL 状态，拒绝恢复。' }
    if ([string]$partialMarker.RedisArchiveSha256 -cne $script:ExpectedRedisArchiveSha256 -or [string]$partialMarker.WrapperExecutableSha256 -cne $script:ExpectedWinSwSha256) { throw '部分安装管理记录的固定构件身份不匹配，拒绝恢复。' }
    $services = @(Get-CimInstance Win32_Service -ErrorAction Stop | Where-Object { [string]$_.Name -eq $script:ServiceName })
        if ($services.Count -gt 1) { throw '目标服务查询返回多个同名记录，拒绝恢复。' }
        if ($services.Count -eq 1) {
            & $script:Module {
                param($l, $s, $expectedWrapperHash)
                if ([string]$s.State -ne 'Stopped' -or [int]$s.ProcessId -ne 0) { throw '部分安装目标服务不是已停止且无 PID 状态，拒绝注销。' }
                Assert-ServiceConfiguration -Layout $l -Service $s
                if ((Get-Sha256 $l.WrapperExecutable) -cne $expectedWrapperHash) { throw '部分安装 wrapper SHA-256 不匹配固定 WinSW 构件，拒绝注销。' }
                if (@(Get-ManagedRedisProcessCandidates -Layout $l).Count -ne 0) { throw '部分安装目标仍存在匹配 Redis 进程，拒绝注销。' }
                if (@(Get-ListenerRecord -Port $l.Port).Count -ne 0) { throw '部分安装目标端口仍有监听者，拒绝注销。' }
                Invoke-NativeProcess -FilePath $l.WrapperExecutable -Arguments 'uninstall' -Deadline ([datetime]::UtcNow.AddSeconds(30))
                $deadline = [datetime]::UtcNow.AddSeconds(15)
                while ($null -ne (Get-ServiceRecord $l.ServiceName)) {
                    if ([datetime]::UtcNow -ge $deadline) { throw '部分安装目标服务注销后未在期限内消失。' }
                    Start-Sleep -Milliseconds 200
                }
            } $layout $services[0] $script:ExpectedWinSwSha256
        }
        $services = @(Get-CimInstance Win32_Service -ErrorAction Stop | Where-Object { [string]$_.Name -eq $script:ServiceName })
        if ($services.Count -ne 0) { throw '目标服务注销后仍存在，拒绝删除任何部分安装目录。' }
        if ($null -eq (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) { throw 'Get-NetTCPConnection 不可用，拒绝恢复。' }
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
        if ($listeners.Count -ne 0) { throw ('测试端口 {0} 已有监听者，拒绝恢复。' -f $Port) }
        foreach ($parent in @($layout.ProductCodeParentRoot, $layout.ProductDataParentRoot)) {
            & $script:Module { param($path) Assert-NoReparsePoint $path } $parent
        }
        $roots = @($layout.ProductCodeRoot, $layout.ProductDataRoot)
        $expectedDirectories = @(
            $layout.ProductCodeRoot,
            $layout.CodeRoot,
            $layout.ProductDataRoot,
            $layout.ConfigRoot,
            $layout.RedisDataRoot,
            $layout.RedisLogRoot,
            $layout.StateRoot
        ) | ForEach-Object { [IO.Path]::GetFullPath($_).TrimEnd('\') }
        $expectedDirectoryKeys = @{}
        foreach ($expectedDirectory in $expectedDirectories) {
            $expectedDirectoryKeys[$expectedDirectory.ToLowerInvariant()] = $expectedDirectory
        }
        $priorStartedUtc = ([datetime]::Parse([string]$priorReport.StartedUtc)).ToUniversalTime()
        $priorCompletedUtc = ([datetime]::Parse([string]$priorReport.CompletedUtc)).ToUniversalTime()
        $archivePath = Join-Path $evidenceRootPath $script:RedisArchiveName
        if (-not [IO.File]::Exists($archivePath)) { throw ('缺少用于部分安装归属校验的 Redis ZIP：{0}' -f $archivePath) }
        $distribution = & $script:Module { param($path, $sha) Assert-RedisDistributionArchive -ArchivePath $path -ExpectedSha256 $sha } $archivePath $script:ExpectedRedisArchiveSha256
        $expectedFiles = @($distribution.Files | Sort-Object Name)
        if ($expectedFiles.Count -ne 16) { throw ('固定 Redis 发行文件清单数量不是 16：{0}' -f $expectedFiles.Count) }
        $expectedFileKeys = @{}
        foreach ($expectedFile in $expectedFiles) {
            if ([string]$expectedFile.Name -match '[\\/]') { throw ('固定 Redis 发行文件名包含路径分隔符：{0}' -f $expectedFile.Name) }
            $target = Join-Path $layout.CodeRoot ([string]$expectedFile.Name)
            $markerFile = @($partialMarker.RedisDistributionFiles | Where-Object { [string]$_.Name -ceq [string]$expectedFile.Name }) | Select-Object -First 1
            if ($null -eq $markerFile -or [string]$markerFile.Sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw ('管理记录缺少固定 Redis 文件哈希：{0}' -f $expectedFile.Name) }
            $expectedFileKeys[$target.ToLowerInvariant()] = [pscustomobject]@{ Name = [string]$expectedFile.Name; Path = $target; EntryPath = [string]$expectedFile.EntryPath; Sha256 = [string]$markerFile.Sha256; Kind = 'Redis'; Required = $true }
        }
        foreach ($artifact in @(
            [pscustomobject]@{ Name = [IO.Path]::GetFileName($layout.WrapperExecutable); Path = $layout.WrapperExecutable; Sha256 = [string]$partialMarker.WrapperExecutableSha256; Kind = 'Wrapper'; Required = $true },
            [pscustomobject]@{ Name = [IO.Path]::GetFileName($layout.WrapperXml); Path = $layout.WrapperXml; Sha256 = [string]$partialMarker.WrapperXmlSha256; Kind = 'WrapperXml'; Required = $true },
            [pscustomobject]@{ Name = [IO.Path]::GetFileName($layout.RedisConfig); Path = $layout.RedisConfig; Sha256 = [string]$partialMarker.RedisConfigSha256; Kind = 'RedisConfig'; Required = $true },
            [pscustomobject]@{ Name = [IO.Path]::GetFileName($layout.SecretFile); Path = $layout.SecretFile; Sha256 = [string]$partialMarker.SecretFileSha256; Kind = 'Secret'; Required = $true },
            [pscustomobject]@{ Name = [IO.Path]::GetFileName($layout.MarkerFile); Path = $layout.MarkerFile; Sha256 = $null; Kind = 'Marker'; Required = $true },
            [pscustomobject]@{ Name = [IO.Path]::GetFileName($layout.WrapperLog); Path = $layout.WrapperLog; Sha256 = $null; Kind = 'WrapperLog'; Required = $false },
            [pscustomobject]@{ Name = [IO.Path]::GetFileName($layout.RedisLog); Path = $layout.RedisLog; Sha256 = $null; Kind = 'RedisLog'; Required = $false }
        )) { $expectedFileKeys[$artifact.Path.ToLowerInvariant()] = $artifact }
        $zipHashes = @{}
        $zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
        try {
            $hashAlgorithm = [Security.Cryptography.SHA256]::Create()
            try {
                foreach ($expectedFile in $expectedFiles) {
                    $entry = $zip.GetEntry([string]$expectedFile.EntryPath)
                    if ($null -eq $entry) { throw ('固定 Redis ZIP 缺少条目：{0}' -f $expectedFile.EntryPath) }
                    $stream = $entry.Open()
                    try { $zipHashes[[string]$expectedFile.Name] = ([BitConverter]::ToString($hashAlgorithm.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() } finally { $stream.Dispose() }
                }
            } finally { $hashAlgorithm.Dispose() }
        } finally { $zip.Dispose() }
        $evidence = New-Object System.Collections.Generic.List[object]
        $observedDirectories = New-Object System.Collections.Generic.List[string]
        $observedFiles = New-Object System.Collections.Generic.List[object]
        foreach ($root in $roots) {
            if (-not [IO.Directory]::Exists($root)) { throw ('部分安装根目录不存在，拒绝恢复：{0}' -f $root) }
            $item = Get-Item -LiteralPath $root -Force -ErrorAction Stop
            if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw ('部分安装根目录不是普通目录：{0}' -f $root) }
            if ($item.CreationTimeUtc -lt $priorStartedUtc.AddSeconds(-5) -or $item.CreationTimeUtc -gt $priorCompletedUtc.AddSeconds(5)) { throw ('部分安装根目录创建时间无法与上一次失败 INSTALL 关联，拒绝删除：{0}；RootCreatedUtc={1:o}；PriorStartedUtc={2:o}；PriorCompletedUtc={3:o}' -f $root, $item.CreationTimeUtc, $priorStartedUtc, $priorCompletedUtc) }
            $observedDirectories.Add([IO.Path]::GetFullPath($root).TrimEnd('\'))
            $children = @(Get-ChildItem -LiteralPath $root -Force -Recurse -ErrorAction Stop)
            foreach ($child in $children) {
                $childPath = [IO.Path]::GetFullPath($child.FullName).TrimEnd('\')
                if ($child.PSIsContainer) {
                    $observedDirectories.Add($childPath)
                    if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw ('部分安装目录包含重解析点，拒绝删除：{0}' -f $childPath) }
                    $createdUtc = $child.CreationTimeUtc
                    if ($createdUtc -lt $priorStartedUtc.AddSeconds(-5) -or $createdUtc -gt $priorCompletedUtc.AddSeconds(5)) { throw ('部分安装目录创建时间无法与上一次失败 INSTALL 关联，拒绝删除：{0}' -f $childPath) }
                } else {
                    if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw ('部分安装文件是重解析点，拒绝删除：{0}' -f $childPath) }
                    $observedFiles.Add($child)
                }
            }
        }
        foreach ($observedDirectory in $observedDirectories) {
            $key = $observedDirectory.ToLowerInvariant()
            if (-not $expectedDirectoryKeys.ContainsKey($key)) { throw ('部分安装目录包含未声明目录，拒绝删除：{0}' -f $observedDirectory) }
        }
        foreach ($expectedDirectory in $expectedDirectories) {
            if (-not [IO.Directory]::Exists($expectedDirectory)) { throw ('部分安装目录树缺少预期目录，拒绝删除：{0}' -f $expectedDirectory) }
            $evidence.Add((Get-SafeDirectoryEvidence -Path $expectedDirectory))
        }
        if ($observedFiles.Count -gt $expectedFileKeys.Count) { throw ('部分安装文件数量超过受控安装文件清单：实际 {0}，上限 {1}' -f $observedFiles.Count, $expectedFileKeys.Count) }
        $fileEvidence = New-Object System.Collections.Generic.List[object]
        foreach ($file in $observedFiles) {
            $filePath = [IO.Path]::GetFullPath($file.FullName)
            $key = $filePath.ToLowerInvariant()
            if (-not $expectedFileKeys.ContainsKey($key)) { throw ('部分安装目录包含未声明文件，拒绝删除：{0}' -f $filePath) }
            $expected = $expectedFileKeys[$key]
            $createdUtc = $file.CreationTimeUtc
            if ($createdUtc -lt $priorStartedUtc.AddSeconds(-5) -or $createdUtc -gt $priorCompletedUtc.AddSeconds(5)) { throw ('部分安装文件创建时间无法与上一次失败 INSTALL 关联，拒绝删除：{0}' -f $filePath) }
            $actualHash = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
            $expectedHash = if ([string]$expected.Kind -ceq 'Redis') { [string]$zipHashes[$expected.Name] } else { [string]$expected.Sha256 }
            if (-not [string]::IsNullOrWhiteSpace($expectedHash) -and $actualHash -cne $expectedHash) { throw ('部分安装文件 SHA-256 与受控安装记录不匹配，拒绝删除：{0}' -f $filePath) }
            $entryPath = if ($expected.PSObject.Properties.Name -contains 'EntryPath') { [string]$expected.EntryPath } else { $null }
            $fileEvidence.Add([pscustomobject]@{ Path = $filePath; Name = $expected.Name; EntryPath = $entryPath; Kind = [string]$expected.Kind; Length = [int64]$file.Length; Sha256 = $actualHash; CreationTimeUtc = $createdUtc })
        }
        foreach ($expected in $expectedFileKeys.Values) {
            if ($expected.Required -and -not [IO.File]::Exists($expected.Path)) { throw ('部分安装目录树缺少受控安装文件，拒绝删除：{0}' -f $expected.Path) }
        }
        foreach ($file in @($observedFiles | Sort-Object FullName -Descending)) {
            [IO.File]::Delete($file.FullName)
            if ([IO.File]::Exists($file.FullName)) { throw ('部分安装文件删除后仍存在：{0}' -f $file.FullName) }
        }
        $deleteDirectories = @($expectedDirectories | Sort-Object Length -Descending)
        foreach ($directory in $deleteDirectories) {
            [IO.Directory]::Delete($directory, $false)
            if ([IO.Directory]::Exists($directory)) { throw ('部分安装目录删除后仍存在：{0}' -f $directory) }
        }
        Add-Action -Id 'RECOVER_PARTIAL_INSTALL' -Status PASS -Detail '仅删除上一次本产品失败 INSTALL 创建的、通过固定 16 文件路径/哈希/时间窗口校验的专用部分安装树；未触及公共父目录、其他文件、服务、进程或 Redis 数据。' -Evidence ([pscustomobject]@{ PriorReport = $priorReportPath; Directories = $evidence.ToArray(); Files = $fileEvidence.ToArray(); DeletedRoots = $roots })
    } catch {
        Add-Action -Id 'RECOVER_PARTIAL_INSTALL' -Status BLOCKED -Detail ('部分安装恢复未执行或未完成；现场保留：{0}' -f $_.Exception.Message)
        throw
    }
}

function Invoke-Preflight {
    $safe = $true
    $redisSnapshots = @()
    $redisAuditError = $null
    try {
        $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
        $isWindows10 = ([string]$os.Caption -match 'Windows 10')
        $is64 = [Environment]::Is64BitOperatingSystem
        if ($isWindows10 -and $is64) {
            Add-Check -Id 'HOST_PLATFORM' -Status PASS -Detail 'Windows 10 x64 主机。' -Evidence ([pscustomobject]@{ Caption = $os.Caption; Version = $os.Version; Build = $os.BuildNumber; PowerShell = $PSVersionTable.PSVersion.ToString() })
        } else {
            Add-Check -Id 'HOST_PLATFORM' -Status BLOCKED -Detail '当前主机不是授权的 Windows 10 x64 环境。' -Evidence ([pscustomobject]@{ Caption = $os.Caption; Version = $os.Version; Is64Bit = $is64 })
            $safe = $false
        }
    } catch {
        Add-Check -Id 'HOST_PLATFORM' -Status BLOCKED -Detail ('无法读取主机平台：{0}' -f $_.Exception.Message)
        $safe = $false
    }

    $isAdmin = Test-IsAdministrator
    if ($isAdmin) {
        Add-Check -Id 'ADMIN_TOKEN' -Status PASS -Detail '当前 PowerShell 具有管理员令牌。'
    } else {
        Add-Check -Id 'ADMIN_TOKEN' -Status BLOCKED -Detail '未取得管理员令牌；EXECUTE 模式必须由用户批准 UAC 后重新运行。'
        $safe = $false
    }

    $allProcesses = @()
    $allServices = @()
    try {
        $allProcesses = @(Get-CimInstance Win32_Process -ErrorAction Stop)
        $allServices = @(Get-CimInstance Win32_Service -ErrorAction Stop)
        Add-Check -Id 'PROCESS_SCM_READ' -Status PASS -Detail '进程和 SCM 只读查询可用。' -Evidence ([pscustomobject]@{ ProcessCount = $allProcesses.Count; ServiceCount = $allServices.Count })
    } catch {
        Add-Check -Id 'PROCESS_SCM_READ' -Status BLOCKED -Detail ('无法完成进程/SCM 只读查询：{0}' -f $_.Exception.Message)
        $safe = $false
    }

    if ($allServices.Count -gt 0) {
        $targetServices = @($allServices | Where-Object { $_.Name -eq $script:ServiceName })
        if ($targetServices.Count -eq 0) {
            Add-Check -Id 'TARGET_SERVICE_ABSENT' -Status PASS -Detail ('目标服务 {0} 不存在。' -f $script:ServiceName)
        } else {
            Add-Check -Id 'TARGET_SERVICE_ABSENT' -Status BLOCKED -Detail '目标服务名已被占用；脚本不会接管、覆盖或卸载已有服务。' -Evidence ($targetServices | Select-Object Name,State,ProcessId,PathName)
            $safe = $false
        }
        $foreignServices = @($allServices | Where-Object { $_.Name -like 'WangbinCollector-R2A2-*' -or $_.Name -like 'REISUN-*' })
        $r2a2Names = @($foreignServices | Where-Object { $_.Name -like 'WangbinCollector-R2A2-*' } | Select-Object -ExpandProperty Name)
        $reisunServices = @($foreignServices | Where-Object { $_.Name -like 'REISUN-*' })
        try {
            if ($null -eq (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) { throw 'Get-NetTCPConnection 不可用。' }
            $allListeners = @(Get-NetTCPConnection -ErrorAction Stop | Select-Object OwningProcess,LocalAddress,LocalPort,RemoteAddress,RemotePort,State)
            $layoutForAudit = Get-WangbinCollectorRedisLayout -Port $Port
            $scope = Test-Phase07OperationScope -Layout $layoutForAudit
            if (-not $scope.Safe) {
                Add-Check -Id 'OPERATION_SCOPE' -Status BLOCKED -Detail '阶段 07 受控生命周期存在可能触及公共父目录或删除其他业务数据的代码路径。' -Evidence $scope
                $safe = $false
            } else {
                Add-Check -Id 'OPERATION_SCOPE' -Status PASS -Detail '阶段 07 的 SCM、ACL、文件和目录操作范围限定在 WangbinCollector-Redis 专用资源；公共父目录不设置 ACL、不删除。' -Evidence $scope
            }
            if ($reisunServices.Count -gt 0) {
                Add-Check -Id 'FOREIGN_SERVICE_BASELINE' -Status BLOCKED -Detail '发现 REISUN-* 受保护服务；本轮没有为其建立完整只读隔离基线，拒绝继续。' -Evidence ($reisunServices | Select-Object Name,State,ProcessId,PathName)
                $safe = $false
            } elseif ($r2a2Names.Count -eq 0) {
                Add-Check -Id 'FOREIGN_SERVICE_BASELINE' -Status PASS -Detail '未发现 R2-A2 或 REISUN 服务；没有外部业务基线需要接管。'
            } else {
                $audit = Get-ForeignServiceAudit -Names $r2a2Names -AllProcesses $allProcesses -AllServices $allServices -AllListeners $allListeners -Layout $layoutForAudit
                if ($audit.Blockers.Count -eq 0 -and $audit.Audits.Count -eq $r2a2Names.Count) {
                    $script:ForeignBaseline = Write-ForeignBaseline -Audit $audit
                    Add-Check -Id 'FOREIGN_SERVICE_BASELINE' -Status PASS -Detail 'R2-A2 服务已完成 SCM 注册身份、服务账号、Service SID、wrapper、进程树、Redis 子进程、端口、配置/数据路径、ACL 和阶段 07 操作范围的只读隔离审计；其存在被记录为受保护业务基线。' -Evidence ([pscustomobject]@{ Services = $audit.Audits; Baseline = $script:ForeignBaseline })
                } else {
                    Add-Check -Id 'FOREIGN_SERVICE_BASELINE' -Status BLOCKED -Detail 'R2-A2 服务存在但无法证明与阶段 07 完全隔离；保持保护，不接管或忽略。' -Evidence ([pscustomobject]@{ Services = $audit.Audits; Blockers = $audit.Blockers })
                    $safe = $false
                }
            }
        } catch {
            Add-Check -Id 'FOREIGN_SERVICE_BASELINE' -Status BLOCKED -Detail ('无法完成 R2-A2/REISUN 只读隔离审计：{0}' -f $_.Exception.Message)
            $safe = $false
        }
    }

    try {
        $redisSnapshots = @(Get-RedisProcessSnapshot -AllProcesses $allProcesses -AllServices $allServices)
    } catch {
        $redisAuditError = $_.Exception.Message
        $safe = $false
    }

    try {
        if ($null -eq (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) { throw 'Get-NetTCPConnection 不可用。' }
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess,State)
        if ($listeners.Count -eq 0) {
            Add-Check -Id 'TEST_PORT_VACANT' -Status PASS -Detail ('测试端口 {0} 当前没有监听者。' -f $Port)
        } else {
            Add-Check -Id 'TEST_PORT_VACANT' -Status BLOCKED -Detail ('测试端口 {0} 已被占用；不会停止或接管监听者。' -f $Port) -Evidence $listeners
            $safe = $false
        }
    } catch {
        Add-Check -Id 'TEST_PORT_VACANT' -Status BLOCKED -Detail ('无法读取测试端口 {0}：{1}' -f $Port, $_.Exception.Message)
        $safe = $false
    }

    $layout = $null
    $targetPathsProvenSafe = $false
    try {
        $layout = Get-WangbinCollectorRedisLayout -Port $Port
        foreach ($parentPath in @($layout.ProductCodeParentRoot, $layout.ProductDataParentRoot)) {
            & $script:Module { param($p) Assert-NoReparsePoint $p } $parentPath
        }
        $parentEvidence = @(
            Get-SafeDirectoryEvidence -Path $layout.ProductCodeParentRoot
            Get-SafeDirectoryEvidence -Path $layout.ProductDataParentRoot
        )
        if (@($parentEvidence | Where-Object { -not $_.Exists }).Count -gt 0) {
            Add-Check -Id 'PUBLIC_PARENTS_SAFE' -Status PASS -Detail '公共父目录不存在；EXECUTE 仅按受控安装流程创建缺失父目录，不修改任何既有公共目录或 ACL。' -Evidence $parentEvidence
        } else {
            Add-Check -Id 'PUBLIC_PARENTS_SAFE' -Status PASS -Detail '公共父目录存在且不是重解析点；验收流程只读取其 ACL，不会写入或替换公共父目录。' -Evidence $parentEvidence
        }
        $targetEvidence = @(
            Get-SafeDirectoryEvidence -Path $layout.ProductCodeRoot
            Get-SafeDirectoryEvidence -Path $layout.ProductDataRoot
        )
        $occupied = @($targetEvidence | Where-Object { $_.Exists })
        if ($occupied.Count -eq 0) {
            $targetPathsProvenSafe = $true
            Add-Check -Id 'DEDICATED_PATHS_FREE' -Status PASS -Detail '独立 Redis 子目录不存在；公共 WangbinCollector 父目录不会被接管。' -Evidence $targetEvidence
        } else {
            $ownership = & $script:Module { param($l) Get-ManagedOwnership -Layout $l } $layout
            if ($ownership.Ownership -eq 'MANAGED_REDIS' -and $occupied.Count -eq $targetEvidence.Count) {
                $targetPathsProvenSafe = $true
                Add-Check -Id 'DEDICATED_PATHS_FREE' -Status PASS -Detail '独立 Redis 子目录已存在，但通过本产品 marker、SCM、哈希、ACL 和 Service SID 完整归属校验。' -Evidence ([pscustomobject]@{ Paths = $targetEvidence; Ownership = $ownership.Ownership })
            } else {
                Add-Check -Id 'DEDICATED_PATHS_FREE' -Status BLOCKED -Detail '独立 Redis 子目录已存在但无法完整证明归属；不会接管、覆盖或删除。' -Evidence ([pscustomobject]@{ Paths = $targetEvidence; Ownership = $ownership.Ownership; Diagnostic = $ownership.Diagnostic })
                $safe = $false
            }
        }
    } catch {
        Add-Check -Id 'DEDICATED_PATHS_FREE' -Status BLOCKED -Detail ('无法证明公共父目录或独立 Redis 子目录安全：{0}' -f $_.Exception.Message)
        $safe = $false
    }

    if ($null -ne $redisAuditError) {
        Add-Check -Id 'REDIS_PID_OWNERSHIP' -Status BLOCKED -Detail ('无法完成现有 redis-server.exe 只读身份/监听审计：{0}' -f $redisAuditError)
    } else {
        $unknown = @($redisSnapshots | Where-Object {
            [string]::IsNullOrWhiteSpace([string]$_.ExecutablePath) -or
            -not $_.CommandLinePresent -or
            [string]$_.Owner -eq 'UNKNOWN' -or
            ($_.ParentProcessId -ne 0 -and [string]::IsNullOrWhiteSpace([string]$_.ParentExecutablePath))
        })
        $portConflict = @($redisSnapshots | Where-Object { @($_.Listeners | Where-Object { [int]$_.LocalPort -eq $Port }).Count -gt 0 })
        if ($portConflict.Count -gt 0) {
            Add-Check -Id 'REDIS_PID_OWNERSHIP' -Status BLOCKED -Detail ('现有 Redis 进程占用测试端口 {0}；不会继续。' -f $Port) -Evidence $redisSnapshots
            $safe = $false
        } elseif ($unknown.Count -gt 0 -and -not $targetPathsProvenSafe) {
            Add-Check -Id 'REDIS_PID_OWNERSHIP' -Status BLOCKED -Detail '现有 Redis 身份未知且独立目标子目录未能证明安全隔离；保持保护并停止。' -Evidence $redisSnapshots
            $safe = $false
        } elseif ($unknown.Count -gt 0) {
            Add-Check -Id 'REDIS_PID_OWNERSHIP' -Status PASS -Detail '发现身份未知的现有 Redis，但其未占用 16379，且目标服务、目标 Redis 子目录和目标流程完全隔离；不会读取、停止或接管该 Redis。' -Evidence $redisSnapshots
        } elseif ($redisSnapshots.Count -eq 0) {
            Add-Check -Id 'REDIS_PID_OWNERSHIP' -Status PASS -Detail '未发现现有 redis-server.exe 进程。' -Evidence @()
        } else {
            Add-Check -Id 'REDIS_PID_OWNERSHIP' -Status PASS -Detail '所有现有 redis-server.exe 未占用测试端口，目标操作范围与其隔离。' -Evidence $redisSnapshots
        }
    }

    $redisArchive = Get-ArtifactPath -Name $script:RedisArchiveName
    $winSw = Get-ArtifactPath -Name $script:WinSwName
    $redisLicense = Get-ArtifactPath -Name 'licenses\redis-windows-LICENSE'
    $winSwLicense = Get-ArtifactPath -Name 'licenses\winsw-LICENSE.txt'
    $artifactPaths = @($redisArchive, $winSw, $redisLicense, $winSwLicense)
    $artifactEvidence = New-Object System.Collections.Generic.List[object]
    foreach ($path in $artifactPaths) {
        if (-not (Test-ExistingPathIsSafeFile -Path $path)) {
            Add-Check -Id 'REAL_ARTIFACTS' -Status BLOCKED -Detail ('构件或许可证证据缺失、不可读或为重解析点：{0}' -f $path)
            $safe = $false
        } else {
            $artifactEvidence.Add([pscustomobject]@{ Path = $path; Length = ([IO.FileInfo]$path).Length; Sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() })
        }
    }
    if ($artifactEvidence.Count -eq $artifactPaths.Count) {
        $redisActual = @($artifactEvidence | Where-Object { $_.Path -eq $redisArchive })[0].Sha256
        $winSwActual = @($artifactEvidence | Where-Object { $_.Path -eq $winSw })[0].Sha256
        if ($redisActual -cne $script:ExpectedRedisArchiveSha256) {
            Add-Check -Id 'REAL_ARTIFACTS' -Status BLOCKED -Detail 'Redis ZIP SHA-256 不匹配固定候选。' -Evidence $artifactEvidence
            $safe = $false
        } elseif ($winSwActual -cne $script:ExpectedWinSwSha256) {
            Add-Check -Id 'REAL_ARTIFACTS' -Status BLOCKED -Detail 'WinSW SHA-256 不匹配固定候选。' -Evidence $artifactEvidence
            $safe = $false
        } else {
            Add-Check -Id 'REAL_ARTIFACTS' -Status PASS -Detail 'Redis ZIP、WinSW 和两份许可证证据存在且 SHA-256 已验证。' -Evidence $artifactEvidence
        }
    }

    try {
        if ($null -eq $script:Module) { $script:Module = Import-Module -Name (Join-Path $PSScriptRoot 'WangbinCollectorRedis.psm1') -Force -PassThru }
        $validated = & $script:Module { param($path, $sha) Assert-RedisDistributionArchive -ArchivePath $path -ExpectedSha256 $sha } $redisArchive $script:ExpectedRedisArchiveSha256
        Add-Check -Id 'REAL_ZIP_CONTRACT' -Status PASS -Detail '真实 Redis ZIP 通过现有固定目录、16 文件、PE 架构和运行时族校验。' -Evidence ([pscustomobject]@{ ArchiveSha256 = $validated.ArchiveSha256; Version = $validated.Version; Architecture = $validated.Architecture; RuntimeFamily = $validated.RuntimeFamily; Files = @($validated.Files | Select-Object Name,EntryPath,Length,Sha256,Architecture) })
    } catch {
        Add-Check -Id 'REAL_ZIP_CONTRACT' -Status BLOCKED -Detail ('真实 Redis ZIP 未通过现有发行物契约：{0}' -f $_.Exception.Message)
        $safe = $false
    }

    $protected = @(
        (Join-Path $PSScriptRoot '..\..\collector-boot\src\main\resources\application.yml'),
        (Join-Path $PSScriptRoot '..\..\.hermes.md')
    )
    Add-Check -Id 'PROTECTED_FILES' -Status PASS -Detail '脚本只写验收报告和专用构件证据目录，不写 application.yml 或 .hermes.md。' -Evidence ($protected | ForEach-Object { [pscustomobject]@{ Path = [IO.Path]::GetFullPath($_); Exists = (Test-Path -LiteralPath $_) } })
    return $safe
}

function Invoke-ManagedRedisCommand {
    param(
        [Parameter(Mandatory)][string[]]$Command,
        [Parameter(Mandatory)][int]$ExpectedProcessId
    )
    return & $script:Module {
        param($port, $command, $expectedProcessId)
        $layout = Get-WangbinCollectorRedisLayout -Port $port
        $owned = Assert-OwnedService -Layout $layout
        if ($null -eq $owned.Identity -or -not $owned.Identity.Owned -or [int]$owned.Identity.RedisProcessId -ne $expectedProcessId) { throw 'Redis 命令前归属验证失败。' }
        $password = Get-ManagedPassword -Layout $layout -Marker $owned.Marker
        try {
            Invoke-RedisCommand -TargetHost '127.0.0.1' -Port $port -Password $password -Command $command -TimeoutMilliseconds 5000 -ExpectedProcessId $expectedProcessId
        } finally {
            $password = $null
        }
    } $Port $Command $ExpectedProcessId
}

function Get-ReadyStatus {
    $status = Get-WangbinCollectorRedisStatus -TargetHost '127.0.0.1' -Port $Port
    if (-not $status.Ready -or -not $status.OwnershipVerified -or -not $status.PingSucceeded -or [int]$status.ListenerProcessId -ne [int]$status.RedisProcessId) {
        throw ('受控状态未达到 Ready：{0}' -f (($status | ConvertTo-Json -Depth 8 -Compress)))
    }
    return $status
}

function Invoke-DataProbe {
    param([Parameter(Mandatory)][int]$ExpectedProcessId, [switch]$WriteData)
    if ($WriteData) {
        $nonce = [guid]::NewGuid().ToString('N')
        $cacheKey = 'wangbin:phase07:cache:' + $nonce
        $streamKey = 'wangbin:phase07:stream:' + $nonce
        $value = 'phase07-' + $nonce
        $set = Invoke-ManagedRedisCommand -Command @('SET', $cacheKey, $value, 'NX') -ExpectedProcessId $ExpectedProcessId
        if ([string]$set.Value -cne 'OK') { throw 'Redis Cache SET 未返回 OK。' }
        $xadd = Invoke-ManagedRedisCommand -Command @('XADD', $streamKey, '*', 'phase', '07', 'probe', $nonce) -ExpectedProcessId $ExpectedProcessId
        if ([string]::IsNullOrWhiteSpace([string]$xadd.Value)) { throw 'Redis Stream XADD 未返回 Stream ID。' }
        $get = Invoke-ManagedRedisCommand -Command @('GET', $cacheKey) -ExpectedProcessId $ExpectedProcessId
        if ([string]$get.Value -cne $value) { throw 'Redis Cache GET 回读不匹配。' }
        $range = Invoke-ManagedRedisCommand -Command @('XRANGE', $streamKey, '-', '+') -ExpectedProcessId $ExpectedProcessId
        $rangeText = $range.Value | ConvertTo-Json -Compress -Depth 12
        if ($rangeText -notlike ('*' + $nonce + '*')) { throw 'Redis Stream XRANGE 未回读本次写入。' }
        $script:ProbeKeys = [pscustomobject]@{ CacheKey = $cacheKey; StreamKey = $streamKey; Value = $value; Nonce = $nonce }
        return [pscustomobject]@{ CacheWrite = 'OK'; CacheRead = 'MATCH'; StreamWrite = [string]$xadd.Value; StreamRead = 'MATCH'; Nonce = $nonce }
    }
    if ($null -eq $script:ProbeKeys) { throw '缺少初始 Redis 读写探针数据。' }
    $get = Invoke-ManagedRedisCommand -Command @('GET', $script:ProbeKeys.CacheKey) -ExpectedProcessId $ExpectedProcessId
    if ([string]$get.Value -cne [string]$script:ProbeKeys.Value) { throw '重启后 Redis Cache 数据不匹配。' }
    $range = Invoke-ManagedRedisCommand -Command @('XRANGE', $script:ProbeKeys.StreamKey, '-', '+') -ExpectedProcessId $ExpectedProcessId
    $rangeText = $range.Value | ConvertTo-Json -Compress -Depth 12
    if ($rangeText -notlike ('*' + [string]$script:ProbeKeys.Nonce + '*')) { throw '重启后 Redis Stream 数据未保留。' }
    return [pscustomobject]@{ CacheReadAfterRestart = 'MATCH'; StreamReadAfterRestart = 'MATCH'; Nonce = [string]$script:ProbeKeys.Nonce }
}

function Remove-ProbeData {
    if ($null -eq $script:ProbeKeys) { return }
    try {
        $status = Get-ReadyStatus
        $deleted = Invoke-ManagedRedisCommand -Command @('DEL', [string]$script:ProbeKeys.CacheKey, [string]$script:ProbeKeys.StreamKey) -ExpectedProcessId ([int]$status.RedisProcessId)
        Add-Action -Id 'CLEANUP_PROBE_KEYS' -Status PASS -Detail '仅删除本次脚本生成的精确 Cache/Stream key；未执行 FLUSHALL/FLUSHDB。' -Evidence ([pscustomobject]@{ Deleted = $deleted.Value; CacheKey = $script:ProbeKeys.CacheKey; StreamKey = $script:ProbeKeys.StreamKey })
    } catch {
        Add-Action -Id 'CLEANUP_PROBE_KEYS' -Status BLOCKED -Detail ('无法在归属仍可证明时清理探针 key；保留现场：{0}' -f $_.Exception.Message)
    } finally {
        $script:ProbeKeys = $null
    }
}

function Invoke-RealAcceptance {
    $installSucceeded = $false
    try {
        Confirm-ForeignBaselineBeforeLifecycle
        $install = Install-WangbinCollectorRedis -RedisDistributionArchive (Get-ArtifactPath $script:RedisArchiveName) -ExpectedRedisArchiveSha256 $script:ExpectedRedisArchiveSha256 -WinSwExecutable (Get-ArtifactPath $script:WinSwName) -ExpectedWinSwSha256 $script:ExpectedWinSwSha256 -RedisSourceDescription $script:SourceDescription -WinSwSourceDescription $script:WinSwSourceDescription -RedisLicenseFile (Get-ArtifactPath 'licenses\redis-windows-LICENSE') -WinSwLicenseFile (Get-ArtifactPath 'licenses\winsw-LICENSE.txt') -Port $Port
        $installSucceeded = $true
        Add-Action -Id 'INSTALL' -Status PASS -Detail '通过现有 Install-WangbinCollectorRedis 完成安装并写入受控归属记录。' -Evidence $install
        Confirm-ForeignBaselineBeforeLifecycle

        $start = Start-WangbinCollectorRedis -TimeoutSeconds 120 -Port $Port
        Add-Action -Id 'START' -Status PASS -Detail '通过现有 Start-WangbinCollectorRedis 启动并确认 wrapper/Redis/端口/PING 归属。' -Evidence $start
        Confirm-ForeignBaselineBeforeLifecycle
        $status = Get-ReadyStatus
        Add-Action -Id 'PING_AND_OWNERSHIP' -Status PASS -Detail '认证 PING、loopback 监听、Redis PID 和受控归属均通过。' -Evidence ($status | Select-Object ServiceName,ScmState,ServiceProcessId,RedisProcessId,ListenerProcessId,Port,PingSucceeded,Ready,OwnershipVerified)

        $writeProbe = Invoke-DataProbe -ExpectedProcessId ([int]$status.RedisProcessId) -WriteData
        Add-Action -Id 'CACHE_STREAM_WRITE_READ' -Status PASS -Detail '使用受控 RESP 边界完成本次 Cache SET/GET 与 Stream XADD/XRANGE。' -Evidence $writeProbe

        $stop = Stop-WangbinCollectorRedis -TimeoutSeconds 120 -Port $Port
        if (-not $stop.Stopped) { throw 'STOP 未返回 Stopped=true。' }
        Add-Action -Id 'STOP' -Status PASS -Detail '通过现有 Stop-WangbinCollectorRedis 完成 SHUTDOWN SAVE、SCM 停止和端口释放确认。' -Evidence $stop
        Confirm-ForeignBaselineBeforeLifecycle

        $restart = Restart-WangbinCollectorRedis -TimeoutSeconds 120 -Port $Port
        Add-Action -Id 'RESTART' -Status PASS -Detail '通过现有 Restart-WangbinCollectorRedis 完成受控 STOP → START。' -Evidence $restart
        Confirm-ForeignBaselineBeforeLifecycle
        $statusAfterRestart = Get-ReadyStatus
        $persisted = Invoke-DataProbe -ExpectedProcessId ([int]$statusAfterRestart.RedisProcessId)
        Add-Action -Id 'PERSISTENCE_AFTER_RESTART' -Status PASS -Detail '重启后 Cache 和 Stream 数据均按精确 key 回读匹配。' -Evidence $persisted
        Remove-ProbeData

        $uninstall = Uninstall-WangbinCollectorRedis -TimeoutSeconds 120 -Port $Port
        if (-not $uninstall.Uninstalled) { throw 'UNINSTALL 未返回 Uninstalled=true。' }
        Add-Action -Id 'UNINSTALL' -Status PASS -Detail '通过现有 Uninstall-WangbinCollectorRedis 注销本产品服务并保留代码、配置、数据和秘密。' -Evidence $uninstall
        Confirm-ForeignBaselineBeforeLifecycle
        $final = Get-WangbinCollectorRedisStatus -Port $Port
        if ($final.ServiceRegistered -or $final.PortListening -or $final.Mode -eq 'MANAGED_REDIS') { throw 'UNINSTALL 后服务、端口或 managed ownership 仍存在。' }
        Add-Action -Id 'FINAL_CLEANUP_READBACK' -Status PASS -Detail 'UNINSTALL 后只读回读确认目标服务和测试端口已清理。' -Evidence ($final | Select-Object ServiceName,Mode,ServiceRegistered,ScmState,Port,PortListening,PingSucceeded,Ready)
    } catch {
        Add-Action -Id 'ACCEPTANCE_STOP_GATE' -Status BLOCKED -Detail ('真实验收在第一处异常处停止：{0}' -f $_.Exception.Message)
        if ($null -ne $script:ProbeKeys) { Remove-ProbeData }
        if ($installSucceeded) {
            try {
                $status = Get-WangbinCollectorRedisStatus -Port $Port
                if ($status.Mode -eq 'MANAGED_REDIS' -and $status.OwnershipVerified) {
                    $stop = Stop-WangbinCollectorRedis -TimeoutSeconds 120 -Port $Port
                    Add-Action -Id 'FAILURE_CLEANUP_STOP' -Status PASS -Detail '失败后仅通过受控 STOP 清理已证明归属的测试服务。' -Evidence $stop
                    $uninstall = Uninstall-WangbinCollectorRedis -TimeoutSeconds 120 -Port $Port
                    Add-Action -Id 'FAILURE_CLEANUP_UNINSTALL' -Status PASS -Detail '失败后仅通过受控 UNINSTALL 清理已证明归属的测试服务。' -Evidence $uninstall
                } else {
                    Add-Action -Id 'FAILURE_CLEANUP' -Status BLOCKED -Detail '失败后无法证明服务归属；未执行任何强制清理，现场保留。'
                }
            } catch {
                Add-Action -Id 'FAILURE_CLEANUP' -Status BLOCKED -Detail ('失败后受控清理未完成，现场保留：{0}' -f $_.Exception.Message)
            }
        }
    }
}

$script:StartedUtc = [datetime]::UtcNow.ToString('o')
$script:ReportPath = Get-ReportPathValue

if ($Mode -eq 'EXECUTE' -and [string]$AuthorizationPhrase -cne $script:ExpectedAuthorizationPhrase) {
    Add-Check -Id 'CURRENT_HOST_AUTHORIZATION' -Status BLOCKED -Detail ('EXECUTE 必须显式提供精确授权短语：{0}' -f $script:ExpectedAuthorizationPhrase)
    Write-Report -ExitCode 2
    exit 2
}

if ($Mode -eq 'EXECUTE' -and -not (Test-IsAdministrator) -and -not $ElevatedChild) {
    $recoveryArgument = if ($RecoverPartial) { ' -RecoverPartial' } else { '' }
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Mode EXECUTE -Port {1} -EvidenceRoot "{2}" -ReportPath "{3}" -AuthorizationPhrase "{4}" -ElevatedChild{5}' -f $PSCommandPath, $Port, [IO.Path]::GetFullPath($EvidenceRoot), $script:ReportPath, $AuthorizationPhrase, $recoveryArgument
    try {
        $child = Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $arguments -Wait -PassThru
        exit $child.ExitCode
    } catch {
        Add-Check -Id 'UAC_ELEVATION' -Status BLOCKED -Detail ('用户未批准或无法启动 UAC 管理员 PowerShell：{0}' -f $_.Exception.Message)
        Write-Report -ExitCode 2
        exit 2
    }
}

try {
    $script:Module = Import-Module -Name (Join-Path $PSScriptRoot 'WangbinCollectorRedis.psm1') -Force -PassThru
    Invoke-AuthorizedPartialInstallRecovery
    $preflightSafe = Invoke-Preflight
    if (-not $preflightSafe) {
        Write-Report -ExitCode 2
        exit 2
    }
    if ($Mode -eq 'PRECHECK') {
        Write-Report -ExitCode 0
        exit 0
    }
    Invoke-RealAcceptance
    $actionFailure = @($script:Actions | Where-Object { $_.Status -in @('FAIL', 'BLOCKED') }).Count -gt 0
    if ($actionFailure) {
        Write-Report -ExitCode 1
        exit 1
    }
    Write-Report -ExitCode 0
    exit 0
} catch {
    Add-Check -Id 'RUNNER' -Status BLOCKED -Detail ('验收脚本自身异常；未继续副作用操作：{0}' -f $_.Exception.Message)
    Write-Report -ExitCode 3
    exit 3
}
