#requires -Version 5.1
[CmdletBinding()]
param([ValidateSet('PRECHECK', 'EXECUTE', 'RESUME')][string]$Mode = 'PRECHECK')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ServiceName = 'WangbinCollector-Redis'
$script:Port = 16379
$script:EvidenceRoot = Join-Path $env:LOCALAPPDATA 'hermes\cache\scratch\wangbin-redis-phase07-real-20261103'
$script:HistoricalPath = Join-Path $script:EvidenceRoot 'r2a2-baseline-20261105-091118-274.json'
$script:HistoricalFingerprint = '652fb2736bb9578d415b9b3fe9f6b3b7e94b763b3c1e512d9c20ac463c6294c2'
$script:ReportPath = Join-Path $script:EvidenceRoot ('phase07-final-gates-{0}-{1}.json' -f $Mode.ToLowerInvariant(), [datetime]::Now.ToString('yyyyMMdd-HHmmss-fff'))
$script:Checks = New-Object System.Collections.Generic.List[object]
$script:Actions = New-Object System.Collections.Generic.List[object]
$script:Module = $null
$script:Probe = $null
$script:FaultInjected = $false
$script:FaultAttempted = $false
$script:Test07_02 = 'NOT_EXECUTED'
$script:Test07_06 = 'NOT_EXECUTED'
$script:AdministratorVerified = $false

function Assert-Condition {
    param([bool]$Condition, [string]$Reason)
    if (-not $Condition) { throw $Reason }
}

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    Assert-Condition $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator) 'ADMINISTRATOR_REQUIRED: 拒绝使用非管理员令牌解释 SCM 空结果。'
    $script:AdministratorVerified = $true
}

function Add-Record {
    param([string]$Id, [string]$Status, $Evidence)
    $script:Actions.Add([pscustomobject]@{ Id = $Id; Status = $Status; Evidence = $Evidence; ObservedUtc = [datetime]::UtcNow.ToString('o') })
}

function Save-Report {
    param([string]$Status, [string]$Reason)
    $report = [ordered]@{
        Schema = 1
        Mode = $Mode
        Status = $Status
        Host = $env:COMPUTERNAME
        Administrator = $script:AdministratorVerified
        Test07_02 = $script:Test07_02
        Test07_06 = $script:Test07_06
        FaultInjected = $script:FaultInjected
        FaultAttempted = $script:FaultAttempted
        HistoricalFingerprint = $script:HistoricalFingerprint
        Checks = @($script:Checks.ToArray())
        Actions = @($script:Actions.ToArray())
        Reason = $Reason
        CompletedUtc = [datetime]::UtcNow.ToString('o')
    }
    [IO.Directory]::CreateDirectory($script:EvidenceRoot) | Out-Null
    [IO.File]::WriteAllText($script:ReportPath, ($report | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding($true)))
    [pscustomobject]@{ Status = $Status; Test07_02 = $script:Test07_02; Test07_06 = $script:Test07_06; ReportPath = $script:ReportPath; Reason = $Reason }
}

function Get-HistoricalBaseline {
    Assert-Condition ([IO.File]::Exists($script:HistoricalPath)) '缺少固定历史 R2-A2 保护基线。'
    $baseline = Get-Content -LiteralPath $script:HistoricalPath -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    Assert-Condition ([string]$baseline.Fingerprint -ceq $script:HistoricalFingerprint) '历史 R2-A2 指纹不匹配；拒绝覆盖原基线。'
    $names = @($baseline.Audits | Select-Object -ExpandProperty ServiceName | Sort-Object)
    Assert-Condition ($names.Count -eq 2 -and ($names -join ',') -ceq 'WangbinCollector-R2A2-Collector,WangbinCollector-R2A2-Redis') '历史基线服务身份不完整。'
    Assert-Condition (@($baseline.Audits | ForEach-Object { $_.PathSecurity }).Count -eq 9) '历史基线关键文件 ACL 指纹数量不是九项。'
    return $baseline
}

