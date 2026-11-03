#requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot 'WangbinCollectorRedis.psm1'
$sourceText = [IO.File]::ReadAllText($modulePath, (New-Object System.Text.UTF8Encoding($true)))
Import-Module -Name $modulePath -Force
$module = Get-Module WangbinCollectorRedis
& $module {
    function Assert-Condition {
        param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
        if (-not $Condition) { throw $Message }
    }
    function Assert-Throws {
        param([Parameter(Mandatory)][scriptblock]$Action, [string]$MessagePattern = '*')
        $thrown = $false
        $message = $null
        try { & $Action } catch { $thrown = $true; $message = $_.Exception.Message }
        if (-not $thrown) { throw '预期操作应拒绝，但没有抛出异常。' }
        if ($message -notlike $MessagePattern) { throw ('拒绝原因不匹配：{0}' -f $message) }
    }
}

function Assert-Condition {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([Parameter(Mandatory)][scriptblock]$Action, [string]$MessagePattern = '*')
    $thrown = $false
    $message = $null
    try { & $Action } catch { $thrown = $true; $message = $_.Exception.Message }
    if (-not $thrown) { throw '预期操作应拒绝，但没有抛出异常。' }
    if ($message -notlike $MessagePattern) { throw ('拒绝原因不匹配：{0}' -f $message) }
}

function Invoke-ModuleAction {
    param([Parameter(Mandatory)][scriptblock]$Action, [object[]]$Arguments = @())
    Import-Module -Name $modulePath -Force | Out-Null
    $script:module = Get-Module WangbinCollectorRedis
    if ($Arguments.Count -eq 0) {
        & $script:module $Action
    } elseif ($Arguments.Count -eq 1) {
        & $script:module $Action $Arguments[0]
    } else {
        throw '安全测试暂不支持超过一个模块脚本参数。'
    }
}

function New-Result {
    param([Parameter(Mandatory)][string]$Id, [Parameter(Mandatory)][string]$Status, [Parameter(Mandatory)][int]$Assertions, [string]$Detail)
    [pscustomobject][ordered]@{
        Id = $Id
        Status = $Status
        Assertions = $Assertions
        Detail = $Detail
        Scope = 'NO_SCM_NO_SYSTEM_ACL_NO_SERVICE_LIFECYCLE'
    }
}

function Invoke-Case {
    param([Parameter(Mandatory)][string]$Id, [Parameter(Mandatory)][scriptblock]$Action, [Parameter(Mandatory)][int]$Assertions)
    try {
        $detail = & $Action
        if ($null -eq $detail) { $detail = 'deterministic assertions passed' }
        New-Result -Id $Id -Status 'PASS' -Assertions $Assertions -Detail ([string]$detail)
    } catch {
        New-Result -Id $Id -Status 'FAIL' -Assertions $Assertions -Detail $_.Exception.Message
    }
}

