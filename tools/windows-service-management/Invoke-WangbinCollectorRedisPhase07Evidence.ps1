#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('AUDIT', 'CRASH_RECOVERY')][string]$Mode = 'AUDIT',
    [ValidateRange(1, 65535)][int]$Port = 16379,
    [string]$EvidenceRoot = (Join-Path $env:LOCALAPPDATA 'hermes\cache\scratch\wangbin-redis-phase07-real-20261103'),
    [string]$ReportPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ServiceName = 'WangbinCollector-Redis'
$script:ProtectedServiceNames = @('WangbinCollector-R2A2-Redis', 'WangbinCollector-R2A2-Collector')
$script:ModulePath = Join-Path $PSScriptRoot 'WangbinCollectorRedis.psm1'
$script:Module = $null
$script:StartedUtc = [datetime]::UtcNow.ToString('o')
$script:Mutations = New-Object System.Collections.Generic.List[string]
$script:DiagnosticStage = 'IMPORT_MODULE'
$script:DiagnosticRole = $null
$script:FailedPathRoles = @()
$script:DiagnosticErrorType = $null
$script:DiagnosticLine = $null

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-ReportPath {
    if (-not [string]::IsNullOrWhiteSpace($ReportPath)) { return [IO.Path]::GetFullPath($ReportPath) }
    $name = 'phase07-{0}-{1}.json' -f $Mode.ToLowerInvariant(), [datetime]::Now.ToString('yyyyMMdd-HHmmss-fff')
    return [IO.Path]::Combine([IO.Path]::GetFullPath($EvidenceRoot), $name)
}

function Get-Sha256Text {
    param([Parameter(Mandatory)][string]$Text)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-', '').ToLowerInvariant() } finally { $hash.Dispose() }
}

function Get-ServiceDaclEvidence {
    param([Parameter(Mandatory)][string]$Name)
    $raw = @(& sc.exe sdshow $Name 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'SCM DACL sdshow 失败。' }
    $lines = @($raw | Where-Object { [string]$_ -match '^\s*D:' } | ForEach-Object { ([string]$_).Trim() })
    if ($lines.Count -ne 1) { throw ('无法唯一读取服务 {0} 的 SCM DACL。' -f $Name) }
    $sddl = $lines[0]
    $descriptor = New-Object Security.AccessControl.RawSecurityDescriptor($sddl)
    $allowed = New-Object System.Collections.Generic.List[string]
    foreach ($ace in $descriptor.DiscretionaryAcl) {
        if ($ace.AceType -ne [Security.AccessControl.AceType]::AccessAllowed -or
            $ace.SecurityIdentifier.Value -notin @('S-1-5-18', 'S-1-5-32-544') -or
            (($ace.AccessMask -band 0xF01FF) -ne 0xF01FF -and ($ace.AccessMask -band 0x10000000) -eq 0)) {
            throw ('服务 DACL 存在非最小权限 ACE：{0}' -f $ace.SecurityIdentifier.Value)
        }
        $allowed.Add($ace.SecurityIdentifier.Value)
    }
    if ($allowed.Count -ne 2 -or $allowed -notcontains 'S-1-5-18' -or $allowed -notcontains 'S-1-5-32-544') {
        throw '服务 DACL 未严格限定为 SYSTEM 和 Administrators。'
    }
    return [pscustomobject][ordered]@{
        Sddl = $sddl
        SddlSha256 = Get-Sha256Text -Text $sddl
        Protected = (($descriptor.ControlFlags -band [Security.AccessControl.ControlFlags]::DiscretionaryAclProtected) -ne 0)
        AllowedControlSids = @($allowed.ToArray())
        MinimumPermission = 'PASS'
    }
}