function Assert-HistoricalBaseline {
    param($Historical, $Current)
    Assert-Condition ([string]$Current.Fingerprint -ceq $script:HistoricalFingerprint) 'R2-A2 完整保护指纹与原始基线不一致。'
    $oldPaths = @($Historical.Audits | ForEach-Object { $_.PathSecurity } | Sort-Object Path)
    $newPaths = @($Current.Audits | ForEach-Object { $_.PathSecurity } | Sort-Object Path)
    Assert-Condition ($newPaths.Count -eq 9 -and $oldPaths.Count -eq $newPaths.Count) 'R2-A2 关键 ACL 路径清单发生变化。'
    for ($i = 0; $i -lt $oldPaths.Count; $i++) {
        Assert-Condition ($oldPaths[$i].Path -ieq $newPaths[$i].Path -and [string]$oldPaths[$i].AclSha256 -ceq [string]$newPaths[$i].AclSha256 -and [string]$oldPaths[$i].Owner -ceq [string]$newPaths[$i].Owner) ('R2-A2 关键 ACL 不一致：{0}' -f $oldPaths[$i].Path)
    }
    return [pscustomobject]@{ Fingerprint = $Current.Fingerprint; Services = @($Current.Audits | Select-Object ServiceName,State,ProcessId); AclFingerprintsMatched = 9 }
}

function Invoke-ExistingPrecheck {
    param([string]$Label, $Historical, [bool]$TargetMustBeAbsent, [bool]$TargetRunning = $false, [switch]$AfterUninstall)
    Assert-Administrator
    $scriptPath = Join-Path $PSScriptRoot 'Invoke-WangbinCollectorRedisPhase07RealAcceptance.ps1'
    $reportPath = Join-Path $script:EvidenceRoot ('phase07-final-precheck-{0}-{1}.json' -f $Label, [guid]::NewGuid().ToString('N'))
    $outPath = Join-Path $script:EvidenceRoot ('phase07-final-precheck-{0}-stdout.txt' -f $Label)
    $errorPath = Join-Path $script:EvidenceRoot ('phase07-final-precheck-{0}-stderr.txt' -f $Label)
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Mode PRECHECK -Port {1} -EvidenceRoot "{2}" -ReportPath "{3}"' -f $scriptPath, $script:Port, $script:EvidenceRoot, $reportPath
    $child = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Wait -PassThru -RedirectStandardOutput $outPath -RedirectStandardError $errorPath -ErrorAction Stop
    Assert-Condition ([IO.File]::Exists($reportPath)) ('管理员 PRECHECK 未写入回执；子进程退出码 {0}。' -f $child.ExitCode)
    $precheck = Get-Content -LiteralPath $reportPath -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    Assert-Condition ([bool]$precheck.Administrator -and [string]$precheck.Host -ceq $env:COMPUTERNAME -and [string]$precheck.Mode -ceq 'PRECHECK') 'PRECHECK 管理员身份、主机或模式不符。'
    $foreign = @($precheck.Checks | Where-Object { $_.Id -eq 'FOREIGN_SERVICE_BASELINE' })
    Assert-Condition ($foreign.Count -eq 1 -and $foreign[0].Status -eq 'PASS') 'R2-A2 完整隔离审计未通过。'
    $baselinePath = [string]$foreign[0].Evidence.Baseline.Path
    Assert-Condition ([IO.File]::Exists($baselinePath)) 'PRECHECK R2-A2 指纹文件缺失。'
    $current = Get-Content -LiteralPath $baselinePath -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    $matched = Assert-HistoricalBaseline -Historical $Historical -Current $current
    foreach ($id in @('HOST_PLATFORM', 'ADMIN_TOKEN', 'PROCESS_SCM_READ', 'OPERATION_SCOPE', 'PUBLIC_PARENTS_SAFE', 'REAL_ARTIFACTS', 'REAL_ZIP_CONTRACT', 'PROTECTED_FILES')) {
        $check = @($precheck.Checks | Where-Object { $_.Id -eq $id })
        Assert-Condition ($check.Count -eq 1 -and $check[0].Status -eq 'PASS') ('PRECHECK 必需项未通过：{0}' -f $id)
    }
    $target = @($precheck.Checks | Where-Object { $_.Id -eq 'TARGET_SERVICE_ABSENT' })
    $paths = @($precheck.Checks | Where-Object { $_.Id -eq 'DEDICATED_PATHS_FREE' })
    $port = @($precheck.Checks | Where-Object { $_.Id -eq 'TEST_PORT_VACANT' })
    $redis = @($precheck.Checks | Where-Object { $_.Id -eq 'REDIS_PID_OWNERSHIP' })
    if ($TargetMustBeAbsent -and -not $AfterUninstall) {
        Assert-Condition ($child.ExitCode -eq 0 -and $precheck.Verdict -eq 'PRECHECK_PASS_EXECUTION_NOT_REQUESTED' -and @($precheck.Checks).Count -eq 13 -and @($precheck.Checks | Where-Object Status -ne 'PASS').Count -eq 0) '完整管理员 PRECHECK 未达到 13/13 PASS。'
        Assert-Condition ($target.Count -eq 1 -and $target[0].Status -eq 'PASS' -and $paths.Count -eq 1 -and $paths[0].Status -eq 'PASS' -and $port[0].Status -eq 'PASS' -and $redis[0].Status -eq 'PASS') '目标 SCM 或专用目录未通过隔离预检。'
    } elseif ($AfterUninstall) {
        Assert-Condition ($target.Count -eq 1 -and $target[0].Status -eq 'PASS' -and $port.Count -eq 1 -and $port[0].Status -eq 'PASS' -and $redis.Count -eq 1 -and $redis[0].Status -eq 'PASS') '卸载后目标服务或端口仍存在。'
    } else {
        Assert-Condition ($target.Count -eq 1 -and $target[0].Status -eq 'BLOCKED' -and $paths.Count -eq 1 -and $paths[0].Status -eq 'PASS') '运行中目标服务及专用目录状态与预期不符。'
        $portExpected = if ($TargetRunning) { 'BLOCKED' } else { 'PASS' }
        $redisExpected = if ($TargetRunning) { 'BLOCKED' } else { 'PASS' }
        Assert-Condition ($port.Count -eq 1 -and $port[0].Status -eq $portExpected -and $redis.Count -eq 1 -and $redis[0].Status -eq $redisExpected) '目标监听端口/Redis 进程与阶段边界不符。'
        if ($TargetRunning) {
            $ready = Get-OwnedReadyStatus
            Assert-Condition ($ready.ServiceRegistered -and $ready.OwnershipVerified -and $ready.Ready) '运行中的目标服务未通过受控归属。'
        }
    }
    $artifacts = @($precheck.Checks | Where-Object { $_.Id -eq 'REAL_ARTIFACTS' })[0].Evidence
    $result = [pscustomobject]@{ Label = $Label; ReportPath = $reportPath; Verdict = $precheck.Verdict; TargetExpectedAbsent = $TargetMustBeAbsent; Baseline = $matched; ArtifactHashes = @($artifacts | Select-Object -ExpandProperty Sha256) }
    $script:Checks.Add($result)
    return $result
}