$rootBase = if (-not [string]::IsNullOrWhiteSpace($env:TMPDIR)) { $env:TMPDIR } else { Join-Path $env:LOCALAPPDATA 'hermes\cache\scratch' }
$testRoot = Join-Path $rootBase ('wangbin-redis-security-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$results = @()

try {
    $results += (Invoke-Case -Id 'TEST-07-S01' -Assertions 8 -Action {
        Invoke-ModuleAction -Action {
            function Assert-Condition {
                param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
                if (-not $Condition) { throw $Message }
            }
            function Assert-Throws {
                param([Parameter(Mandatory)][scriptblock]$Action, [string]$MessagePattern = '*')
                $thrown = $false
                $message = $null
                try { & $Action } catch { $thrown = $true; $message = $_.Exception.Message }
                if (-not $thrown) { throw '预期操作应拒绝，但没有抛出异常。' }
                if ($message -notlike $MessagePattern) { throw ('拒绝原因不匹配：{0}' -f $message) }
            }
            $root = $args[0]
            $sourceText = [IO.File]::ReadAllText((Get-Module WangbinCollectorRedis).Path, (New-Object System.Text.UTF8Encoding($true)))
            $path = Join-Path $root 'Program Files\WangbinCollector\Redis\bin\WangbinCollector-Redis.exe'
            $quote = [char]34
            $valid = $quote + $path + $quote + ' //RS//WangbinCollector-Redis'
            $casePath = $quote + $path.ToUpperInvariant() + $quote + ' //RS//WangbinCollector-Redis'
            Assert-Condition (Test-ServicePath -Actual $valid -Expected $path -ExpectedTail '//RS//WangbinCollector-Redis') '正确 wrapper 命令行未被接受。'
            Assert-Condition (Test-ServicePath -Actual $casePath -Expected $path -ExpectedTail '//RS//WangbinCollector-Redis') '大小写变化不应改变绝对路径归属。'
            Assert-Condition (-not (Test-ServicePath -Actual ($quote + $path + $quote + ' //RS//Other') -Expected $path -ExpectedTail '//RS//WangbinCollector-Redis')) '篡改服务名参数未被拒绝。'
            Assert-Condition (-not (Test-ServicePath -Actual ($quote + $path + $quote + ' --bad') -Expected $path -ExpectedTail '//RS//WangbinCollector-Redis')) '附加参数未被拒绝。'
            Assert-Condition (-not (Test-ServicePath -Actual ($quote + (Join-Path $root 'other.exe') + $quote + ' //RS//WangbinCollector-Redis') -Expected $path -ExpectedTail '//RS//WangbinCollector-Redis')) 'wrapper 路径替换未被拒绝。'
            Assert-Condition (-not (Test-ServicePath -Actual ($quote + $path + $quote) -Expected $path -ExpectedTail '//RS//WangbinCollector-Redis')) '缺少 WinSW 服务参数未被拒绝。'
            Assert-Condition (-not (Test-ServicePath -Actual ($quote + $path + $quote + ' //RS//WangbinCollector-Redis extra') -Expected $path -ExpectedTail '//RS//WangbinCollector-Redis')) '多余尾部参数未被拒绝。'
            Assert-Condition ($sourceText.IndexOf('TargetHost', [StringComparison]::Ordinal) -ge 0 -and $sourceText.IndexOf('$Host', [StringComparison]::Ordinal) -lt 0) 'PowerShell protected $Host 参数仍存在。'
            'SCM 完整路径、固定参数和 TargetHost 校验通过。'
        } -Arguments @($testRoot)
    })

    $results += (Invoke-Case -Id 'TEST-07-S02' -Assertions 10 -Action {
        Invoke-ModuleAction -Action {
            function Assert-Condition {
                param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
                if (-not $Condition) { throw $Message }
            }
            function Assert-Throws {
                param([Parameter(Mandatory)][scriptblock]$Action, [string]$MessagePattern = '*')
                $thrown = $false
                $message = $null
                try { & $Action } catch { $thrown = $true; $message = $_.Exception.Message }
                if (-not $thrown) { throw '预期操作应拒绝，但没有抛出异常。' }
                if ($message -notlike $MessagePattern) { throw ('拒绝原因不匹配：{0}' -f $message) }
            }
            $root = $args[0]
            $layout = Get-WangbinCollectorRedisLayout -ProgramFilesRoot (Join-Path $root 'pf') -ProgramDataRoot (Join-Path $root 'pd')
            [IO.Directory]::CreateDirectory((Split-Path $layout.MarkerFile -Parent)) | Out-Null
            $distribution = [pscustomobject]@{ Architecture = 'x64'; RuntimeFamily = 'MSYS2'; ArchivePath = (Join-Path $root 'redis.zip'); ArchiveSha256 = ('f' * 64); Files = @() }
            $marker = New-InitialMarker -Layout $layout -RedisDistribution $distribution -RedisHash ('a' * 64) -WrapperHash ('b' * 64) -XmlHash ('c' * 64) -ConfigHash ('d' * 64) -SecretHash ('e' * 64) -RedisVersion '8.10.3' -WrapperVersion 'winsw-test' -RedisSource 'approved redis source' -WrapperSource 'approved winsw source' -RedisLicensePath (Join-Path $root 'redis-license.txt') -WrapperLicensePath (Join-Path $root 'winsw-license.txt') -RedisLicenseHash ('1' * 64) -WrapperLicenseHash ('2' * 64)
            $marker.RegistrationState = 'Uninstalled'
            [IO.File]::WriteAllText($layout.MarkerFile, ($marker | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
            $read = Get-ManagedMarker -Layout $layout
            Assert-Condition ($read.ServiceName -eq $layout.ServiceName) '完整 marker 未被读回。'
            $marker.CodeRoot = Join-Path $root 'foreign-code'
            [IO.File]::WriteAllText($layout.MarkerFile, ($marker | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
            Assert-Throws -Action { Get-ManagedMarker -Layout $layout } -MessagePattern '*路径*'
            $marker.CodeRoot = $layout.CodeRoot
            $marker.RegistrationState = 'Installed'
            $marker.ServiceSid = 'not-a-service-sid'
            [IO.File]::WriteAllText($layout.MarkerFile, ($marker | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
            Assert-Throws -Action { Get-ManagedMarker -Layout $layout } -MessagePattern '*SID*'
            $marker.RegistrationState = 'Uninstalled'
            $marker.ServiceSid = $null
            [IO.File]::Delete($layout.MarkerFile)
            Assert-Condition ($null -eq (Get-ManagedMarker -Layout $layout)) 'marker 缺失未返回 null。'
            $artifact = Join-Path $root 'artifact.bin'
            [IO.File]::WriteAllText($artifact, 'owned artifact', (New-Object System.Text.UTF8Encoding($false)))
            $badMarker = [pscustomobject]@{ ArtifactSha256 = ('0' * 64) }
            Assert-Throws -Action { Assert-MarkerArtifact -Marker $badMarker -Path $artifact -HashProperty ArtifactSha256 } -MessagePattern '*哈希不匹配*'

            $moduleObject = Get-Module WangbinCollectorRedis
            $moduleObject.SessionState.InvokeCommand.InvokeScript($false, {
                function Get-ScPath { 'mock-sc.exe' }
                function Invoke-NativeProcess { param($FilePath, $Arguments, $Deadline, [switch]$CaptureOutput); return $script:MockSddl }
                function Get-Acl { [pscustomobject]@{ AreAccessRulesProtected = $false } }
                $script:MockSddl = 'D:P(A;;GA;;;SY)(A;;GA;;;BA)'
            }, $null, $null) | Out-Null
            Assert-ServiceDacl -Name 'WangbinCollector-Redis'
            $moduleObject.SessionState.InvokeCommand.InvokeScript($false, { $script:MockSddl = 'D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;WD)' }, $null, $null) | Out-Null
            Assert-Throws -Action { Assert-ServiceDacl -Name 'WangbinCollector-Redis' } -MessagePattern '*非本产品管理员控制授权*'
            $driftFile = Join-Path $root 'drift.txt'
            [IO.File]::WriteAllText($driftFile, 'acl')
            Assert-Throws -Action { Assert-ControlledFileAcl -Path $driftFile -Mode Admin } -MessagePattern '*所有者或继承保护不匹配*'
            'marker、构件哈希、服务 DACL 解析和 ACL 漂移拒绝通过。'
        } -Arguments @($testRoot)
    })

    $results += (Invoke-Case -Id 'TEST-07-S03' -Assertions 8 -Action {
        Invoke-ModuleAction -Action {
            function Assert-Condition {
                param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
                if (-not $Condition) { throw $Message }
            }
            function Assert-Throws {
                param([Parameter(Mandatory)][scriptblock]$Action, [string]$MessagePattern = '*')
                $thrown = $false
                $message = $null
                try { & $Action } catch { $thrown = $true; $message = $_.Exception.Message }
                if (-not $thrown) { throw '预期操作应拒绝，但没有抛出异常。' }
                if ($message -notlike $MessagePattern) { throw ('拒绝原因不匹配：{0}' -f $message) }
            }
            function New-FailureBytes {
                param([int]$ActionsOffset = 20, [int]$Count = 4, [int]$TrailingBytes = 0, [int]$FirstType = 1)
                $length = $ActionsOffset + ([math]::Max($Count, 0) * 8) + $TrailingBytes
                if ($length -lt 20) { $length = 20 }
                $bytes = New-Object byte[] $length
                [BitConverter]::GetBytes([int]3600).CopyTo($bytes, 0)
                [BitConverter]::GetBytes($Count).CopyTo($bytes, 12)
                [BitConverter]::GetBytes($ActionsOffset).CopyTo($bytes, 16)
                for ($i = 0; $i -lt $Count; $i++) {
                    [BitConverter]::GetBytes([int]$(if ($i -eq 0) { $FirstType } elseif ($i -eq 3) { 0 } else { 1 })).CopyTo($bytes, $ActionsOffset + ($i * 8))
                    [BitConverter]::GetBytes([int]$(if ($i -eq 0) { 5000 } elseif ($i -eq 1) { 10000 } elseif ($i -eq 2) { 15000 } else { 0 })).CopyTo($bytes, $ActionsOffset + ($i * 8) + 4)
                }
                return $bytes
            }
            $validBytes = [byte[]](New-FailureBytes)
            $valid = ConvertFrom-FailureActions -Bytes $validBytes
            Assert-Condition ($null -ne $valid -and ($valid.Actions -join ',') -eq '1:5000,1:10000,1:15000,0:0') '正确 FailureActions 未被解析。'
            foreach ($bad in @(
                (New-FailureBytes -ActionsOffset 24),
                (New-FailureBytes -Count 129),
                (New-FailureBytes -TrailingBytes 1),
                (New-FailureBytes -FirstType 2)
            )) {
                $summary = ConvertFrom-FailureActions -Bytes ([byte[]]$bad)
                Assert-Condition ($null -eq $summary -or ($summary.Actions -join ',') -ne '1:5000,1:10000,1:15000,0:0') '非法 FailureActions 被默认放行。'
            }
            $invalidBytes = New-FailureBytes -ActionsOffset 20 -Count 4 -TrailingBytes 1
            Assert-Condition ($null -eq (ConvertFrom-FailureActions -Bytes ([byte[]]$invalidBytes))) '非法尾部数据未被拒绝。'
            $source = [IO.File]::ReadAllText((Get-Module WangbinCollectorRedis).Path, (New-Object System.Text.UTF8Encoding($true)))
            Assert-Condition ($source.IndexOf('Get-FailureActionSummary', [StringComparison]::Ordinal) -ge 0) '服务配置未使用 FailureActions 汇总。'
            Assert-Condition ($source.IndexOf('FailureActions 不是预期', [StringComparison]::Ordinal) -ge 0) '无效服务恢复策略未阻断。'
            'FailureActions offset、长度、动作和服务归属拒绝通过。'
        }
    })

    $results += (Invoke-Case -Id 'TEST-07-S04' -Assertions 7 -Action {
        Invoke-ModuleAction -Action {
            function Assert-Condition {
                param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
                if (-not $Condition) { throw $Message }
            }
            function Assert-Throws {
                param([Parameter(Mandatory)][scriptblock]$Action, [string]$MessagePattern = '*')
                $thrown = $false
                $message = $null
                try { & $Action } catch { $thrown = $true; $message = $_.Exception.Message }
                if (-not $thrown) { throw '预期操作应拒绝，但没有抛出异常。' }
                if ($message -notlike $MessagePattern) { throw ('拒绝原因不匹配：{0}' -f $message) }
            }
            $source = $args[0]
            $moduleObject = Get-Module WangbinCollectorRedis
            $ownershipSetup = {
                function Get-ManagedOwnership {
                    param($Layout)
                    [pscustomobject]@{ Ownership = 'MANAGED_REDIS'; Marker = [pscustomobject]@{ RedisVersion = 'x'; WrapperVersion = 'y' }; Service = [pscustomobject]@{ State = 'Stopped'; ProcessId = 0 }; Identity = [pscustomobject]@{ Owned = $false; RedisProcessId = $null }; Diagnostic = 'fake-managed' }
                }
                function Get-ListenerRecord { param($Port); @() }
                function Get-ManagedPassword { param($Layout, $Marker); $script:PasswordRead = $true; throw 'password must not be read' }
                $script:PasswordRead = $false
            }
            $moduleObject.SessionState.InvokeCommand.InvokeScript($false, $ownershipSetup, $null, $null) | Out-Null
            $status = Get-WangbinCollectorRedisStatus -TargetHost '203.0.113.77' -Port 6379
            $passwordRead = [bool]$moduleObject.SessionState.PSVariable.Get('PasswordRead').Value
            Assert-Condition (-not $passwordRead) '未验证 listener 时读取了 managed 密码。'
            Assert-Condition ($status.Evidence -eq 'BLOCKED' -and $status.PingSucceeded -eq $false) '未验证 listener 未返回 BLOCKED。'
            $authIndex = $source.IndexOf('if (-not [string]::IsNullOrEmpty($Password))', [StringComparison]::Ordinal)
            $listenerIndex = $source.IndexOf('$listeners = @(Get-ListenerRecord -Port $Port)', [StringComparison]::Ordinal)
            Assert-Condition ($listenerIndex -ge 0 -and $listenerIndex -lt $authIndex) 'AUTH 前缺少连接后 listener 复核。'
            Assert-Condition ($source.IndexOf("LocalAddress -ne '127.0.0.1'", [StringComparison]::Ordinal) -ge 0) '缺少 loopback 地址限制。'
            Assert-Condition ($source.IndexOf('TargetHost', [StringComparison]::Ordinal) -ge 0) '外部目标参数未显式命名。'
            Assert-Condition ($source.IndexOf('Get-ManagedPassword', [StringComparison]::Ordinal) -ge 0) 'managed 密码读取边界不存在。'
            Assert-Condition ($source.IndexOf('ExpectedProcessId', [StringComparison]::Ordinal) -ge 0) 'ExpectedProcessId 归属边界不存在。'
            'managed 密码读取条件、目标地址和连接后归属复核通过。'
        } -Arguments @($sourceText)
    })

    $results += (Invoke-Case -Id 'TEST-07-S05' -Assertions 7 -Action {
        Invoke-ModuleAction -Action {
            function Assert-Condition {
                param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
                if (-not $Condition) { throw $Message }
            }
            function Assert-Throws {
                param([Parameter(Mandatory)][scriptblock]$Action, [string]$MessagePattern = '*')
                $thrown = $false
                $message = $null
                try { & $Action } catch { $thrown = $true; $message = $_.Exception.Message }
                if (-not $thrown) { throw '预期操作应拒绝，但没有抛出异常。' }
                if ($message -notlike $MessagePattern) { throw ('拒绝原因不匹配：{0}' -f $message) }
            }
            $source = $args[0]
            $stopStart = $source.IndexOf('function Stop-WangbinCollectorRedis', [StringComparison]::Ordinal)
            $stopEnd = $source.IndexOf('function Restart-WangbinCollectorRedis', [StringComparison]::Ordinal)
            $stopText = $source.Substring($stopStart, $stopEnd - $stopStart)
            Assert-Condition ($stopText.IndexOf('Invoke-RedisShutdownSave', [StringComparison]::Ordinal) -lt $stopText.IndexOf("Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('stop", [StringComparison]::Ordinal)) 'STOP 顺序不是先发送 SHUTDOWN 再停止 wrapper。'
            Assert-Condition ($stopText.IndexOf('Test-OwnedListener', [StringComparison]::Ordinal) -ge 0) 'STOP 前缺少 listener 归属检查。'
            Assert-Condition ($stopText.IndexOf('Stop Pending', [StringComparison]::Ordinal) -ge 0) 'STOP 未处理 Stop Pending。'
            Assert-Condition ($source.IndexOf('卸载前目标端口仍有监听者', [StringComparison]::Ordinal) -ge 0) 'UNINSTALL 未检查残留监听者。'
            Assert-Throws -Action { Assert-ManagedRedisLifecycleState -ServiceState 'Stopped' -ResidualProcessCount 1 } -MessagePattern '*仍存在匹配的 Redis 进程*'
            Assert-Throws -Action { Assert-ManagedRedisLifecycleState -ServiceState 'Start Pending' -ResidualProcessCount 0 } -MessagePattern '*过渡状态*'
            Assert-Throws -Action { Assert-ManagedRedisLifecycleState -ServiceState 'Stop Pending' -ResidualProcessCount 0 } -MessagePattern '*过渡状态*'
            'STOP/UNINSTALL 顺序、过渡状态和残留进程拒绝通过。'
        } -Arguments @($sourceText)
    })

    $results += (Invoke-Case -Id 'TEST-07-S06' -Assertions 7 -Action {
        Invoke-ModuleAction -Action {
            function Assert-Condition {
                param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
                if (-not $Condition) { throw $Message }
            }
            function Assert-Throws {
                param([Parameter(Mandatory)][scriptblock]$Action, [string]$MessagePattern = '*')
                $thrown = $false
                $message = $null
                try { & $Action } catch { $thrown = $true; $message = $_.Exception.Message }
                if (-not $thrown) { throw '预期操作应拒绝，但没有抛出异常。' }
                if ($message -notlike $MessagePattern) { throw ('拒绝原因不匹配：{0}' -f $message) }
            }
            $root = $args[0]
            $source = [IO.File]::ReadAllText((Get-Module WangbinCollectorRedis).Path, (New-Object System.Text.UTF8Encoding($true)))
            $fake = Join-Path $root 'missing.exe'
            $license = Join-Path $root 'missing-license.txt'
            $message = $null
            try {
                Install-WangbinCollectorRedis -RedisDistributionArchive $fake -ExpectedRedisArchiveSha256 ('0' * 64) -WinSwExecutable $fake -ExpectedWinSwSha256 ('1' * 64) -RedisSourceDescription 'approved local redis' -WinSwSourceDescription 'approved local winsw' -RedisLicenseFile $license -WinSwLicenseFile $license -Port 59999 | Out-Null
            } catch { $message = $_.Exception.Message }
            Assert-Condition ($message -like '*需要已提升的管理员令牌*') '非管理员 INSTALL 未在副作用前阻断。'
            $moduleObject = Get-Module WangbinCollectorRedis
            $ownershipSetup = {
                function Get-ManagedMarker { param($Layout); $null }
                function Get-ServiceRecordSafe { param($Name); if ($script:MockServiceReadMode -eq 'foreign') { [pscustomobject]@{ Value = [pscustomobject]@{ State = 'Stopped' }; Error = $null } } else { [pscustomobject]@{ Value = $null; Error = 'access denied' } } }
                $script:MockServiceReadMode = 'foreign'
            }
            $moduleObject.SessionState.InvokeCommand.InvokeScript($false, $ownershipSetup, $null, $null) | Out-Null
            $ownership = Get-ManagedOwnership -Layout ([pscustomobject]@{ ServiceName = 'WangbinCollector-Redis' })
            Assert-Condition ($ownership.Ownership -eq 'FOREIGN_SERVICE') '无 marker 的同名服务未识别为 FOREIGN_SERVICE。'
            $moduleObject.SessionState.InvokeCommand.InvokeScript($false, { $script:MockServiceReadMode = 'unknown' }, $null, $null) | Out-Null
            $unknown = Get-ManagedOwnership -Layout ([pscustomobject]@{ ServiceName = 'WangbinCollector-Redis' })
            Assert-Condition ($unknown.Ownership -eq 'OWNERSHIP_UNKNOWN' -and $unknown.Diagnostic -eq 'ScmReadFailed') 'SCM 读取失败被误判为不存在。'
            Assert-Condition ($source.IndexOf('Assert-NonSecretDescription', [StringComparison]::Ordinal) -ge 0) '来源描述敏感信息校验缺失。'
            Assert-Condition ($source.IndexOf('Assert-Sha256', [StringComparison]::Ordinal) -ge 0) '构件 SHA-256 校验缺失。'
            Assert-Condition ($source.IndexOf('InstallLicense', [StringComparison]::Ordinal) -lt 0) '发现未授权下载/安装路径。'
            '非管理员 INSTALL、同名外部服务、SCM UNKNOWN 和构件来源阻断通过。'
        } -Arguments @($testRoot)
    })
} finally {
    $cleanup = $true
    try { [IO.Directory]::Delete($testRoot, $true) } catch { $cleanup = $false }
}

$summary = [ordered]@{
    TestRunner = 'Invoke-WangbinCollectorRedisSecurityTests.ps1'
    PowerShell = $PSVersionTable.PSVersion.ToString()
    Scope = 'NO_SCM_NO_SYSTEM_ACL_NO_SERVICE_LIFECYCLE'
    ScratchCleaned = $cleanup
    Results = @($results)
    Passed = @($results | Where-Object Status -eq 'PASS').Count
    Failed = @($results | Where-Object Status -eq 'FAIL').Count
    RealScmAcceptance = 'BLOCKED'
}
$summary | ConvertTo-Json -Depth 12
if ($summary.Failed -ne 0 -or -not $cleanup) { exit 1 }
exit 0