function Get-PathSecurityEvidence {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Role,
        [Parameter(Mandatory)][string]$ExpectedMode,
        [Parameter(Mandatory)][string]$ServiceSid,
        [Parameter(Mandatory)][bool]$Directory,
        [switch]$GeneratedFile
    )
    $full = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $full)) { throw ('关键路径不存在：{0}' -f $full) }
    $item = Get-Item -LiteralPath $full -Force -ErrorAction Stop
    if ([bool]$item.PSIsContainer -ne $Directory) { throw ('关键路径类型不匹配：{0}' -f $full) }
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw ('关键路径是重解析点：{0}' -f $full) }
    $acl = Get-Acl -LiteralPath $full -ErrorAction Stop
    $sddl = [string]$acl.Sddl
    $descriptor = New-Object Security.AccessControl.RawSecurityDescriptor($sddl)
    $aces = @($descriptor.DiscretionaryAcl | ForEach-Object {
        [pscustomobject]@{ Type = [string]$_.AceType; Sid = [string]$_.SecurityIdentifier.Value; AccessMask = [int]$_.AccessMask; Flags = [string]$_.AceFlags }
    })
    $result = [ordered]@{
        Path = $full
        Role = $Role
        Exists = $true
        IsDirectory = [bool]$item.PSIsContainer
        Owner = '[REDACTED]'
        AccessRulesProtected = [bool]$acl.AreAccessRulesProtected
        AclSha256 = Get-Sha256Text -Text $sddl
        DaclAces = $aces
        ExpectedMode = $ExpectedMode
        MinimumPermission = 'FAIL'
    }
    try {
        if ($GeneratedFile) {
            # Redis/WinSW 运行时文件继承受保护的 Write 目录，或继承已核验的服务账号生成子目录。
            $parent = Get-Acl -LiteralPath ([IO.Path]::GetDirectoryName($full)) -ErrorAction Stop
            $ownerSid = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
            $parentOwner = $parent.GetOwner([Security.Principal.SecurityIdentifier]).Value
            $owners = @('S-1-5-18', $ServiceSid)
            if ($full -ieq $script:WrapperLogPath) { $owners += 'S-1-5-32-544' }
            if ((-not $parent.AreAccessRulesProtected -and $parentOwner -notin @('S-1-5-18', $ServiceSid)) -or
                $acl.AreAccessRulesProtected -or $ownerSid -notin $owners) { throw '运行时文件继承来源或所有者不受控。' }
            $rights = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
            if ($rights.Count -ne 3 -or @($rights | Where-Object { $_.AccessControlType -ne 'Allow' -or -not $_.IsInherited }).Count -ne 0) { throw '运行时文件存在额外、拒绝或非继承 ACE。' }
            $serviceMask = [long]([Security.AccessControl.FileSystemRights]::Modify -bor [Security.AccessControl.FileSystemRights]::Synchronize)
            $expected = @{
                'S-1-5-18' = [long][Security.AccessControl.FileSystemRights]::FullControl
                'S-1-5-32-544' = [long][Security.AccessControl.FileSystemRights]::FullControl
                $ServiceSid = $serviceMask
            }
            foreach ($rule in $rights) {
                $sid = [string]$rule.IdentityReference.Value
                $flags = if ($Directory) { [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit' } else { [Security.AccessControl.InheritanceFlags]::None }
                if (-not $expected.ContainsKey($sid) -or [long]$rule.FileSystemRights -ne [long]$expected[$sid] -or $rule.InheritanceFlags -ne $flags) { throw '运行时文件 ACE 不符合 SYSTEM/Administrators/Service SID 最小权限模型。' }
                $expected.Remove($sid)
            }
            if ($expected.Count -ne 0) { throw '运行时文件缺少受控主体。' }
            $result.MinimumPermission = 'PASS'
            $result.AclOrigin = 'INHERITED_FROM_PROTECTED_WRITE_ROOT'
        } else {
            $result.MinimumPermission = & $script:Module {
                param($p, $m, $sid, $isDirectory)
                if ($isDirectory) {
                    Assert-ControlledDirectoryAcl -Path $p -Mode $m -ServiceSid $sid
                } else {
                    Assert-ControlledFileAcl -Path $p -Mode $m -ServiceSid $sid
                }
                'PASS'
            } $full $ExpectedMode $ServiceSid $Directory
        }
    } catch {
        $result.MinimumPermission = 'FAIL'
        $result.PermissionDiagnostic = 'ACL_RULE_MISMATCH'
    }
    return [pscustomobject]$result
}

function Get-ProtectedBaselineReadback {
    if (-not (Test-IsAdministrator)) { throw 'ADMINISTRATOR_REQUIRED: SCM 服务 DACL 可使非管理员 CIM 查询返回空列表，不能据此断言服务不存在。' }
    $services = @(Get-CimInstance Win32_Service -ErrorAction Stop | Where-Object { $script:ProtectedServiceNames -contains [string]$_.Name })
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { [int]$_.LocalPort -eq 26379 } | Select-Object OwningProcess,LocalAddress,LocalPort,State)
    $pid56800 = Get-CimInstance Win32_Process -Filter 'ProcessId=56800' -ErrorAction Stop
    if ($services.Count -ne $script:ProtectedServiceNames.Count -or $null -eq $pid56800 -or
        $listeners.Count -ne 1 -or [int]$listeners[0].OwningProcess -ne 56800) {
        throw 'R2-A2 服务、受保护 PID 56800 或 26379 监听状态与预期不一致，拒绝继续。'
    }
    return [pscustomobject][ordered]@{
        ProtectedServiceNames = @($services | Select-Object -ExpandProperty Name)
        ProtectedServiceStates = @($services | Select-Object Name,State,ProcessId,StartName)
        ProtectedPid56800Present = ($null -ne $pid56800)
        ProtectedPid56800CreationDate = [string]$pid56800.CreationDate
        ProtectedPid56800Executable = ([string]$pid56800.ExecutablePath -replace '(?i)([A-Z]:\\Users\\)[^\\]+', '$1[REDACTED]')
        ProtectedPort26379Listeners = @($listeners)
        Scope = 'READ_ONLY_PROTECTED_BASELINE'
    }
}