function Invoke-TargetAudit {
    param([string]$Label)
    $auditPath = Join-Path $script:EvidenceRoot ('phase07-final-acl-{0}-{1}.json' -f $Label, [guid]::NewGuid().ToString('N'))
    $scriptPath = Join-Path $PSScriptRoot 'Invoke-WangbinCollectorRedisPhase07Evidence.ps1'
    $outPath = Join-Path $script:EvidenceRoot ('phase07-final-acl-{0}-stdout.txt' -f $Label)
    $errorPath = Join-Path $script:EvidenceRoot ('phase07-final-acl-{0}-stderr.txt' -f $Label)
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Mode AUDIT -Port {1} -ReportPath "{2}"' -f $scriptPath, $script:Port, $auditPath
    $child = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Wait -PassThru -RedirectStandardOutput $outPath -RedirectStandardError $errorPath -ErrorAction Stop
    Assert-Condition ([IO.File]::Exists($auditPath)) '独立 ACL 脚本没有生成真实系统读回回执。'
    $audit = Get-Content -LiteralPath $auditPath -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    Assert-Condition ($child.ExitCode -eq 0 -and $audit.Status -eq 'PASS' -and $audit.Target.Service.State -eq 'Running') '独立服务 DACL/文件 ACL 验收未通过；保持现场。'
    Assert-Condition ([int]$audit.Target.Service.ServiceSidType -eq 1 -and $audit.Target.Service.Dacl.MinimumPermission -eq 'PASS' -and @($audit.Target.Paths | Where-Object MinimumPermission -ne 'PASS').Count -eq 0) '独立 Service SID、服务 DACL 或关键路径 ACL 不匹配。'
    $result = [pscustomobject]@{ ReportPath = $auditPath; ServiceSid = $audit.Target.Service.ActualServiceSid; ServiceDaclSha256 = $audit.Target.Service.Dacl.SddlSha256; VerifiedPaths = @($audit.Target.Paths).Count; RedisProcessId = [int]$audit.Target.Process.RedisProcessId; WrapperProcessId = [int]$audit.Target.Process.WrapperProcessId; ExecutableSha256 = $audit.Target.Process.ExecutableSha256 }
    Add-Record -Id ('ACL_DACL_' + $Label) -Status PASS -Evidence $result
    return $audit
}

function Get-OwnedReadyStatus {
    $status = Get-WangbinCollectorRedisStatus -Port $script:Port
    Assert-Condition ($status.Ready -and $status.OwnershipVerified -and $status.PingSucceeded -and [int]$status.ListenerProcessId -eq [int]$status.RedisProcessId) '目标服务没有通过受控归属、监听 PID 和认证 PING。'
    return $status
}

function Invoke-ScopedCommand {
    param([int]$ProcessId, [string[]]$Command)
    return & $script:Module {
        param($port, $expectedPid, $parts)
        $layout = Get-WangbinCollectorRedisLayout -Port $port
        $owned = Assert-OwnedService -Layout $layout
        if (-not $owned.Identity.Owned -or [int]$owned.Identity.RedisProcessId -ne $expectedPid -or -not (Test-OwnedListener -Layout $layout -Identity $owned.Identity)) { throw 'Redis RESP 操作前子进程和监听归属不匹配。' }
        $password = Get-ManagedPassword -Layout $layout -Marker $owned.Marker
        try { Invoke-RedisCommand -TargetHost '127.0.0.1' -Port $port -Password $password -Command $parts -TimeoutMilliseconds 5000 -ExpectedProcessId $expectedPid }
        finally { $password = $null }
    } $script:Port $ProcessId $Command
}

function Invoke-Probe {
    param([int]$ProcessId, [switch]$Write)
    if ($Write) {
        $nonce = [guid]::NewGuid().ToString('N')
        $script:Probe = [pscustomobject]@{ CacheKey = ('wangbin:phase07:final:cache:' + $nonce); StreamKey = ('wangbin:phase07:final:stream:' + $nonce); Value = ('phase07-final-' + $nonce); Nonce = $nonce }
        $set = Invoke-ScopedCommand -ProcessId $ProcessId -Command @('SET', $script:Probe.CacheKey, $script:Probe.Value, 'NX')
        Assert-Condition ([string]$set.Value -ceq 'OK') '唯一 Cache key SET NX 失败。'
        $stream = Invoke-ScopedCommand -ProcessId $ProcessId -Command @('XADD', $script:Probe.StreamKey, '*', 'phase', '07', 'probe', $nonce)
        Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$stream.Value)) '唯一 Stream key XADD 失败。'
        $save = Invoke-ScopedCommand -ProcessId $ProcessId -Command @('SAVE')
        Assert-Condition ([string]$save.Value -ceq 'OK') '故障注入前 Redis 持久化 SAVE 未确认。'
    }
    Assert-Condition ($null -ne $script:Probe) '缺少本次会话生成的隔离探针。'
    $cache = Invoke-ScopedCommand -ProcessId $ProcessId -Command @('GET', $script:Probe.CacheKey)
    Assert-Condition ([string]$cache.Value -ceq [string]$script:Probe.Value) 'Cache 精确值回读不匹配。'
    $range = Invoke-ScopedCommand -ProcessId $ProcessId -Command @('XRANGE', $script:Probe.StreamKey, '-', '+')
    $rangeJson = $range.Value | ConvertTo-Json -Depth 12 -Compress
    Assert-Condition ($rangeJson -like ('*' + $script:Probe.Nonce + '*')) 'Stream 精确探针回读不匹配。'
    return [pscustomobject]@{ Cache = 'MATCH'; Stream = 'MATCH'; PersistedBeforeCrash = [bool]$Write; Nonce = $script:Probe.Nonce }
}