function Get-ProcessEvidence {
    param([Parameter(Mandatory)]$Identity, [Parameter(Mandatory)]$Layout)
    $process = Get-CimInstance Win32_Process -Filter ('ProcessId={0}' -f [int]$Identity.RedisProcessId) -ErrorAction Stop
    if ($null -eq $process) { throw '目标 Redis PID 读回失败。' }
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { [int]$_.LocalPort -eq [int]$Layout.Port } | Select-Object LocalAddress,LocalPort,State,OwningProcess)
    $redisHash = & $script:Module { param($path) Get-Sha256 $path } $Layout.RedisExecutable
    $configMatch = & $script:Module {
        param($commandLine, $config)
        Test-CommandLineContainsPath -CommandLine $commandLine -Path $config -AllowRelativeConfig
    } ([string]$process.CommandLine) $Layout.RedisConfig
    if (-not $configMatch) { throw '目标 Redis 命令行未证明使用受控 redis.conf。' }
    if ($listeners.Count -ne 1 -or [int]$listeners[0].OwningProcess -ne [int]$Identity.RedisProcessId -or [string]$listeners[0].LocalAddress -ne '127.0.0.1') {
        throw '目标监听端口未证明归属于目标 Redis PID。'
    }
    return [pscustomobject][ordered]@{
        RedisProcessId = [int]$Identity.RedisProcessId
        ParentProcessId = [int]$process.ParentProcessId
        WrapperProcessId = [int]$Identity.WrapperProcessId
        ExecutablePath = [IO.Path]::GetFullPath([string]$process.ExecutablePath)
        ExecutableSha256 = $redisHash
        CommandLine = '[REDACTED]'
        ConfigurationPath = [IO.Path]::GetFullPath([string]$Layout.RedisConfig)
        ConfigurationPathVerified = $true
        CreationDate = [string]$process.CreationDate
        ListenerRecords = @($listeners)
        ListenerPidMatchesRedis = $true
    }
}