function Assert-ExactFaultIdentity {
    param($Audit)
    $target = $Audit.Target
    $targetRedisPid = [int]$target.Process.RedisProcessId
    Assert-Condition ($targetRedisPid -gt 0 -and $targetRedisPid -notin @(56800, 55424, 46128) -and [int]$target.Service.ProcessId -eq [int]$target.Process.ParentProcessId -and [int]$target.Service.ProcessId -eq [int]$target.Process.WrapperProcessId) '故障 PID 与受保护 PID 冲突或 Redis/wrapper 父子身份不匹配。'
    $process = Get-CimInstance Win32_Process -Filter ('ProcessId={0}' -f $targetRedisPid) -ErrorAction Stop
    Assert-Condition ($null -ne $process -and [int]$process.ParentProcessId -eq [int]$target.Service.ProcessId -and [string]$process.CreationDate -ceq [string]$target.Process.CreationDate -and [IO.Path]::GetFullPath([string]$process.ExecutablePath) -ieq [string]$target.Process.ExecutablePath) '故障前 PID 被替换或进程身份变化。'
    $diskHash = (Get-FileHash -LiteralPath $process.ExecutablePath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
    Assert-Condition ($diskHash -ceq [string]$target.Process.ExecutableSha256 -and $diskHash -ceq [string]$target.Marker.RedisExecutableSha256 -and $target.Process.ConfigurationPathVerified -and $target.Process.ListenerPidMatchesRedis) 'Redis 可执行文件哈希、配置参数或监听 PID 未通过验证。'
    $ready = Get-OwnedReadyStatus
    Assert-Condition ([int]$ready.RedisProcessId -eq $targetRedisPid -and [int]$ready.ServiceProcessId -eq [int]$target.Service.ProcessId) '故障前受控归属在最后一次检查中变化。'
    return [pscustomobject]@{ ProcessId = $targetRedisPid; WrapperProcessId = [int]$target.Service.ProcessId; CreationDate = [string]$process.CreationDate; ExecutableSha256 = $diskHash; ConfigurationPath = $target.Process.ConfigurationPath; ListenerProcessId = [int]$ready.ListenerProcessId }
}

function Assert-ProtectedRuntime {
    $names = @('WangbinCollector-R2A2-Redis', 'WangbinCollector-R2A2-Collector')
    $services = @(Get-CimInstance Win32_Service -ErrorAction Stop | Where-Object { $names -contains [string]$_.Name })
    Assert-Condition ($services.Count -eq 2) 'R2-A2 服务读回不完整。'
    foreach ($pair in @(@('WangbinCollector-R2A2-Redis', 55424), @('WangbinCollector-R2A2-Collector', 46128))) {
        $match = @($services | Where-Object { $_.Name -eq $pair[0] -and $_.State -eq 'Running' -and [int]$_.ProcessId -eq [int]$pair[1] })
        Assert-Condition ($match.Count -eq 1) ('R2-A2 服务状态或 PID 变化：{0}' -f $pair[0])
    }
    $redis = Get-CimInstance Win32_Process -Filter 'ProcessId=56800' -ErrorAction Stop
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { [int]$_.LocalPort -eq 26379 })
    Assert-Condition ($null -ne $redis -and [int]$redis.ParentProcessId -eq 55424 -and $listeners.Count -eq 1 -and [int]$listeners[0].OwningProcess -eq 56800 -and [string]$listeners[0].LocalAddress -eq '127.0.0.1') 'R2-A2 Redis 子进程或 26379 监听者变化。'
}

function Wait-AutomaticRecovery {
    param($Before, [datetime]$FaultUtc)
    $deadline = [datetime]::UtcNow.AddSeconds(120)
    $states = New-Object System.Collections.Generic.List[object]
    while ([datetime]::UtcNow -lt $deadline) {
        Assert-ProtectedRuntime
        $service = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f $script:ServiceName) -ErrorAction Stop
        Assert-Condition ($null -ne $service) '故障后 SCM 服务记录消失；停止自动恢复观察。'
        $states.Add([pscustomobject]@{ Utc = [datetime]::UtcNow.ToString('o'); ScmState = [string]$service.State; WrapperPid = [int]$service.ProcessId })
        if ([string]$service.State -eq 'Running' -and [int]$service.ProcessId -gt 0) {
            try {
                $ready = Get-OwnedReadyStatus
                if ([int]$ready.RedisProcessId -ne [int]$Before.ProcessId -and [int]$ready.RedisProcessId -gt 0) {
                    $newProcess = Get-CimInstance Win32_Process -Filter ('ProcessId={0}' -f [int]$ready.RedisProcessId) -ErrorAction Stop
                    Assert-Condition ($null -ne $newProcess -and [int]$newProcess.ParentProcessId -eq [int]$ready.ServiceProcessId -and $newProcess.CreationDate -gt ([datetime]$Before.CreationDate)) '恢复后的 Redis PID、父子关系或创建时间无效。'
                    $channel = if ([int]$ready.ServiceProcessId -eq [int]$Before.WrapperProcessId) { 'WINSW_CHILD_RECOVERY_SCM_NOT_TRIGGERED' } else { 'SCM_SERVICE_RECOVERY_WRAPPER_REPLACED' }
                    $scmEvents = @()
                    if ($channel -eq 'SCM_SERVICE_RECOVERY_WRAPPER_REPLACED') {
                        $scmEvents = @(Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = 'Service Control Manager'; StartTime = $FaultUtc; Id = 7031 } -ErrorAction Stop | Where-Object { [string]$_.Message -like ('*' + $script:ServiceName + '*') } | Select-Object Id,RecordId,TimeCreated)
                        Assert-Condition ($scmEvents.Count -gt 0) 'wrapper 已更换，但缺少 SCM 7031 故障恢复事件；不能证明 FailureActions 实际触发。'
                    }
                    return [pscustomobject]@{ Channel = $channel; ScmFailureActionObserved = ($scmEvents.Count -gt 0); ScmEvents = $scmEvents; RedisProcessId = [int]$ready.RedisProcessId; WrapperProcessId = [int]$ready.ServiceProcessId; Ping = 'PONG'; States = @($states.ToArray()) }
                }
            } catch {
                if ([datetime]::UtcNow -ge $deadline) { throw }
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw '120 秒内未观测到自动恢复；禁止用手工 START 伪造 PASS。'
}

function Get-InterruptedRun {
    $path = Join-Path $script:EvidenceRoot 'phase07-final-gates-execute-20261105-152327-026.json'
    Assert-Condition ([IO.File]::Exists($path) -and -not ((Get-Item -LiteralPath $path -Force -ErrorAction Stop).Attributes -band [IO.FileAttributes]::ReparsePoint)) '既有阶段 07 中断回执不存在或发生路径重定向。'
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
    Assert-Condition ($hash -ceq 'dc3d32aa7db3eb7e97ee1724de2a1e4b3fec3a25cbddac63b67ac6411356de6a') '既有阶段 07 中断回执哈希不匹配。'
    $previous = Get-Content -LiteralPath $path -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    Assert-Condition ([string]$previous.Host -ceq $env:COMPUTERNAME -and [bool]$previous.Administrator -and
        [string]$previous.Mode -ceq 'EXECUTE' -and [string]$previous.Status -ceq 'BLOCKED' -and
        [string]$previous.HistoricalFingerprint -ceq $script:HistoricalFingerprint -and
        [string]$previous.Test07_02 -ceq 'NOT_EXECUTED' -and [string]$previous.Test07_06 -ceq 'NOT_EXECUTED' -and
        -not [bool]$previous.FaultAttempted -and -not [bool]$previous.FaultInjected) '中断回执不符合只完成 INSTALL/START 的续验条件。'
    $actions = @($previous.Actions)
    Assert-Condition ($actions.Count -eq 2 -and $actions[0].Id -ceq 'INSTALL' -and $actions[0].Status -ceq 'PASS' -and
        [bool]$actions[0].Evidence.Installed -and -not [bool]$actions[0].Evidence.AlreadyInstalled -and
        $actions[1].Id -ceq 'START' -and $actions[1].Status -ceq 'PASS' -and
        [bool]$actions[1].Evidence.Started -and -not [bool]$actions[1].Evidence.AlreadyRunning -and
        [int]$actions[1].Evidence.RedisProcessId -gt 0 -and [int]$actions[1].Evidence.RedisProcessId -ne 56800) '中断回执缺少唯一 INSTALL/START 的可信归属。'
    return $previous
}

function Invoke-AuthorizedAcceptance {
    param($Historical)
    $script:Module = Import-Module -Name (Join-Path $PSScriptRoot 'WangbinCollectorRedis.psm1') -Force -PassThru
    $null = Invoke-ExistingPrecheck -Label 'before-install' -Historical $Historical -TargetMustBeAbsent $true
    $confirmation = Read-Host '请在本次管理员窗口手工输入本机合并验收授权短语（禁止自动读取脚本或环境变量代填）'
    Assert-Condition ([string]$confirmation -ceq 'WangbinCollector-Phase07-FINAL-GATES-APPROVED') '未获得本次合并验收的人工授权；未执行 INSTALL。'
    $confirmation = $null
    $null = Invoke-ExistingPrecheck -Label 'authorized-before-install' -Historical $Historical -TargetMustBeAbsent $true
    $install = Install-WangbinCollectorRedis -RedisDistributionArchive (Join-Path $script:EvidenceRoot 'Redis-8.10.3-Windows-x64-msys2.zip') -ExpectedRedisArchiveSha256 'af16df340525b431e9ec8dabfd625d5e7a233f82805b5418819904ea909c447f' -WinSwExecutable (Join-Path $script:EvidenceRoot 'WinSW-x64.exe') -ExpectedWinSwSha256 '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da' -RedisSourceDescription 'GitHub Release redis-windows/redis-windows 8.10.3; owner-authorized isolated Phase 07 acceptance.' -WinSwSourceDescription 'GitHub Release winsw/winsw v2.12.0; owner-authorized isolated Phase 07 acceptance.' -RedisLicenseFile (Join-Path $script:EvidenceRoot 'licenses\redis-windows-LICENSE') -WinSwLicenseFile (Join-Path $script:EvidenceRoot 'licenses\winsw-LICENSE.txt') -Port $script:Port
    Add-Record -Id INSTALL -Status PASS -Evidence $install
    $null = Invoke-ExistingPrecheck -Label 'after-install' -Historical $Historical -TargetMustBeAbsent $false
    $start = Start-WangbinCollectorRedis -TimeoutSeconds 120 -Port $script:Port
    Add-Record -Id START -Status PASS -Evidence $start
    $null = Invoke-ExistingPrecheck -Label 'after-start' -Historical $Historical -TargetMustBeAbsent $false -TargetRunning $true
    Invoke-PostStartAcceptance -Historical $Historical
}

function Invoke-PostStartAcceptance {
    param($Historical, [int]$ExpectedRedisPid = 0)
    $firstAudit = Invoke-TargetAudit -Label 'before-fault'
    if ($ExpectedRedisPid -gt 0) {
        Assert-Condition ([int]$firstAudit.Target.Process.RedisProcessId -eq $ExpectedRedisPid) '续验目标 Redis PID 已变化；不对未知或替代进程注入故障。'
    }
    $before = Assert-ExactFaultIdentity -Audit $firstAudit
    $beforeData = Invoke-Probe -ProcessId $before.ProcessId -Write
    Add-Record -Id PROBE_BEFORE_FAULT -Status PASS -Evidence $beforeData
    $persistedAudit = Invoke-TargetAudit -Label 'after-persist-before-fault'
    Assert-Condition (@($persistedAudit.Target.Paths | Where-Object { $_.Role -eq 'ProgramData Redis data file' }).Count -gt 0 -and @($persistedAudit.Target.Paths | Where-Object { $_.Role -eq 'ProgramData service log' }).Count -gt 0) '持久化数据文件或运行日志未取得独立 ACL 证据。'
    $script:Test07_02 = 'PASS'
    $null = Invoke-ExistingPrecheck -Label 'before-fault' -Historical $Historical -TargetMustBeAbsent $false -TargetRunning $true
    $before = Assert-ExactFaultIdentity -Audit $persistedAudit
    Add-Record -Id FAULT_TARGET_FINAL_IDENTITY -Status PASS -Evidence $before
    $guarded = Get-Process -Id ([int]$before.ProcessId) -ErrorAction Stop
    # CIM 创建时间经只读回执转为字符串后仅保留秒；句柄时间按相同精度核对，且另行核验父子关系、哈希和监听者。
    Assert-Condition ($guarded.StartTime.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss') -ceq ([datetime]$before.CreationDate).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss')) '故障注入前进程句柄创建时间与已证明 PID 不一致。'
    Save-Report -Status 'FAULT_INJECTION_PENDING' -Reason '已确认精确 PID 和 R2-A2 完整基线；下一步仅针对该 Redis 子进程执行一次 Stop-Process。' | Out-Null
    $faultUtc = [datetime]::UtcNow
    $script:FaultAttempted = $true
    Stop-Process -InputObject $guarded -Force -ErrorAction Stop
    $script:FaultInjected = $true
    Add-Record -Id EXACT_PID_FAULT_INJECTION -Status PASS -Evidence ([pscustomobject]@{ RedisProcessId = $before.ProcessId; WrapperProcessId = $before.WrapperProcessId; TargetWasNotProtectedPid = $true })
    Save-Report -Status 'OBSERVING_AUTOMATIC_RECOVERY' -Reason '故障注入已执行；尚未将恢复判定为 PASS。' | Out-Null
    $recovered = Wait-AutomaticRecovery -Before $before -FaultUtc $faultUtc
    Add-Record -Id AUTOMATIC_RECOVERY -Status PASS -Evidence $recovered
    $null = Invoke-ExistingPrecheck -Label 'after-fault' -Historical $Historical -TargetMustBeAbsent $false -TargetRunning $true
    $afterAudit = Invoke-TargetAudit -Label 'after-fault'
    Assert-Condition ([int]$afterAudit.Target.Process.RedisProcessId -eq [int]$recovered.RedisProcessId -and [string]$afterAudit.Target.Process.ExecutableSha256 -ceq [string]$firstAudit.Target.Process.ExecutableSha256) '恢复后 Redis 文件哈希或受控归属不一致。'
    $afterData = Invoke-Probe -ProcessId $recovered.RedisProcessId
    Add-Record -Id PROBE_AFTER_FAULT -Status PASS -Evidence $afterData
    $script:Test07_06 = 'PASS'
    $deleted = Invoke-ScopedCommand -ProcessId $recovered.RedisProcessId -Command @('DEL', $script:Probe.CacheKey, $script:Probe.StreamKey)
    Add-Record -Id CLEANUP_EXACT_PROBE_KEYS -Status PASS -Evidence ([pscustomobject]@{ Deleted = $deleted.Value; CacheKey = $script:Probe.CacheKey; StreamKey = $script:Probe.StreamKey })
    $null = Invoke-ExistingPrecheck -Label 'before-stop' -Historical $Historical -TargetMustBeAbsent $false -TargetRunning $true
    $stop = Stop-WangbinCollectorRedis -TimeoutSeconds 120 -Port $script:Port
    Assert-Condition ([bool]$stop.Stopped) '受控 STOP 未确认。'
    Add-Record -Id STOP -Status PASS -Evidence $stop
    $null = Invoke-ExistingPrecheck -Label 'before-uninstall' -Historical $Historical -TargetMustBeAbsent $false
    $uninstall = Uninstall-WangbinCollectorRedis -TimeoutSeconds 120 -Port $script:Port
    Assert-Condition ([bool]$uninstall.Uninstalled) '受控 UNINSTALL 未确认。'
    Add-Record -Id UNINSTALL -Status PASS -Evidence $uninstall
    $null = Invoke-ExistingPrecheck -Label 'after-uninstall' -Historical $Historical -TargetMustBeAbsent $true -AfterUninstall
    $status = Get-WangbinCollectorRedisStatus -Port $script:Port
    Assert-Condition (-not $status.ServiceRegistered -and -not $status.PortListening -and $status.Mode -eq 'NOT_INSTALLED' -and $status.Evidence -eq 'VERIFIED') '目标服务注销或 16379 释放读回失败。'
    Add-Record -Id FINAL_TARGET_READBACK -Status PASS -Evidence ($status | Select-Object ServiceRegistered,PortListening,Mode,ScmState)
}