function Get-TargetAudit {
    $script:DiagnosticStage = 'TARGET_LAYOUT_AND_MARKER'
    $layout = & $script:Module { param($port) Get-WangbinCollectorRedisLayout -Port $port } $Port
    $script:WrapperLogPath = [IO.Path]::GetFullPath($layout.WrapperLog)
    $marker = & $script:Module { param($l) Get-ManagedMarker -Layout $l } $layout
    if ($null -eq $marker -or [string]$marker.RegistrationState -cne 'Installed') { throw '目标服务没有 Installed ownership marker。' }
    $service = & $script:Module { param($name) Get-ServiceRecord $name } $layout.ServiceName
    if ($null -eq $service) { throw '目标服务未注册。' }
    $script:DiagnosticStage = 'TARGET_SERVICE_DACL'
    $serviceDacl = Get-ServiceDaclEvidence -Name $layout.ServiceName
    $script:DiagnosticStage = 'TARGET_SERVICE_CONFIGURATION_AND_OWNERSHIP'
    $registry = & $script:Module { param($name) Get-ServiceRegistrySnapshot $name } $layout.ServiceName
    $failure = & $script:Module { param($name) Get-FailureActionSummary $name } $layout.ServiceName
    $serviceSid = & $script:Module { param($name) Get-ServiceSid -Name $name } $layout.ServiceName
    if ([string]$marker.ServiceSid -cne [string]$serviceSid) { throw 'marker Service SID 与系统实际 SID 不一致。' }
    & $script:Module { param($l, $s) Assert-ServiceConfiguration -Layout $l -Service $s } $layout $service
    $owned = & $script:Module { param($l) Assert-OwnedService -Layout $l } $layout
    if (-not $owned.Identity.Owned) { throw '目标服务未通过完整 wrapper/Redis ownership 校验。' }
    $listenerOwned = & $script:Module { param($l, $i) Test-OwnedListener -Layout $l -Identity $i } $layout $owned.Identity
    if (-not $listenerOwned) { throw '目标监听端口不属于受控 Redis 子进程。' }
    $script:DiagnosticStage = 'TARGET_PATH_ENUMERATION'
    $pathSpecs = New-Object System.Collections.Generic.List[object]
    $pathSpecs.Add([pscustomobject]@{ Path = $layout.ProductCodeRoot; Role = 'ProgramFiles Redis root'; Mode = 'Code'; Directory = $true })
    $pathSpecs.Add([pscustomobject]@{ Path = $layout.CodeRoot; Role = 'ProgramFiles bin'; Mode = 'Code'; Directory = $true })
    $pathSpecs.Add([pscustomobject]@{ Path = $layout.ProductDataRoot; Role = 'ProgramData Redis root'; Mode = 'Traverse'; Directory = $true })
    $pathSpecs.Add([pscustomobject]@{ Path = $layout.ConfigRoot; Role = 'ProgramData config'; Mode = 'Read'; Directory = $true })
    $pathSpecs.Add([pscustomobject]@{ Path = $layout.RedisDataRoot; Role = 'ProgramData data'; Mode = 'Write'; Directory = $true })
    $pathSpecs.Add([pscustomobject]@{ Path = $layout.RedisLogRoot; Role = 'ProgramData logs'; Mode = 'Write'; Directory = $true })
    $pathSpecs.Add([pscustomobject]@{ Path = $layout.StateRoot; Role = 'ProgramData state'; Mode = 'Admin'; Directory = $true })
    foreach ($spec in @(
        @($layout.RedisExecutable, 'redis-server.exe', 'Code'),
        @($layout.WrapperExecutable, 'WinSW wrapper', 'Code'),
        @($layout.WrapperXml, 'WinSW XML', 'Code'),
        @($layout.RedisConfig, 'generated redis.conf', 'Read'),
        @($layout.SecretFile, 'managed secret file', 'Admin'),
        @($layout.MarkerFile, 'ownership marker', 'Admin')
    )) { $pathSpecs.Add([pscustomobject]@{ Path = $spec[0]; Role = $spec[1]; Mode = $spec[2]; Directory = $false }) }
    foreach ($file in @($marker.RedisDistributionFiles)) {
        $filePath = Join-Path $layout.CodeRoot ([string]$file.Name)
        if (-not @($pathSpecs | Where-Object { [IO.Path]::GetFullPath($_.Path) -ieq [IO.Path]::GetFullPath($filePath) }).Count) {
            $pathSpecs.Add([pscustomobject]@{ Path = $filePath; Role = ('Redis distribution ' + [string]$file.Name); Mode = 'Code'; Directory = $false })
        }
    }
    foreach ($logEntry in @(Get-ChildItem -LiteralPath $layout.RedisLogRoot -Force -ErrorAction Stop)) {
        if ($logEntry.PSIsContainer -or ($logEntry.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw '运行日志目录出现未知目录或重解析点。' }
        $pathSpecs.Add([pscustomobject]@{ Path = $logEntry.FullName; Role = 'ProgramData service log'; Mode = 'Write'; Directory = $false; GeneratedFile = $true })
    }
    $dataPending = New-Object System.Collections.Generic.List[string]
    $dataPending.Add([IO.Path]::GetFullPath($layout.RedisDataRoot))
    $dataPrefix = $dataPending[0].TrimEnd([char]92, [char]47) + [IO.Path]::DirectorySeparatorChar
    for ($index = 0; $index -lt $dataPending.Count; $index++) {
        foreach ($dataEntry in @(Get-ChildItem -LiteralPath $dataPending[$index] -Force -ErrorAction Stop)) {
            $dataPath = [IO.Path]::GetFullPath($dataEntry.FullName)
            if (-not $dataPath.StartsWith($dataPrefix, [StringComparison]::OrdinalIgnoreCase) -or
                ($dataEntry.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw '数据目录出现路径逃逸或重解析点。' }
            $pathSpecs.Add([pscustomobject]@{ Path = $dataPath; Role = $(if ($dataEntry.PSIsContainer) { 'ProgramData Redis data directory' } else { 'ProgramData Redis data file' }); Mode = 'Write'; Directory = [bool]$dataEntry.PSIsContainer; GeneratedFile = $true })
            if ($dataEntry.PSIsContainer) { $dataPending.Add($dataPath) }
        }
    }
    $pathEvidence = New-Object System.Collections.Generic.List[object]
    $script:DiagnosticStage = 'TARGET_PATH_ACL'
    foreach ($spec in $pathSpecs) {
        $script:DiagnosticRole = [string]$spec.Role
        $generated = $spec.PSObject.Properties.Name -contains 'GeneratedFile' -and [bool]$spec.GeneratedFile
        $pathEvidence.Add((Get-PathSecurityEvidence -Path $spec.Path -Role $spec.Role -ExpectedMode $spec.Mode -ServiceSid $serviceSid -Directory ([bool]$spec.Directory) -GeneratedFile:$generated))
    }
    $script:DiagnosticRole = $null
    $pathFailures = @($pathEvidence | Where-Object { $_.MinimumPermission -ne 'PASS' })
    $script:FailedPathRoles = @($pathFailures | ForEach-Object { [string]$_.Role })
    if ($pathFailures.Count -gt 0) { throw ('存在 {0} 个关键路径 ACL 未通过最小权限校验。' -f $pathFailures.Count) }
    $script:DiagnosticStage = 'TARGET_REDIS_PROCESS'
    $processEvidence = Get-ProcessEvidence -Identity $owned.Identity -Layout $layout
    return [pscustomobject][ordered]@{
        Service = [pscustomobject][ordered]@{
            Name = [string]$service.Name
            State = [string]$service.State
            ProcessId = [int]$service.ProcessId
            PathName = [string]$service.PathName
            StartName = [string]$service.StartName
            ServiceSidType = [int]$registry.ServiceSidType
            ActualServiceSid = [string]$serviceSid
            Start = [int]$registry.Start
            Dependencies = @($registry.DependOnService)
            FailureActions = $failure
            Dacl = $serviceDacl
        }
        Process = $processEvidence
        Paths = @($pathEvidence.ToArray())
        Boundary = [pscustomobject]@{ InstallMarker = 'Installed'; Runtime = 'RUNNING_AND_OWNERSHIP_VERIFIED'; Uninstall = 'NOT_EXECUTED_IN_READ_ONLY_AUDIT' }
        Marker = [pscustomobject]@{ ServiceSid = [string]$marker.ServiceSid; RedisExecutableSha256 = [string]$marker.RedisExecutableSha256; RedisConfigSha256 = [string]$marker.RedisConfigSha256; SecretFileSha256 = [string]$marker.SecretFileSha256 }
    }
}

function Get-CrashPlan {
    return [pscustomobject][ordered]@{
        Status = 'NOT_EXECUTED'
        RequiredAuthorization = 'USER_MUST_AUTHORIZE_CURRENT_TARGET_PID_IN_VISIBLE_ADMIN_WINDOW'
        Mutation = 'Stop-Process -Id ONLY_THE_READBACK_REDIS_PID -Force; never use process name or port.'
        Preconditions = @(
            'Administrator token verified',
            'WangbinCollector-Redis ownership marker and SCM configuration verified',
            'Service SID, service DACL, all target ACLs and FailureActions verified',
            'wrapper parent, Redis executable/hash, redis.conf argument, creation time and listener PID verified',
            'R2-A2 services, PID 56800 and port 26379 read back unchanged',
            'isolated Cache/Stream probe written and read before injection'
        )
        RecoveryAssertions = @(
            'FailureActions observed in the real SCM registry and service state transitions',
            'new Redis PID differs from the injected PID',
            'new Redis PID is the owned child of the verified wrapper',
            'new executable path/hash/configuration argument/creation time/listener PID verified',
            'authenticated PING succeeds',
            'isolated Cache/Stream probe survives recovery',
            'R2-A2 state is byte-for-byte/fingerprint unchanged'
        )
        Cleanup = 'After evidence is written, use existing controlled STOP/UNINSTALL only with separate authorization; no forced cleanup is part of the injection.'
    }
}

function Write-Report {
    param([Parameter(Mandatory)][string]$Status, [AllowNull()]$Target, [AllowNull()]$Protected, [AllowNull()]$ProtectedAfter, [AllowNull()]$Error)
    $report = [ordered]@{
        Schema = 1
        Phase = '07'
        TestRunner = 'Invoke-WangbinCollectorRedisPhase07Evidence.ps1'
        Mode = $Mode
        Status = $Status
        Evidence = 'REAL_WINDOWS_READBACK_ONLY'
        ServiceName = $script:ServiceName
        Port = $Port
        StartedUtc = $script:StartedUtc
        CompletedUtc = [datetime]::UtcNow.ToString('o')
        Target = $Target
        ProtectedBaseline = $Protected
        ProtectedBaselineAfter = $ProtectedAfter
        CrashRecoveryPlan = if ($Mode -eq 'CRASH_RECOVERY') { Get-CrashPlan } else { $null }
        FailureInjection = 'NOT_RUN'
        Mutations = @($script:Mutations.ToArray())
        Diagnostic = if ($Status -eq 'BLOCKED') { [pscustomobject]@{ Stage = $script:DiagnosticStage; Role = $script:DiagnosticRole; FailedPathRoles = @($script:FailedPathRoles); ErrorType = $script:DiagnosticErrorType; Line = $script:DiagnosticLine } } else { $null }
        Error = if ($null -eq $Error) { $null } elseif ([string]$Error -like 'ADMINISTRATOR_REQUIRED:*') { 'ADMINISTRATOR_REQUIRED: SCM baseline query unavailable to this token' } else { 'READBACK_BLOCKED: consult local PowerShell diagnostics; do not export raw exception text' }
    }
    $path = Get-ReportPath
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
    [IO.File]::WriteAllText($path, ($report | ConvertTo-Json -Depth 30), (New-Object Text.UTF8Encoding($true)))
    $report | ConvertTo-Json -Depth 30
}

try {
    $script:Module = Import-Module -Name $script:ModulePath -Force -PassThru
    $script:DiagnosticStage = 'PROTECTED_BASELINE_BEFORE'
    $protected = Get-ProtectedBaselineReadback
    if ($Mode -eq 'CRASH_RECOVERY') {
        $after = Get-ProtectedBaselineReadback
        Write-Report -Status 'PLAN_ONLY' -Target $null -Protected $protected -ProtectedAfter $after -Error $null
        exit 0
    }
    if (-not (Test-IsAdministrator)) { throw 'ACL/SCM 独立证据采集必须在管理员 PowerShell 中执行。' }
    $target = Get-TargetAudit
    $script:DiagnosticStage = 'PROTECTED_BASELINE_AFTER'
    $after = Get-ProtectedBaselineReadback
    if (($protected | ConvertTo-Json -Depth 10 -Compress) -cne ($after | ConvertTo-Json -Depth 10 -Compress)) { throw 'R2-A2 前后只读基线不一致。' }
    Write-Report -Status 'PASS' -Target $target -Protected $protected -ProtectedAfter $after -Error $null
    exit 0
} catch {
    $script:DiagnosticErrorType = $_.Exception.GetType().Name
    $script:DiagnosticLine = [int]$_.InvocationInfo.ScriptLineNumber
    try {
        $protected = Get-ProtectedBaselineReadback
    } catch { $protected = [pscustomobject]@{ Readback = 'BLOCKED' } }
    Write-Report -Status 'BLOCKED' -Target $null -Protected $protected -ProtectedAfter $null -Error $_.Exception.Message
    exit 2
}