function Invoke-AuthorizedResume {
    param($Historical)
    $script:Module = Import-Module -Name (Join-Path $PSScriptRoot 'WangbinCollectorRedis.psm1') -Force -PassThru
    $previous = Get-InterruptedRun
    $null = Invoke-ExistingPrecheck -Label 'resume-before-authorization' -Historical $Historical -TargetMustBeAbsent $false -TargetRunning $true
    $confirmation = Read-Host '请在本次管理员窗口手工输入续验授权短语（禁止自动从源码读取代填）'
    Assert-Condition ([string]$confirmation -ceq 'WangbinCollector-Phase07-FINAL-GATES-APPROVED') '未取得本次人工确认；保持已安装服务现场。'
    $confirmation = $null
    $null = Invoke-ExistingPrecheck -Label 'resume-before-fault' -Historical $Historical -TargetMustBeAbsent $false -TargetRunning $true
    Add-Record -Id EXISTING_INSTALL_START -Status PASS -Evidence ([pscustomobject]@{ PreviousReportSha256 = 'dc3d32aa7db3eb7e97ee1724de2a1e4b3fec3a25cbddac63b67ac6411356de6a'; PreviousRedisProcessId = [int]$previous.Actions[1].Evidence.RedisProcessId; NoSecondInstallOrStart = $true })
    Invoke-PostStartAcceptance -Historical $Historical -ExpectedRedisPid ([int]$previous.Actions[1].Evidence.RedisProcessId)
}

try {
    Assert-Administrator
    $historical = Get-HistoricalBaseline
    if ($Mode -eq 'PRECHECK') {
        $null = Invoke-ExistingPrecheck -Label 'standalone' -Historical $historical -TargetMustBeAbsent $true
        Save-Report -Status 'READY_FOR_AUTHORIZATION' -Reason '管理员 13/13 PRECHECK、R2-A2 历史指纹及九项 ACL、真实构件和目标隔离已通过；未执行 INSTALL 或故障注入。'
        exit 0
    }
    if ($Mode -eq 'RESUME') {
        Invoke-AuthorizedResume -Historical $historical
    } else {
        Invoke-AuthorizedAcceptance -Historical $historical
    }
    Save-Report -Status 'PASS' -Reason '两项独立真实门禁及受控 STOP/UNINSTALL 后 R2-A2 完整基线读回通过。'
    exit 0
} catch {
    $reason = if ($_.Exception.Message -like 'ADMINISTRATOR_REQUIRED:*') { 'ADMINISTRATOR_REQUIRED' } else { $_.Exception.Message -replace '(?i)([A-Z]:\\Users\\)[^\\]+', '$1[REDACTED]' }
    Save-Report -Status 'BLOCKED' -Reason $reason
    exit 2
}
