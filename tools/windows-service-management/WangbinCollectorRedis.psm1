#requires -Version 5.1
Set-StrictMode -Version Latest
$script:ErrorActionPreference = 'Stop'

$script:ProductName = 'WangbinCollector'
$script:ServiceName = 'WangbinCollector-Redis'
$script:Schema = 3
$script:DefaultPort = 6379
$script:RedisVersion = '8.10.3'
$script:RedisArchitecture = 'x64'
$script:RedisRuntimeFamily = 'MSYS2'
$script:RedisArchiveFileName = 'Redis-8.10.3-Windows-x64-msys2.zip'
$script:RedisDistributionDirectoryName = 'Redis-8.10.3-Windows-x64-msys2'
$script:RedisArchiveSha256 = 'af16df340525b431e9ec8dabfd625d5e7a233f82805b5418819904ea909c447f'
$script:Utf8 = New-Object System.Text.UTF8Encoding($false)
$script:SystemSid = New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18')
$script:AdministratorsSid = New-Object System.Security.Principal.SecurityIdentifier('S-1-5-32-544')

function Test-WindowsPlatform {
    return $env:OS -eq 'Windows_NT'
}

function Assert-WindowsPlatform {
    if (-not (Test-WindowsPlatform)) {
        throw 'WangbinCollector Redis 服务管理仅支持 Windows。'
    }
}

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    try {
        $principal = New-Object Security.Principal.WindowsPrincipal($identity)
        if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
            throw '需要已提升的管理员令牌；本模块不会请求 UAC，也不会降级为普通用户操作。'
        }
    } finally {
        $identity.Dispose()
    }
}

function Enter-WangbinRedisOperationLock {
    $mutex = New-Object System.Threading.Mutex($false, 'Local\WangbinCollector-Redis-Management')
    try {
        try {
            $acquired = $mutex.WaitOne(0)
        } catch [System.Threading.AbandonedMutexException] {
            $acquired = $true
        }
        if (-not $acquired) { throw '另一个 WangbinCollector Redis 运维操作正在执行；拒绝并发操作。' }
        return $mutex
    } catch {
        $mutex.Dispose()
        throw
    }
}

function Exit-WangbinRedisOperationLock {
    param([Parameter(Mandatory)][System.Threading.Mutex]$Mutex)
    try { $Mutex.ReleaseMutex() } finally { $Mutex.Dispose() }
}

function ConvertTo-FullPath {
    param([Parameter(Mandatory)][string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { throw '路径不能为空。' }
    $full = [IO.Path]::GetFullPath($Path)
    if (-not [IO.Path]::IsPathRooted($full)) { throw '必须使用绝对路径。' }
    return $full.TrimEnd('\', '/')
}

function Test-SamePath {
    param([Parameter(Mandatory)][string]$Left, [Parameter(Mandatory)][string]$Right)
    try {
        return [string]::Equals((ConvertTo-FullPath $Left), (ConvertTo-FullPath $Right), [StringComparison]::OrdinalIgnoreCase)
    } catch {
        return $false
    }
}

function ConvertTo-Msys2Path {
    param([Parameter(Mandatory)][string]$Path)
    $windows = ConvertTo-FullPath $Path
    $separator = [char]92
    if ($windows.Length -lt 3 -or $windows[1] -ne ':' -or $windows[2] -ne $separator) { throw ('MSYS2 路径只支持本地盘符路径：{0}。' -f $Path) }
    return ('/{0}/{1}' -f $windows[0].ToString().ToLowerInvariant(), $windows.Substring(3).Replace($separator, [char]47))
}

function Test-CommandLineContainsPath {
    param([Parameter(Mandatory)][string]$CommandLine, [Parameter(Mandatory)][string]$Path, [switch]$AllowRelativeConfig)
    if ([string]::IsNullOrWhiteSpace($CommandLine)) { return $false }
    if ($AllowRelativeConfig -and
        [regex]::IsMatch($CommandLine, '(?i)(?:^|\s)(?:"redis\.conf"|redis\.conf)\s*$')) {
        return $true
    }
    $normalizedCommand = $CommandLine.Replace([char]92, [char]47)
    $normalizedWindows = (ConvertTo-FullPath $Path).Replace([char]92, [char]47)
    $normalizedMsys = ConvertTo-Msys2Path $Path
    foreach ($candidate in @($normalizedWindows, $normalizedMsys)) {
        $start = 0
        while ($start -lt $normalizedCommand.Length) {
            $index = $normalizedCommand.IndexOf($candidate, $start, [StringComparison]::OrdinalIgnoreCase)
            if ($index -lt 0) { break }
            $beforeOk = $index -eq 0 -or [char]::IsWhiteSpace($normalizedCommand[$index - 1]) -or $normalizedCommand[$index - 1] -eq [char]34
            $afterIndex = $index + $candidate.Length
            $afterOk = $afterIndex -eq $normalizedCommand.Length -or [char]::IsWhiteSpace($normalizedCommand[$afterIndex]) -or $normalizedCommand[$afterIndex] -eq [char]34
            if ($beforeOk -and $afterOk) { return $true }
            $start = $index + 1
        }
    }
    return $false
}

function Assert-NoReparsePoint {
    param([Parameter(Mandatory)][string]$Path)
    $current = ConvertTo-FullPath $Path
    while (-not [string]::IsNullOrEmpty($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw ('拒绝使用重解析路径：{0}。' -f $current)
            }
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrEmpty($parent) -or $parent -eq $current) { break }
        $current = $parent.TrimEnd('\', '/')
    }
}

function Assert-ServiceName {
    param([Parameter(Mandatory)][string]$Name)
    if ($Name -cne $script:ServiceName -or $Name -notmatch '^[A-Za-z][A-Za-z0-9._-]{0,127}$') {
        throw ('服务名不是本产品固定标识：{0}。' -f $Name)
    }
}

function Get-WangbinCollectorRedisLayout {
    [CmdletBinding()]
    param(
        [string]$ProgramFilesRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles),
        [string]$ProgramDataRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData),
        [ValidateRange(1, 65535)][int]$Port = $script:DefaultPort
    )
    Assert-WindowsPlatform
    $programFiles = ConvertTo-FullPath $ProgramFilesRoot
    $programData = ConvertTo-FullPath $ProgramDataRoot
    $productCodeParent = Join-Path $programFiles $script:ProductName
    $productCode = Join-Path $productCodeParent 'Redis'
    $codeRoot = Join-Path $productCode 'bin'
    $productDataParent = Join-Path $programData $script:ProductName
    $productData = Join-Path $productDataParent 'Redis'
    $dataRoot = $productData
    [pscustomobject][ordered]@{
        ProductName = $script:ProductName
        ServiceName = $script:ServiceName
        Port = $Port
        ProductCodeParentRoot = $productCodeParent
        ProductCodeRoot = $productCode
        CodeRoot = $codeRoot
        ProductDataParentRoot = $productDataParent
        ProductDataRoot = $productData
        DataRoot = $dataRoot
        ConfigRoot = Join-Path $dataRoot 'config'
        RedisConfig = Join-Path $dataRoot 'config\redis.conf'
        RedisExecutable = Join-Path $codeRoot 'redis-server.exe'
        WrapperExecutable = Join-Path $codeRoot ($script:ServiceName + '.exe')
        WrapperXml = Join-Path $codeRoot ($script:ServiceName + '.xml')
        RedisDataRoot = Join-Path $dataRoot 'data'
        RedisLogRoot = Join-Path $dataRoot 'logs'
        StateRoot = Join-Path $dataRoot 'state'
        MarkerFile = Join-Path $dataRoot 'state\ownership.json'
        SecretFile = Join-Path $dataRoot 'state\redis.secret.json'
        WrapperLog = Join-Path $dataRoot ('logs\' + $script:ServiceName + '.wrapper.log')
        RedisLog = Join-Path $dataRoot 'logs\redis.log'
    }
}

function Get-ScPath {
    Assert-WindowsPlatform
    return (Join-Path $env:SystemRoot 'System32\sc.exe')
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Path)
    if (-not [IO.File]::Exists($Path)) { throw ('文件不存在：{0}。' -f $Path) }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
}

function Assert-Sha256 {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Expected
    )
    if ($Expected -notmatch '^[a-fA-F0-9]{64}$') { throw 'SHA-256 必须是 64 位十六进制值。' }
    $actual = Get-Sha256 $Path
    if ($actual -cne $Expected.ToLowerInvariant()) {
        throw ('文件 SHA-256 不匹配：{0}。' -f $Path)
    }
    return $actual
}

function Assert-NonSecretDescription {
    param([Parameter(Mandatory)][string]$Description)
    if ($Description -match '(?i)(password|passwd|token|api[\s_-]?key|secret|credential)\s*[:=]' -or $Description -match '(?i)://[^/\s:]+:[^@\s]+@') {
        throw '构件来源说明不得包含密码、Token、API key、凭据或带认证信息的 URL。'
    }
}

function Get-RedisDistributionContract {
    return [ordered]@{
        'redis-server.exe' = [pscustomobject]@{ Required = $true; Kind = 'RedisServer'; Architecture = 'x64' }
        'redis-sentinel.exe' = [pscustomobject]@{ Required = $true; Kind = 'RedisSentinel'; Architecture = 'x64' }
        'redis-cli.exe' = [pscustomobject]@{ Required = $true; Kind = 'RedisCli'; Architecture = 'x64' }
        'redis-benchmark.exe' = [pscustomobject]@{ Required = $true; Kind = 'RedisBenchmark'; Architecture = 'x64' }
        'redis-check-aof.exe' = [pscustomobject]@{ Required = $true; Kind = 'RedisCheckAof'; Architecture = 'x64' }
        'redis-check-rdb.exe' = [pscustomobject]@{ Required = $true; Kind = 'RedisCheckRdb'; Architecture = 'x64' }
        'redis.conf' = [pscustomobject]@{ Required = $true; Kind = 'RedisConfig'; Architecture = $null }
        'sentinel.conf' = [pscustomobject]@{ Required = $true; Kind = 'SentinelConfig'; Architecture = $null }
        'README.md' = [pscustomobject]@{ Required = $true; Kind = 'Documentation'; Architecture = $null }
        'README.zh_CN.md' = [pscustomobject]@{ Required = $true; Kind = 'Documentation'; Architecture = $null }
        'start.bat' = [pscustomobject]@{ Required = $true; Kind = 'Launcher'; Architecture = $null }
        'msys-2.0.dll' = [pscustomobject]@{ Required = $true; Kind = 'MSYS2Runtime'; Architecture = 'x64' }
        'msys-crypto-3.dll' = [pscustomobject]@{ Required = $true; Kind = 'MSYS2Runtime'; Architecture = 'x64' }
        'msys-ssl-3.dll' = [pscustomobject]@{ Required = $true; Kind = 'MSYS2Runtime'; Architecture = 'x64' }
        'msys-gcc_s-seh-1.dll' = [pscustomobject]@{ Required = $true; Kind = 'MSYS2Runtime'; Architecture = 'x64' }
        'msys-stdc++-6.dll' = [pscustomobject]@{ Required = $true; Kind = 'MSYS2Runtime'; Architecture = 'x64' }
    }
}

function Get-PeArchitecture {
    param([Parameter(Mandatory)][string]$Path)
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 0x40 -or $bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a) { return 'NOT_PE' }
    $peOffset = [BitConverter]::ToInt32($bytes, 0x3c)
    if ($peOffset -lt 0 -or $peOffset + 6 -gt $bytes.Length -or $bytes[$peOffset] -ne 0x50 -or $bytes[$peOffset + 1] -ne 0x45) { return 'NOT_PE' }
    $machine = [BitConverter]::ToUInt16($bytes, $peOffset + 4)
    switch ($machine) {
        0x8664 { return 'x64' }
        0x014c { return 'x86' }
        0xaa64 { return 'ARM64' }
        default { return ('UNKNOWN_{0:X4}' -f $machine) }
    }
}

function ConvertTo-ZipEntryPath {
    param([Parameter(Mandatory)][string]$Name, [switch]$AllowDirectory)
    if ([string]::IsNullOrWhiteSpace($Name)) { throw ('ZIP 条目不是受支持路径：{0}。' -f $Name) }
    if ($Name.IndexOf([char]92) -ge 0) { throw ('拒绝 ZIP 反斜杠路径：{0}。' -f $Name) }
    $isDirectory = $Name.EndsWith('/')
    if ($isDirectory -and -not $AllowDirectory) { throw ('ZIP 条目不是受支持文件：{0}。' -f $Name) }
    $normalized = if ($isDirectory) { $Name.Substring(0, $Name.Length - 1) } else { $Name }
    if ($normalized.StartsWith('/') -or $normalized -match '^[A-Za-z]:') { throw ('拒绝 ZIP 绝对路径：{0}。' -f $Name) }
    $parts = $normalized -split '/'
    if ($parts | Where-Object { $_ -eq '' -or $_ -eq '.' -or $_ -eq '..' -or $_ -match ':|[\x00-\x1f]' }) { throw ('拒绝 ZIP 路径穿越或非法条目：{0}。' -f $Name) }
    if ($parts.Count -lt 1 -or $parts.Count -gt 2) { throw ('Redis 发行包不允许嵌套路径：{0}。' -f $Name) }
    if ($isDirectory -and $parts.Count -ne 1) { throw ('Redis 发行包只允许一个顶层发行目录：{0}。' -f $Name) }
    if ($isDirectory) { return ($normalized + '/') }
    return $normalized
}

function Assert-ZipEntrySafe {
    param([Parameter(Mandatory)]$Entry, [Parameter(Mandatory)][string]$ArchivePath)
    $isDirectory = ([string]$Entry.FullName).EndsWith('/')
    $normalized = ConvertTo-ZipEntryPath -Name ([string]$Entry.FullName) -AllowDirectory:$isDirectory
    if ([string]$Entry.FullName -cne $normalized) { throw ('ZIP 条目路径未规范化：{0}。' -f $Entry.FullName) }
    if ($isDirectory) {
        if ($normalized -cne ($script:RedisDistributionDirectoryName + '/')) { throw ('ZIP 顶层目录名称不匹配：{0}。' -f $Entry.FullName) }
        if ($Entry.Length -ne 0) { throw ('ZIP 目录条目大小不为零：{0}。' -f $Entry.FullName) }
    } elseif ($Entry.Length -le 0 -or $Entry.Length -gt 1073741824) {
        throw ('ZIP 条目大小不受支持：{0}。' -f $Entry.FullName)
    }
    $archiveRoot = [IO.Path]::GetDirectoryName((ConvertTo-FullPath $ArchivePath))
    Assert-NoReparsePoint $archiveRoot
    return [pscustomobject]@{ IsDirectory = $isDirectory; Path = $normalized }
}

function Assert-RedisDistributionArchive {
    param(
        [Parameter(Mandatory)][string]$ArchivePath,
        [Parameter(Mandatory)][string]$ExpectedSha256,
        [switch]$SkipFixedCandidateCheck
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = ConvertTo-FullPath $ArchivePath
    Assert-NoReparsePoint $archive
    if (-not [IO.File]::Exists($archive)) { throw ('Redis 完整发行包不存在：{0}。' -f $archive) }
    $archiveHash = Assert-Sha256 -Path $archive -Expected $ExpectedSha256
    if (-not $SkipFixedCandidateCheck -and ($archiveHash -cne $script:RedisArchiveSha256 -or [IO.Path]::GetFileName($archive) -cne $script:RedisArchiveFileName)) { throw 'Redis 发行包不是已批准的 Redis 8.10.3 MSYS2 候选。' }
    $contract = Get-RedisDistributionContract
    $entries = New-Object -TypeName 'System.Collections.Generic.Dictionary[string,object]' -ArgumentList ([StringComparer]::OrdinalIgnoreCase)
    $rootDirectorySeen = $false
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        foreach ($entry in $zip.Entries) {
            $safeEntry = Assert-ZipEntrySafe -Entry $entry -ArchivePath $archive
            if ($safeEntry.IsDirectory) {
                if ($rootDirectorySeen) { throw 'Redis 发行包包含重复顶层目录条目。' }
                $rootDirectorySeen = $true
                continue
            }
            $rootPrefix = $script:RedisDistributionDirectoryName + '/'
            if (-not $safeEntry.Path.StartsWith($rootPrefix, [StringComparison]::Ordinal)) { throw ('Redis 发行包文件不在固定顶层目录：{0}。' -f $entry.FullName) }
            $name = $safeEntry.Path.Substring($rootPrefix.Length)
            if ($name.IndexOf('/') -ge 0) { throw ('Redis 发行包不允许嵌套文件目录：{0}。' -f $entry.FullName) }
            $canonical = @($contract.Keys | Where-Object { $_ -ceq $name })
            if ($canonical.Count -ne 1) { throw ('Redis 发行包包含未批准文件：{0}。' -f $name) }
            if ($entries.ContainsKey($name)) { throw ('Redis 发行包包含重复文件：{0}。' -f $name) }
            $entries.Add($name, [pscustomobject]@{ Name = $name; EntryPath = $safeEntry.Path; Length = $entry.Length; Sha256 = $null; Kind = $contract[$name].Kind; Architecture = $contract[$name].Architecture })
            if ($contract[$name].Architecture -in @('x64','x86','ARM64')) {
                $temporary = [IO.Path]::GetTempFileName()
                try {
                    $stream = $entry.Open(); $output = [IO.File]::Open($temporary, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
                    try { $stream.CopyTo($output) } finally { $output.Dispose(); $stream.Dispose() }
                    $actualArchitecture = Get-PeArchitecture -Path $temporary
                    if ($actualArchitecture -ne $contract[$name].Architecture) { throw ('Redis 构件架构不匹配：{0}={1}。' -f $name, $actualArchitecture) }
                    $entries[$name].Sha256 = Get-Sha256 $temporary
                } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
            }
        }
    } finally { $zip.Dispose() }
    if (-not $rootDirectorySeen) { throw ('Redis 发行包缺少固定顶层目录：{0}。' -f $script:RedisDistributionDirectoryName) }
    foreach ($name in $contract.Keys) { if ($contract[$name].Required -and -not $entries.ContainsKey($name)) { throw ('Redis 发行包缺少必需文件：{0}。' -f $name) } }
    [pscustomobject][ordered]@{ ArchivePath = $archive; ArchiveSha256 = $archiveHash; Version = $script:RedisVersion; Architecture = $script:RedisArchitecture; RuntimeFamily = $script:RedisRuntimeFamily; Files = @($entries.Values | Sort-Object Name) }
}

function Copy-RedisDistribution {
    param([Parameter(Mandatory)]$Distribution, [Parameter(Mandatory)][string]$ArchivePath, [Parameter(Mandatory)][string]$DestinationRoot, [string]$ServiceSid)
    $copyStage = 'OPEN_ARCHIVE'
    $archive = [IO.Compression.ZipFile]::OpenRead((ConvertTo-FullPath $ArchivePath))
    $copied = New-Object System.Collections.Generic.List[object]
    try {
        $copyStage = 'ENUMERATE_FILES'
        $files = @($Distribution.Files | Sort-Object Name)
        $copyStage = 'COPY_FILES'
        foreach ($file in $files) {
            try {
                $expectedEntryPath = $script:RedisDistributionDirectoryName + '/' + [string]$file.Name
                if ([string]$file.EntryPath -cne $expectedEntryPath) { throw ('Redis 发行包条目路径不匹配：{0}。' -f $file.Name) }
                $entry = $archive.GetEntry($expectedEntryPath)
                if ($null -eq $entry) { throw ('Redis 发行包条目读回失败：{0}。' -f $expectedEntryPath) }
                $target = Join-Path $DestinationRoot $file.Name
                Assert-NoReparsePoint $target
                $input = $entry.Open()
                $output = [IO.File]::Open($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
                try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
                if ([string]::IsNullOrWhiteSpace($ServiceSid)) {
                    $null = Set-ControlledFileAcl -Path $target -Mode Code
                } else {
                    $null = Set-ControlledFileAcl -Path $target -Mode Code -ServiceSid $ServiceSid
                }
                $actual = Get-Sha256 $target
                if ($actual -cne [string]$file.Sha256 -and [string]$file.Name -notin @('redis.conf', 'sentinel.conf', 'README.md', 'README.zh_CN.md', 'start.bat')) { throw ('Redis 发行文件复制后哈希不匹配：{0}。' -f $file.Name) }
                [void]$copied.Add([pscustomobject]@{ Name = $file.Name; Path = $target; Sha256 = $actual; Length = ([IO.FileInfo]$target).Length; Kind = $file.Kind; Architecture = $file.Architecture })
            } catch {
                throw ('Redis 发行文件 {0} 复制或初始 ACL 失败：{1}' -f [string]$file.Name, $_.Exception.Message)
            }
        }
        $copyStage = 'RETURN_RESULT'
        return @($copied.ToArray())
    } catch {
        throw ('Redis 发行包复制阶段 {0} 失败：{1}' -f $copyStage, $_.Exception.Message)
    } finally { $archive.Dispose() }
}

function Assert-MarkerDistribution {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)]$Marker)
    if ([string]$Marker.RedisVersion -cne $script:RedisVersion -or [string]$Marker.RedisArchitecture -cne $script:RedisArchitecture -or [string]$Marker.RedisRuntimeFamily -cne $script:RedisRuntimeFamily) { throw 'marker 中的 Redis 版本、架构或运行时族不匹配。' }
    if ([string]$Marker.RedisArchiveSha256 -cne $script:RedisArchiveSha256) { throw 'marker 中的 Redis 发行包哈希不是固定候选。' }
    $contract = Get-RedisDistributionContract
    $files = @($Marker.RedisDistributionFiles)
    if ($files.Count -ne $contract.Count) { throw 'marker 构件清单数量不匹配。' }
    foreach ($name in $contract.Keys) {
        $entry = @($files | Where-Object { [string]$_.Name -ceq $name })
        if ($entry.Count -ne 1) { throw ('marker 构件清单缺少或重复文件：{0}。' -f $name) }
        $target = Join-Path $Layout.CodeRoot $name
        if (-not (Test-SamePath $entry[0].Path $target)) { throw ('marker 构件路径重定向：{0}。' -f $name) }
        if ($entry[0].PSObject.Properties.Name -contains 'EntryPath' -and [string]$entry[0].EntryPath -cne ($script:RedisDistributionDirectoryName + '/' + $name)) { throw ('marker ZIP 条目路径重定向：{0}。' -f $name) }
        Assert-MarkerArtifact -Marker ([pscustomobject]@{ ArtifactSha256 = $entry[0].Sha256 }) -Path $target -HashProperty ArtifactSha256
        if ([string]$entry[0].Architecture -in @('x64','x86','ARM64') -and (Get-PeArchitecture $target) -ne [string]$entry[0].Architecture) { throw ('磁盘构件架构漂移：{0}。' -f $name) }
    }
}

function Get-FileVersionText {
    param([Parameter(Mandatory)][string]$Path)
    try {
        return [Diagnostics.FileVersionInfo]::GetVersionInfo($Path).FileVersion
    } catch {
        return $null
    }
}

function Get-ServiceRecord {
    param([Parameter(Mandatory)][string]$Name)
    Assert-ServiceName $Name
    try {
        $record = Get-CimInstance -ClassName Win32_Service -Filter ("Name='{0}'" -f $Name) -OperationTimeoutSec 2 -ErrorAction Stop
        if ($null -eq $record) { return $null }
        return $record
    } catch {
        throw ('无法读取 SCM 服务 {0}；当前状态为 UNKNOWN，禁止猜测或执行副作用操作。' -f $Name)
    }
}

function Get-ServiceRecordSafe {
    param([Parameter(Mandatory)][string]$Name)
    try {
        return [pscustomobject]@{ Value = Get-ServiceRecord $Name; Error = $null }
    } catch {
        return [pscustomobject]@{ Value = $null; Error = $_.Exception.Message }
    }
}

function Get-ServiceRegistryKey {
    param([Parameter(Mandatory)][string]$Name)
    Assert-ServiceName $Name
    return ('HKLM:\SYSTEM\CurrentControlSet\Services\{0}' -f $Name)
}

function Get-ServiceRegistrySnapshot {
    param([Parameter(Mandatory)][string]$Name)
    $keyPath = Get-ServiceRegistryKey $Name
    if (-not (Test-Path -LiteralPath $keyPath)) { return $null }
    $item = Get-ItemProperty -LiteralPath $keyPath -ErrorAction Stop
    $dependencies = @()
    if ($item.PSObject.Properties.Name -contains 'DependOnService') { $dependencies = @($item.DependOnService) }
    [pscustomobject]@{
        ServiceSidType = [int]$item.ServiceSidType
        Start = [int]$item.Start
        DependOnService = $dependencies
        FailureActions = $item.FailureActions
    }
}

function Get-ServiceSid {
    param([Parameter(Mandatory)][string]$Name)
    Assert-ServiceName $Name
    $account = New-Object System.Security.Principal.NTAccount('NT SERVICE', $Name)
    try {
        return $account.Translate([Security.Principal.SecurityIdentifier]).Value
    } catch {
        throw ('无法解析本产品服务 SID：{0}。' -f $Name)
    }
}

function New-DirectorySecurity {
    param(
        [Parameter(Mandatory)][ValidateSet('Code', 'Traverse', 'Read', 'Write', 'Admin')][string]$Mode,
        [string]$ServiceSid
    )
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($script:SystemSid)
    $inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    $none = [Security.AccessControl.PropagationFlags]::None
    $noRights = [Security.AccessControl.FileSystemRights]0
    foreach ($sid in @($script:SystemSid, $script:AdministratorsSid)) {
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid, [Security.AccessControl.FileSystemRights]::FullControl, $inherit, $none, [Security.AccessControl.AccessControlType]::Allow)))
    }
    if (-not [string]::IsNullOrWhiteSpace($ServiceSid)) {
        $service = New-Object Security.Principal.SecurityIdentifier($ServiceSid)
        switch ($Mode) {
            Code { $rights = [Security.AccessControl.FileSystemRights]::ReadAndExecute }
            Traverse { $rights = [Security.AccessControl.FileSystemRights]'Traverse, ReadAttributes, Synchronize' }
            Read { $rights = [Security.AccessControl.FileSystemRights]::ReadAndExecute }
            Write { $rights = [Security.AccessControl.FileSystemRights]::Modify }
            Admin { $rights = $noRights }
        }
        if ($rights -ne $noRights) {
            $inheritance = [Security.AccessControl.InheritanceFlags]::None
            if ($Mode -in @('Code', 'Read', 'Write')) { $inheritance = $inherit }
            $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($service, $rights, $inheritance, $none, [Security.AccessControl.AccessControlType]::Allow)))
        }
    }
    return $acl
}

function New-FileSecurity {
    param(
        [Parameter(Mandatory)][ValidateSet('Code', 'Read', 'Write', 'Admin')][string]$Mode,
        [string]$ServiceSid,
        [switch]$PreserveOwner
    )
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    if (-not $PreserveOwner) { $acl.SetOwner($script:SystemSid) }
    foreach ($sid in @($script:SystemSid, $script:AdministratorsSid)) {
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid, [Security.AccessControl.FileSystemRights]::FullControl, [Security.AccessControl.InheritanceFlags]::None, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)))
    }
    if (-not [string]::IsNullOrWhiteSpace($ServiceSid) -and $Mode -ne 'Admin') {
        $service = New-Object Security.Principal.SecurityIdentifier($ServiceSid)
        $rights = [Security.AccessControl.FileSystemRights]::Read
        if ($Mode -eq 'Code') { $rights = [Security.AccessControl.FileSystemRights]::ReadAndExecute }
        if ($Mode -eq 'Write') { $rights = [Security.AccessControl.FileSystemRights]::Modify }
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($service, $rights, [Security.AccessControl.InheritanceFlags]::None, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)))
    }
    return $acl
}

function Set-ControlledDirectoryAcl {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Mode, [string]$ServiceSid)
    Assert-NoReparsePoint $Path
    if ([string]::IsNullOrWhiteSpace($ServiceSid)) {
        $acl = New-DirectorySecurity -Mode $Mode
    } else {
        $acl = New-DirectorySecurity -Mode $Mode -ServiceSid $ServiceSid
    }
    Set-Acl -LiteralPath $Path -AclObject $acl -ErrorAction Stop
}

function Set-ControlledFileAcl {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Mode, [string]$ServiceSid)
    Assert-NoReparsePoint $Path
    if ([string]::IsNullOrWhiteSpace($ServiceSid)) {
        $acl = New-FileSecurity -Mode $Mode -PreserveOwner
    } else {
        $acl = New-FileSecurity -Mode $Mode -ServiceSid $ServiceSid -PreserveOwner
    }
    Set-Acl -LiteralPath $Path -AclObject $acl -ErrorAction Stop
    $icacls = Join-Path $env:SystemRoot 'System32\icacls.exe'
    Invoke-NativeProcess -FilePath $icacls -Arguments ('"{0}" /setowner "NT AUTHORITY\SYSTEM"' -f $Path) -Deadline ([datetime]::UtcNow.AddSeconds(10))
    $owner = (Get-Acl -LiteralPath $Path -ErrorAction Stop).GetOwner([Security.Principal.SecurityIdentifier]).Value
    if ($owner -cne $script:SystemSid.Value) { throw ('受控文件所有者设置后不匹配：{0}' -f $Path) }
}

function Get-AccessRuleSignature {
    param([Parameter(Mandatory)]$Security)
    $rules = @($Security.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    return @($rules | ForEach-Object {
        '{0}|{1}|{2}|{3}|{4}|{5}' -f $_.IdentityReference.Value, [int]$_.AccessControlType, [long]$_.FileSystemRights, [int]$_.InheritanceFlags, [int]$_.PropagationFlags, [bool]$_.IsInherited
    } | Sort-Object)
}

function Assert-ControlledDirectoryAcl {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][ValidateSet('Code', 'Traverse', 'Read', 'Write', 'Admin')][string]$Mode, [string]$ServiceSid)
    Assert-NoReparsePoint $Path
    if (-not [IO.Directory]::Exists($Path)) { throw ('受保护目录不存在：{0}。' -f $Path) }
    $actual = Get-Acl -LiteralPath $Path -ErrorAction Stop
    if (-not $actual.AreAccessRulesProtected -or $actual.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $script:SystemSid.Value) {
        throw ('受保护目录所有者或继承保护不匹配：{0}。' -f $Path)
    }
    $expected = New-DirectorySecurity -Mode $Mode -ServiceSid $ServiceSid
    $actualRules = Get-AccessRuleSignature -Security $actual
    $expectedRules = Get-AccessRuleSignature -Security $expected
    if (($actualRules -join "`n") -cne ($expectedRules -join "`n")) {
        throw ('受保护目录 ACL 不匹配：{0}。' -f $Path)
    }
}

function Assert-ControlledFileAcl {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][ValidateSet('Code', 'Read', 'Write', 'Admin')][string]$Mode, [string]$ServiceSid)
    Assert-NoReparsePoint $Path
    if (-not [IO.File]::Exists($Path)) { throw ('受保护文件不存在：{0}。' -f $Path) }
    $actual = Get-Acl -LiteralPath $Path -ErrorAction Stop
    if (-not $actual.AreAccessRulesProtected -or $actual.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $script:SystemSid.Value) {
        throw ('受保护文件所有者或继承保护不匹配：{0}。' -f $Path)
    }
    $expected = New-FileSecurity -Mode $Mode -ServiceSid $ServiceSid
    $actualRules = Get-AccessRuleSignature -Security $actual
    $expectedRules = Get-AccessRuleSignature -Security $expected
    if (($actualRules -join "`n") -cne ($expectedRules -join "`n")) {
        throw ('受保护文件 ACL 不匹配：{0}。' -f $Path)
    }
}

function Assert-ManagedArtifactsAcl {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)][string]$ServiceSid)
    foreach ($entry in @(
        @($Layout.ProductCodeRoot, 'Code'), @($Layout.CodeRoot, 'Code'),
        @($Layout.ProductDataRoot, 'Traverse'), @($Layout.DataRoot, 'Traverse'),
        @($Layout.ConfigRoot, 'Read'), @($Layout.RedisDataRoot, 'Write'),
        @($Layout.RedisLogRoot, 'Write'), @($Layout.StateRoot, 'Admin')
    )) { Assert-ControlledDirectoryAcl -Path $entry[0] -Mode $entry[1] -ServiceSid $ServiceSid }
    foreach ($entry in @(
        @($Layout.RedisExecutable, 'Code'), @($Layout.WrapperExecutable, 'Code'),
        @($Layout.WrapperXml, 'Code'), @($Layout.RedisConfig, 'Read'),
        @($Layout.SecretFile, 'Admin'), @($Layout.MarkerFile, 'Admin')
    )) { Assert-ControlledFileAcl -Path $entry[0] -Mode $entry[1] -ServiceSid $ServiceSid }
}

function New-ControlledDirectory {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Mode, [string]$ServiceSid)
    Assert-NoReparsePoint $Path
    if (Test-Path -LiteralPath $Path) { throw ('目标目录已经存在，拒绝接管：{0}。' -f $Path) }
    $parent = [IO.Path]::GetDirectoryName((ConvertTo-FullPath $Path))
    if (-not [IO.Directory]::Exists($parent)) { throw ('父目录不存在，拒绝递归创建未知目录：{0}。' -f $parent) }
    [IO.Directory]::CreateDirectory($Path) | Out-Null
    Set-ControlledDirectoryAcl -Path $Path -Mode $Mode -ServiceSid $ServiceSid
}

function Write-ControlledText {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Mode,
        [string]$ServiceSid,
        [switch]$Replace
    )
    Assert-NoReparsePoint $Path
    if (-not $Replace -and [IO.File]::Exists($Path)) { throw ('拒绝覆盖已有文件：{0}。' -f $Path) }
    $parent = [IO.Path]::GetDirectoryName((ConvertTo-FullPath $Path))
    if (-not [IO.Directory]::Exists($parent)) { throw ('目标文件父目录不存在：{0}。' -f $parent) }
    $temporary = $Path + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllText($temporary, $Text, $script:Utf8)
        Set-ControlledFileAcl -Path $temporary -Mode $Mode -ServiceSid $ServiceSid
        if ($Replace -and [IO.File]::Exists($Path)) {
            [IO.File]::Replace($temporary, $Path, [System.Management.Automation.Language.NullString]::Value)
        } else {
            [IO.File]::Move($temporary, $Path)
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Read-JsonFile {
    param([Parameter(Mandatory)][string]$Path)
    Assert-NoReparsePoint $Path
    if (-not [IO.File]::Exists($Path)) { return $null }
    try {
        return [IO.File]::ReadAllText($Path, $script:Utf8) | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw ('受控 JSON 文件无法解析：{0}。' -f $Path)
    }
}

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
        return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
        $rng.Dispose()
    }
}

function Convert-SecureStringToPlainText {
    param([Parameter(Mandatory)][Security.SecureString]$SecureString)
    $handle = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($handle)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($handle)
    }
}

function Get-RemainingMilliseconds {
    param([Parameter(Mandatory)][datetime]$Deadline, [int]$Maximum = 2000)
    $remaining = [math]::Floor(($Deadline - [datetime]::UtcNow).TotalMilliseconds)
    if ($remaining -lt 1) { throw '操作 deadline 已到。' }
    return [int][math]::Min($remaining, $Maximum)
}

function Invoke-NativeProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string]$Arguments,
        [Parameter(Mandatory)][datetime]$Deadline,
        [switch]$CaptureOutput
    )
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $FilePath
    $info.Arguments = $Arguments
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw ('无法启动受控运维请求：{0}。' -f $FilePath) }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit((Get-RemainingMilliseconds -Deadline $Deadline -Maximum 30000))) {
            try { $process.Kill() } catch { }
            throw ('受控运维请求超时：{0}；服务状态需要重新读取。' -f [IO.Path]::GetFileName($FilePath))
        }
        $outText = $stdout.GetAwaiter().GetResult()
        $errText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw ('受控运维请求失败：{0}，退出码 {1}。' -f [IO.Path]::GetFileName($FilePath), $process.ExitCode)
        }
        if ($CaptureOutput) { return $outText }
        return
    } finally {
        $process.Dispose()
    }
}

function Get-ServiceCommandTail {
    param([Parameter(Mandatory)][string]$CommandLine, [Parameter(Mandatory)][string]$Executable)
    if ([string]::IsNullOrWhiteSpace($CommandLine)) { return $null }
    $actual = $CommandLine.Trim()
    $expectedPath = (ConvertTo-FullPath $Executable).Replace('/', '\')
    $expectedTail = $null
    $prefixes = @(
        ('"' + $expectedPath + '"')
        $expectedPath
    )
    foreach ($prefix in $prefixes) {
        if ($actual.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            $remainder = $actual.Substring($prefix.Length).Trim()
            if ([string]::IsNullOrEmpty($remainder)) { $expectedTail = '' } else { $expectedTail = $remainder }
            break
        }
    }
    return [pscustomobject]@{ Tail = $expectedTail }
}

function Test-ServicePath {
    param([Parameter(Mandatory)][string]$Actual, [Parameter(Mandatory)][string]$Expected, [string]$ExpectedTail = '')
    $parsed = Get-ServiceCommandTail -CommandLine $Actual -Executable $Expected
    if ($null -eq $parsed) { return $false }
    $actualTail = if ($null -eq $parsed.Tail) { '' } else { [string]$parsed.Tail }
    $expectedTailValue = if ($null -eq $ExpectedTail) { '' } else { [string]$ExpectedTail }
    return $actualTail.Replace('/', '\') -ceq $expectedTailValue.Replace('/', '\')
}

function Get-ProcessRecord {
    param([Parameter(Mandatory)][int]$ProcessId)
    if ($ProcessId -le 0) { return $null }
    return Get-CimInstance Win32_Process -Filter ('ProcessId={0}' -f $ProcessId) -OperationTimeoutSec 2 -ErrorAction Stop
}

function Get-ListenerRecord {
    param([Parameter(Mandatory)][int]$Port)
    try {
        return @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object {
            [int]$_.LocalPort -eq $Port -and $_.LocalAddress -in @('127.0.0.1', '::1', '0.0.0.0', '::')
        })
    } catch {
        throw '无法读取 TCP 监听者；归属状态为 UNKNOWN，拒绝生命周期操作。'
    }
}

function Assert-PortVacant {
    param([Parameter(Mandatory)][int]$Port)
    $socket = New-Object Net.Sockets.Socket([Net.Sockets.AddressFamily]::InterNetwork, [Net.Sockets.SocketType]::Stream, [Net.Sockets.ProtocolType]::Tcp)
    try {
        $socket.ExclusiveAddressUse = $true
        $socket.Bind((New-Object Net.IPEndPoint([Net.IPAddress]::Loopback, $Port)))
        $socket.Listen(1)
    } catch {
        throw ('127.0.0.1:{0} 已被占用或无法完成独占检查；不会接管或终止监听者。' -f $Port)
    } finally {
        $socket.Dispose()
    }
}

function ConvertFrom-FailureActions {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    $bytes = $Bytes
    if ($bytes.Length -lt 20) { return $null }
    $reset = [BitConverter]::ToInt32($bytes, 0)
    $count = [BitConverter]::ToInt32($bytes, 12)
    $actionsOffset = [BitConverter]::ToInt32($bytes, 16)
    if ($actionsOffset -ne 20) { return $null }
    if ($count -lt 0 -or $count -gt 128) { return $null }
    $offset = $actionsOffset
    if ($bytes.Length -ne ($offset + ($count * 8))) { return $null }
    $actions = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $count; $i++) {
        $base = $offset + ($i * 8)
        $actionType = [BitConverter]::ToInt32($bytes, $base)
        $delayMilliseconds = [BitConverter]::ToInt32($bytes, $base + 4)
        if ($actionType -notin @(0, 1) -or $delayMilliseconds -lt 0 -or ($actionType -eq 0 -and $delayMilliseconds -ne 0)) { return $null }
        $actions.Add(('{0}:{1}' -f $actionType, $delayMilliseconds))
    }
    [pscustomobject]@{ ResetSeconds = $reset; Actions = $actions.ToArray() }
}

function Get-FailureActionSummary {
    param([Parameter(Mandatory)][string]$Name)
    $snapshot = Get-ServiceRegistrySnapshot $Name
    if ($null -eq $snapshot -or $null -eq $snapshot.FailureActions) { return $null }
    return ConvertFrom-FailureActions -Bytes ([byte[]]$snapshot.FailureActions)
}

function Assert-ServiceDacl {
    param([Parameter(Mandatory)][string]$Name)
    $sddlText = Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('sdshow "{0}"' -f $Name) -Deadline ([datetime]::UtcNow.AddSeconds(5)) -CaptureOutput
    $sddl = @($sddlText -split '\r?\n' | Where-Object { $_.Trim().StartsWith('D:') })
    if ($sddl.Count -ne 1) { throw '服务对象 DACL 无法唯一解析。' }
    try {
        $security = New-Object Security.AccessControl.RawSecurityDescriptor($sddl[0].Trim())
    } catch {
        throw '服务对象 DACL 不是有效的安全描述符。'
    }
    if (($security.ControlFlags -band [Security.AccessControl.ControlFlags]::DiscretionaryAclProtected) -eq 0 -or $null -eq $security.DiscretionaryAcl) {
        throw '服务对象 DACL 未启用保护或不存在。'
    }
    $seen = New-Object System.Collections.Generic.List[string]
    foreach ($ace in $security.DiscretionaryAcl) {
        if ($ace.AceType -ne [Security.AccessControl.AceType]::AccessAllowed -or $ace.SecurityIdentifier.Value -notin @('S-1-5-18', 'S-1-5-32-544')) {
            throw '服务对象存在非本产品管理员控制授权。'
        }
        if (($ace.AccessMask -band 0xF01FF) -ne 0xF01FF -and ($ace.AccessMask -band 0x10000000) -eq 0) {
            throw '服务管理员控制权限不完整。'
        }
        $seen.Add($ace.SecurityIdentifier.Value)
    }
    if ($seen.Count -ne 2 -or $seen -notcontains 'S-1-5-18' -or $seen -notcontains 'S-1-5-32-544') {
        throw '服务对象 DACL 不匹配。'
    }
}

function Assert-ServiceConfiguration {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)]$Service)
    # WinSW v2.12.0 registers the SCM ImagePath as the quoted wrapper path;
    # the //RS// service-dispatch tail is used by the wrapper process and is
    # not persisted in the service's ImagePath on this host.
    $expectedServiceTail = ''
    if (-not (Test-ServicePath -Actual $Service.PathName -Expected $Layout.WrapperExecutable -ExpectedTail $expectedServiceTail)) {
        throw ('SCM wrapper 完整命令行不是本产品固定服务调用；实际路径：{0}；期望可执行文件：{1}；期望尾部：{2}。' -f $Service.PathName, $Layout.WrapperExecutable, $expectedServiceTail)
    }
    if ($Service.StartName -ine ('NT SERVICE\' + $Layout.ServiceName)) { throw 'SCM 服务账号不是本产品虚拟服务账号。' }
    if ($Service.StartMode -ine 'Manual') { throw 'SCM 启动类型不是 Manual。' }
    $registry = Get-ServiceRegistrySnapshot $Layout.ServiceName
    if ($null -eq $registry -or $registry.ServiceSidType -ne 1 -or $registry.Start -ne 3 -or @($registry.DependOnService).Count -ne 0) { throw '服务 SID 类型、依赖或 Manual 注册值不匹配。' }
    $failure = Get-FailureActionSummary $Layout.ServiceName
    $expected = @('1:5000', '1:10000', '1:15000', '0:0')
    if ($null -eq $failure -or $failure.ResetSeconds -ne 3600 -or ($failure.Actions -join ',') -cne ($expected -join ',')) {
        throw 'SCM FailureActions 不是预期的 5/10/15 秒重启及后续 none。'
    }
    Assert-ServiceDacl -Name $Layout.ServiceName
}

function Get-ManagedMarker {
    param([Parameter(Mandatory)]$Layout)
    try {
        $markerItem = Get-Item -LiteralPath $Layout.MarkerFile -Force -ErrorAction Stop
        if ($markerItem.PSIsContainer) { throw '管理记录路径是目录而不是文件。' }
    } catch [System.Management.Automation.ItemNotFoundException] {
        return $null
    } catch {
        throw '无法读取管理记录；归属状态为 UNKNOWN，禁止猜测或执行副作用操作。'
    }
    $marker = Read-JsonFile $Layout.MarkerFile
    if ($null -eq $marker) { return $null }
    if ($marker.Product -cne $script:ProductName -or [int]$marker.Schema -ne $script:Schema) { throw '管理记录产品或版本不匹配。' }
    if ($marker.ServiceName -cne $Layout.ServiceName -or -not (Test-SamePath $marker.CodeRoot $Layout.CodeRoot) -or -not (Test-SamePath $marker.DataRoot $Layout.DataRoot) -or [int]$marker.Port -ne [int]$Layout.Port) {
        throw '管理记录固定身份、路径或端口不匹配。'
    }
    if ($marker.ServiceAccount -cne ('NT SERVICE\' + $Layout.ServiceName) -or [string]$marker.InstallNonce -notmatch '^[a-f0-9]{32}$') {
        throw '管理记录服务账号或安装 nonce 不匹配。'
    }
    if ($marker.RegistrationState -eq 'Installed' -and [string]$marker.ServiceSid -notmatch '^S-1-5-80-') {
        throw '已安装管理记录缺少有效的虚拟服务 SID。'
    }
    foreach ($property in @('RedisExecutable', 'WrapperExecutable', 'WrapperXml', 'RedisConfig', 'RedisDataRoot', 'RedisLogRoot', 'MarkerFile', 'SecretFile')) {
        if (-not $marker.PSObject.Properties.Name.Contains($property) -or -not (Test-SamePath $marker.$property $Layout.$property)) { throw '管理记录存在路径重定向。' }
    }
    foreach ($property in @('ServiceSid', 'RedisSource', 'WrapperSource', 'RedisLicensePath', 'WrapperLicensePath', 'RedisLicenseSha256', 'WrapperLicenseSha256', 'RedisVersion', 'RedisArchitecture', 'RedisRuntimeFamily', 'RedisArchivePath', 'RedisArchiveSha256', 'RedisDistributionFiles', 'WrapperVersion')) {
        if (-not $marker.PSObject.Properties.Name.Contains($property)) { throw ('管理记录缺少不可变来源字段：{0}。' -f $property) }
    }
    if ($marker.RegistrationState -notin @('Installing', 'Installed', 'Uninstalled')) { throw '管理记录状态无效。' }
    return $marker
}

function Assert-MarkerServiceIdentity {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)]$Marker)
    if ($null -ne $Marker.ServiceSid -and [string]$Marker.ServiceSid -notmatch '^S-1-5-80-') {
        throw '管理记录中的服务 SID 格式无效。'
    }
    if ($Marker.RegistrationState -eq 'Installed') {
        $actualSid = Get-ServiceSid -Name $Layout.ServiceName
        if ([string]$Marker.ServiceSid -cne $actualSid) { throw '管理记录服务 SID 与当前服务账号解析结果不匹配。' }
    }
}

function Assert-MarkerLicenseEvidence {
    param([Parameter(Mandatory)]$Marker)
    foreach ($entry in @(
        @('RedisLicensePath', 'RedisLicenseSha256'),
        @('WrapperLicensePath', 'WrapperLicenseSha256')
    )) {
        $path = ConvertTo-FullPath ([string]$Marker.($entry[0]))
        Assert-NoReparsePoint $path
        if (-not [IO.File]::Exists($path)) { throw ('许可证证据文件不存在：{0}。' -f $path) }
        $expected = [string]$Marker.($entry[1])
        if ($expected -notmatch '^[a-f0-9]{64}$' -or (Get-Sha256 $path) -cne $expected) {
            throw ('许可证证据哈希不匹配：{0}。' -f $path)
        }
    }
    if ([string]::IsNullOrWhiteSpace([string]$Marker.RedisSource) -or [string]::IsNullOrWhiteSpace([string]$Marker.WrapperSource)) {
        throw '管理记录缺少构件来源说明。'
    }
    Assert-NonSecretDescription -Description ([string]$Marker.RedisSource)
    Assert-NonSecretDescription -Description ([string]$Marker.WrapperSource)
}

function Assert-MarkerArtifact {
    param([Parameter(Mandatory)]$Marker, [Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$HashProperty)
    Assert-NoReparsePoint $Path
    if (-not [IO.File]::Exists($Path)) { throw ('受保护构件不存在：{0}。' -f $Path) }
    $expected = [string]$Marker.$HashProperty
    if ($expected -notmatch '^[a-f0-9]{64}$' -or (Get-Sha256 $Path) -cne $expected) { throw ('受保护构件哈希不匹配：{0}。' -f $Path) }
}

function Get-ManagedRedisProcessIdentity {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)]$Service)
    $result = [ordered]@{ Owned = $false; WrapperProcessId = [int]$Service.ProcessId; RedisProcessId = $null; RedisCreationDate = $null; Diagnostic = 'NotRunning' }
    if ([int]$Service.ProcessId -le 0) { return [pscustomobject]$result }
    $wrapper = Get-ProcessRecord -ProcessId ([int]$Service.ProcessId)
    if ($null -eq $wrapper -or -not (Test-SamePath $wrapper.ExecutablePath $Layout.WrapperExecutable)) {
        $result.Diagnostic = 'WrapperIdentityMismatch'
        return [pscustomobject]$result
    }
    $children = @(Get-CimInstance Win32_Process -Filter ('ParentProcessId={0}' -f [int]$Service.ProcessId) -OperationTimeoutSec 2 -ErrorAction Stop)
    $redis = @($children | Where-Object {
        (Test-SamePath $_.ExecutablePath $Layout.RedisExecutable) -and
        (Test-CommandLineContainsPath -CommandLine ([string]$_.CommandLine) -Path $Layout.RedisConfig -AllowRelativeConfig) -and
        $null -ne $_.CreationDate -and $null -ne $wrapper.CreationDate -and $_.CreationDate -ge $wrapper.CreationDate
    })
    $unexpected = @($children | Where-Object {
        -not (Test-SamePath $_.ExecutablePath $Layout.RedisExecutable) -and
        -not (Test-SamePath $_.ExecutablePath (Join-Path $env:SystemRoot 'System32\conhost.exe'))
    })
    if ($redis.Count -ne 1 -or $unexpected.Count -ne 0) {
        $result.Diagnostic = 'ChildProcessIdentityMismatch'
        return [pscustomobject]$result
    }
    $result.RedisProcessId = [int]$redis[0].ProcessId
    $result.RedisCreationDate = $redis[0].CreationDate
    $result.Owned = $true
    $result.Diagnostic = 'OwnedWrapperAndChild'
    return [pscustomobject]$result
}

function Test-OwnedListener {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)]$Identity)
    if (-not $Identity.Owned -or $null -eq $Identity.RedisProcessId) { return $false }
    $listeners = @(Get-ListenerRecord -Port $Layout.Port)
    if ($listeners.Count -ne 1) { return $false }
    return [int]$listeners[0].OwningProcess -eq [int]$Identity.RedisProcessId -and $listeners[0].LocalAddress -eq '127.0.0.1'
}

function Get-ManagedRedisProcessCandidates {
    param([Parameter(Mandatory)]$Layout)
    try {
        $name = [IO.Path]::GetFileName($Layout.RedisExecutable)
        return @(Get-CimInstance Win32_Process -Filter ("Name='{0}'" -f $name) -OperationTimeoutSec 2 -ErrorAction Stop | Where-Object {
            (Test-SamePath $_.ExecutablePath $Layout.RedisExecutable) -and
            (Test-CommandLineContainsPath -CommandLine ([string]$_.CommandLine) -Path $Layout.RedisConfig -AllowRelativeConfig)
        })
    } catch {
        throw '无法读取 Redis 进程候选；归属状态为 UNKNOWN，拒绝生命周期操作。'
    }
}

function Assert-ManagedRedisLifecycleState {
    param([Parameter(Mandatory)][string]$ServiceState, [Parameter(Mandatory)][int]$ResidualProcessCount)
    if ($ServiceState -notin @('Running', 'Stopped')) {
        throw ('服务处于过渡状态 {0}，归属状态 UNKNOWN；拒绝生命周期操作。' -f $ServiceState)
    }
    if ($ServiceState -eq 'Stopped' -and $ResidualProcessCount -ne 0) {
        throw 'SCM 已停止但仍存在匹配的 Redis 进程；拒绝继续生命周期操作。'
    }
}

function Assert-OwnedService {
    param([Parameter(Mandatory)]$Layout, [switch]$AllowStopped)
    $marker = Get-ManagedMarker $Layout
    if ($null -eq $marker -or $marker.RegistrationState -ne 'Installed') { throw '缺少已安装的本产品管理记录；禁止服务生命周期操作。' }
    Assert-MarkerServiceIdentity -Layout $Layout -Marker $marker
    Assert-MarkerLicenseEvidence -Marker $marker
    foreach ($pair in @(
        @($Layout.RedisExecutable, 'RedisExecutableSha256'),
        @($Layout.WrapperExecutable, 'WrapperExecutableSha256'),
        @($Layout.WrapperXml, 'WrapperXmlSha256'),
        @($Layout.RedisConfig, 'RedisConfigSha256'),
        @($Layout.SecretFile, 'SecretFileSha256')
    )) { Assert-MarkerArtifact -Marker $marker -Path $pair[0] -HashProperty $pair[1] }
    Assert-MarkerDistribution -Layout $Layout -Marker $marker
    $service = Get-ServiceRecord $Layout.ServiceName
    if ($null -eq $service) { throw '管理记录存在但 SCM 服务不存在，归属状态 UNKNOWN；禁止接管。' }
    Assert-ServiceConfiguration -Layout $Layout -Service $service
    Assert-ManagedArtifactsAcl -Layout $Layout -ServiceSid ([string]$marker.ServiceSid)
    $residualProcessCount = 0
    if ([string]$service.State -eq 'Stopped') { $residualProcessCount = @(Get-ManagedRedisProcessCandidates -Layout $Layout).Count }
    Assert-ManagedRedisLifecycleState -ServiceState ([string]$service.State) -ResidualProcessCount $residualProcessCount
    if (-not $AllowStopped -and $service.State -eq 'Stopped') { throw '本产品服务当前已停止。' }
    $identity = $null
    if ($service.State -eq 'Running') {
        $identity = Get-ManagedRedisProcessIdentity -Layout $Layout -Service $service
        if (-not $identity.Owned) { throw '运行中的 SCM 服务未通过 wrapper/Redis 子进程归属核验；禁止生命周期操作。' }
    }
    [pscustomobject]@{ Marker = $marker; Service = $service; Identity = $identity }
}

function New-RespCommandBytes {
    param([Parameter(Mandatory)][string[]]$Command)
    $memory = New-Object IO.MemoryStream
    try {
        $prefix = [Text.Encoding]::ASCII.GetBytes(('*' + $Command.Count + "`r`n"))
        $memory.Write($prefix, 0, $prefix.Length)
        foreach ($part in $Command) {
            $bytes = $script:Utf8.GetBytes($part)
            $header = [Text.Encoding]::ASCII.GetBytes(('$' + $bytes.Length + "`r`n"))
            $memory.Write($header, 0, $header.Length)
            $memory.Write($bytes, 0, $bytes.Length)
            $memory.WriteByte(13)
            $memory.WriteByte(10)
        }
        return $memory.ToArray()
    } finally {
        $memory.Dispose()
    }
}

function Read-RespByte {
    param([Parameter(Mandatory)][IO.Stream]$Stream, [Parameter(Mandatory)][datetime]$Deadline)
    $Stream.ReadTimeout = Get-RemainingMilliseconds -Deadline $Deadline -Maximum 5000
    $value = $Stream.ReadByte()
    if ($value -lt 0) { throw 'Redis 响应提前结束。' }
    return $value
}

function Read-RespLine {
    param([Parameter(Mandatory)][IO.Stream]$Stream, [Parameter(Mandatory)][datetime]$Deadline)
    $bytes = New-Object System.Collections.Generic.List[byte]
    while ($bytes.Count -lt 8192) {
        $value = Read-RespByte -Stream $Stream -Deadline $Deadline
        if ($value -eq 13) {
            if ((Read-RespByte -Stream $Stream -Deadline $Deadline) -ne 10) { throw 'Redis RESP 行结束符错误。' }
            return $script:Utf8.GetString($bytes.ToArray())
        }
        $bytes.Add([byte]$value)
    }
    throw 'Redis RESP 行超过上限。'
}

function Read-RespValue {
    param([Parameter(Mandatory)][IO.Stream]$Stream, [Parameter(Mandatory)][datetime]$Deadline, [int]$Depth = 0)
    if ($Depth -gt 4) { throw 'Redis RESP 嵌套超过上限。' }
    $type = [char](Read-RespByte -Stream $Stream -Deadline $Deadline)
    $line = Read-RespLine -Stream $Stream -Deadline $Deadline
    switch ($type) {
        '+' { return $line }
        '-' { throw ('Redis 返回错误类别：{0}。' -f (($line -split '\s+')[0])) }
        ':' { return [long]::Parse($line, [Globalization.CultureInfo]::InvariantCulture) }
        '$' {
            $length = [int]::Parse($line, [Globalization.CultureInfo]::InvariantCulture)
            if ($length -eq -1) { return $null }
            if ($length -lt 0 -or $length -gt 1048576) { throw 'Redis bulk 长度超限。' }
            $bytes = New-Object byte[] $length
            $offset = 0
            while ($offset -lt $length) {
                $Stream.ReadTimeout = Get-RemainingMilliseconds -Deadline $Deadline -Maximum 5000
                $count = $Stream.Read($bytes, $offset, $length - $offset)
                if ($count -eq 0) { throw 'Redis bulk 响应提前结束。' }
                $offset += $count
            }
            if ((Read-RespByte -Stream $Stream -Deadline $Deadline) -ne 13 -or (Read-RespByte -Stream $Stream -Deadline $Deadline) -ne 10) { throw 'Redis bulk 结束符错误。' }
            return $script:Utf8.GetString($bytes)
        }
        '*' {
            $length = [int]::Parse($line, [Globalization.CultureInfo]::InvariantCulture)
            if ($length -eq -1) { return $null }
            if ($length -lt 0 -or $length -gt 512) { throw 'Redis array 长度超限。' }
            $values = New-Object object[] $length
            for ($i = 0; $i -lt $length; $i++) { $values[$i] = Read-RespValue -Stream $Stream -Deadline $Deadline -Depth ($Depth + 1) }
            return ,$values
        }
        default { throw '不支持的 Redis RESP 类型。' }
    }
}

function Invoke-RedisCommand {
    param(
        [Parameter(Mandatory)][string]$TargetHost,
        [Parameter(Mandatory)][int]$Port,
        [string]$Password,
        [Parameter(Mandatory)][string[]]$Command,
        [int]$TimeoutMilliseconds = 2000,
        [int]$ExpectedProcessId = 0,
        [switch]$AllowConnectionCloseAfterWrite
    )
    $client = New-Object Net.Sockets.TcpClient
    $stream = $null
    $deadline = [datetime]::UtcNow.AddMilliseconds($TimeoutMilliseconds)
    try {
        $connect = $client.ConnectAsync($TargetHost, $Port)
        if (-not $connect.Wait((Get-RemainingMilliseconds -Deadline $deadline -Maximum $TimeoutMilliseconds))) { throw 'Redis 连接超时。' }
        $stream = $client.GetStream()
        if ($ExpectedProcessId -gt 0) {
            $listeners = @(Get-ListenerRecord -Port $Port)
            if ($listeners.Count -ne 1 -or [int]$listeners[0].OwningProcess -ne $ExpectedProcessId -or $listeners[0].LocalAddress -ne '127.0.0.1') {
                throw 'Redis 连接建立后监听者归属发生变化；拒绝发送认证或运维命令。'
            }
        }
        if (-not [string]::IsNullOrEmpty($Password)) {
            $authBytes = New-RespCommandBytes -Command @('AUTH', $Password)
            $stream.WriteTimeout = Get-RemainingMilliseconds -Deadline $deadline -Maximum 5000
            $stream.Write($authBytes, 0, $authBytes.Length)
            $auth = Read-RespValue -Stream $stream -Deadline $deadline
            if ($auth -cne 'OK') { throw 'Redis AUTH 未确认。' }
        }
        $commandBytes = New-RespCommandBytes -Command $Command
        $stream.WriteTimeout = Get-RemainingMilliseconds -Deadline $deadline -Maximum 5000
        $stream.Write($commandBytes, 0, $commandBytes.Length)
        if ($AllowConnectionCloseAfterWrite) {
            return [pscustomobject]@{ Sent = $true; Value = $null; ConnectionClosedExpected = $true }
        }
        $value = Read-RespValue -Stream $stream -Deadline $deadline
        return [pscustomobject]@{ Sent = $true; Value = $value; ConnectionClosedExpected = $false }
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        $client.Dispose()
    }
}

function Get-ManagedPassword {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)]$Marker)
    Assert-MarkerArtifact -Marker $Marker -Path $Layout.SecretFile -HashProperty SecretFileSha256
    $secret = Read-JsonFile $Layout.SecretFile
    if ($null -eq $secret -or [string]::IsNullOrWhiteSpace([string]$secret.RedisPassword)) { throw '受控 Redis 密码文件格式无效。' }
    return [string]$secret.RedisPassword
}

function Invoke-RedisPing {
    param([Parameter(Mandatory)][string]$TargetHost, [Parameter(Mandatory)][int]$Port, [string]$Password, [int]$TimeoutMilliseconds = 2000, [int]$ExpectedProcessId = 0)
    try {
        $response = Invoke-RedisCommand -TargetHost $TargetHost -Port $Port -Password $Password -Command @('PING') -TimeoutMilliseconds $TimeoutMilliseconds -ExpectedProcessId $ExpectedProcessId
        return [pscustomobject]@{ Succeeded = $response.Value -ceq 'PONG'; Diagnostic = if ($response.Value -ceq 'PONG') { 'PONG' } else { 'UnexpectedResponse' } }
    } catch {
        $message = $_.Exception.Message
        $category = if ($message -match 'NOAUTH|AUTH') { 'AUTH_REQUIRED_OR_FAILED' } elseif ($message -match '连接|超时|Connect') { 'UNREACHABLE_OR_TIMEOUT' } else { 'PING_FAILED' }
        return [pscustomobject]@{ Succeeded = $false; Diagnostic = $category }
    }
}

function Invoke-RedisShutdownSave {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)]$Marker, [Parameter(Mandatory)][datetime]$Deadline, [Parameter(Mandatory)][int]$ExpectedProcessId)
    $password = Get-ManagedPassword -Layout $Layout -Marker $Marker
    try {
        $result = Invoke-RedisCommand -TargetHost '127.0.0.1' -Port $Layout.Port -Password $password -Command @('SHUTDOWN', 'SAVE') -TimeoutMilliseconds (Get-RemainingMilliseconds -Deadline $Deadline -Maximum 5000) -ExpectedProcessId $ExpectedProcessId -AllowConnectionCloseAfterWrite
        return [pscustomobject]@{ Dispatched = $result.Sent; Diagnostic = 'AuthenticatedShutdownSaveDispatched' }
    } catch {
        throw ('Redis 优雅关闭请求未能发送：{0}。' -f $_.Exception.Message)
    } finally {
        $password = $null
    }
}

function Get-ManagedOwnership {
    param([Parameter(Mandatory)]$Layout)
    $result = [ordered]@{
        Ownership = 'NOT_INSTALLED'
        Marker = $null
        Service = $null
        Identity = $null
        Diagnostic = 'NoManagedService'
    }
    try {
        $marker = Get-ManagedMarker $Layout
        $serviceResult = Get-ServiceRecordSafe $Layout.ServiceName
        if ($null -eq $marker -and $null -eq $serviceResult.Value) {
            if ($null -ne $serviceResult.Error) {
                $result.Ownership = 'OWNERSHIP_UNKNOWN'
                $result.Diagnostic = 'ScmReadFailed'
            }
            return [pscustomobject]$result
        }
        if ($null -eq $marker -and $null -ne $serviceResult.Value) {
            $result.Ownership = 'FOREIGN_SERVICE'
            $result.Service = $serviceResult.Value
            $result.Diagnostic = 'ServiceNameCollisionWithoutProductMarker'
            return [pscustomobject]$result
        }
        $result.Marker = $marker
        if ($null -eq $serviceResult.Value) {
            if ($null -ne $serviceResult.Error) {
                $result.Ownership = 'OWNERSHIP_UNKNOWN'
                $result.Diagnostic = 'ScmReadFailed'
                return [pscustomobject]$result
            }
            $result.Ownership = if ($marker.RegistrationState -eq 'Uninstalled') { 'NOT_INSTALLED' } else { 'OWNERSHIP_UNKNOWN' }
            $result.Diagnostic = 'MarkerAndScmRegistrationDisagree'
            return [pscustomobject]$result
        }
        $service = $serviceResult.Value
        $result.Service = $service
        if ($marker.RegistrationState -ne 'Installed') {
            $result.Ownership = 'OWNERSHIP_UNKNOWN'
            $result.Diagnostic = 'MarkerNotInstalledButServiceExists'
            return [pscustomobject]$result
        }
        if ([string]$service.State -notin @('Running', 'Stopped')) {
            $result.Ownership = 'OWNERSHIP_UNKNOWN'
            $result.Diagnostic = 'ScmStateTransitionInProgress'
            return [pscustomobject]$result
        }
        try {
            Assert-MarkerServiceIdentity -Layout $Layout -Marker $marker
            Assert-MarkerLicenseEvidence -Marker $marker
            foreach ($pair in @(
                @($Layout.RedisExecutable, 'RedisExecutableSha256'),
                @($Layout.WrapperExecutable, 'WrapperExecutableSha256'),
                @($Layout.WrapperXml, 'WrapperXmlSha256'),
                @($Layout.RedisConfig, 'RedisConfigSha256'),
                @($Layout.SecretFile, 'SecretFileSha256')
            )) { Assert-MarkerArtifact -Marker $marker -Path $pair[0] -HashProperty $pair[1] }
            Assert-ServiceConfiguration -Layout $Layout -Service $service
            Assert-ManagedArtifactsAcl -Layout $Layout -ServiceSid ([string]$marker.ServiceSid)
            if ($service.State -eq 'Stopped' -and @(Get-ManagedRedisProcessCandidates -Layout $Layout).Count -ne 0) {
                throw 'SCM 已停止但仍存在匹配的 Redis 进程。'
            }
            $identity = Get-ManagedRedisProcessIdentity -Layout $Layout -Service $service
            $result.Identity = $identity
            $result.Ownership = 'MANAGED_REDIS'
            $result.Diagnostic = $identity.Diagnostic
        } catch {
            $result.Ownership = 'OWNERSHIP_UNKNOWN'
            $result.Diagnostic = 'ManagedIdentityVerificationFailed'
        }
    } catch {
        $result.Ownership = 'OWNERSHIP_UNKNOWN'
        $result.Diagnostic = 'ReadOnlyOwnershipAuditFailed'
    }
    return [pscustomobject]$result
}

function Get-WangbinCollectorRedisStatus {
    [CmdletBinding()]
    param(
        [string]$TargetHost = '127.0.0.1',
        [ValidateRange(1, 65535)][int]$Port = $script:DefaultPort,
        [Security.SecureString]$ExternalPassword
    )
    Assert-WindowsPlatform
    $layout = Get-WangbinCollectorRedisLayout -Port $Port
    $ownership = Get-ManagedOwnership -Layout $layout
    $status = [ordered]@{
        Product = $script:ProductName
        ServiceName = $layout.ServiceName
        Mode = $ownership.Ownership
        ServiceRegistered = $false
        ScmState = 'UNKNOWN'
        OwnershipVerified = $false
        ServiceProcessId = $null
        RedisProcessId = $null
        RedisVersion = $null
        WrapperVersion = $null
        Port = $Port
        PortListening = $false
        ListenerProcessId = $null
        PingSucceeded = $false
        Ready = $false
        LifecycleManagement = $false
        Evidence = 'INFERRED'
        Diagnostic = $ownership.Diagnostic
    }
    if ($null -ne $ownership.Service) {
        $status.ServiceRegistered = $true
        $status.ScmState = [string]$ownership.Service.State
        $status.ServiceProcessId = [int]$ownership.Service.ProcessId
    } elseif ($ownership.Ownership -eq 'NOT_INSTALLED') {
        $status.ScmState = 'ABSENT'
    }
    if ($null -ne $ownership.Marker) {
        $status.RedisVersion = [string]$ownership.Marker.RedisVersion
        $status.WrapperVersion = [string]$ownership.Marker.WrapperVersion
    }
    if ($null -ne $ownership.Identity) {
        $status.RedisProcessId = $ownership.Identity.RedisProcessId
        $status.OwnershipVerified = [bool]$ownership.Identity.Owned
        if ($ownership.Identity.Owned) {
            $listeners = @(Get-ListenerRecord -Port $Port)
            if ($listeners.Count -eq 1 -and $listeners[0].LocalAddress -eq '127.0.0.1') {
                $status.PortListening = $true
                $status.ListenerProcessId = [int]$listeners[0].OwningProcess
            }
        }
    } else {
        $listeners = @(Get-ListenerRecord -Port $Port)
        if ($listeners.Count -eq 1 -and $listeners[0].LocalAddress -eq '127.0.0.1') {
            $status.PortListening = $true
            $status.ListenerProcessId = [int]$listeners[0].OwningProcess
        }
    }
    $password = $null
    try {
        if ($ownership.Ownership -eq 'MANAGED_REDIS' -and (-not $status.OwnershipVerified -or -not $status.PortListening -or $status.ListenerProcessId -ne $status.RedisProcessId)) {
            $status.LifecycleManagement = $true
            $status.Evidence = 'BLOCKED'
            $status.Diagnostic = 'ManagedListenerOwnershipNotVerified'
            return [pscustomobject]$status
        }
        if ($ownership.Ownership -eq 'MANAGED_REDIS' -and $null -ne $ownership.Marker) {
            $password = Get-ManagedPassword -Layout $layout -Marker $ownership.Marker
        } elseif ($null -ne $ExternalPassword) {
            $password = Convert-SecureStringToPlainText $ExternalPassword
        }
        $expectedProcessId = 0
        $pingHost = $TargetHost
        if ($ownership.Ownership -eq 'MANAGED_REDIS') {
            $pingHost = '127.0.0.1'
            if ($status.OwnershipVerified) { $expectedProcessId = [int]$status.RedisProcessId }
        }
        $ping = Invoke-RedisPing -TargetHost $pingHost -Port $Port -Password $password -ExpectedProcessId $expectedProcessId
        $status.PingSucceeded = [bool]$ping.Succeeded
        if ($ownership.Ownership -eq 'MANAGED_REDIS') {
            $status.Ready = [bool]($ping.Succeeded -and $status.OwnershipVerified -and $status.PortListening -and $status.ListenerProcessId -eq $status.RedisProcessId)
        } else {
            $status.Ready = [bool]($ping.Succeeded -and $ownership.Ownership -eq 'NOT_INSTALLED')
        }
        if ($ownership.Ownership -eq 'NOT_INSTALLED' -and $ping.Succeeded) {
            $status.Mode = 'EXTERNAL_REDIS'
            $status.LifecycleManagement = $false
            $status.Evidence = 'VERIFIED_CONNECTION_ONLY'
            $status.Diagnostic = 'ExternalRedisConnectionOnly'
        } elseif ($ownership.Ownership -eq 'MANAGED_REDIS') {
            $status.LifecycleManagement = $true
            $status.Evidence = if ($status.Ready) { 'VERIFIED' } else { 'BLOCKED' }
            $status.Diagnostic = if ($ping.Succeeded) { 'ManagedRedisReady' } else { 'ManagedServiceNotReady' }
        } elseif ($ownership.Ownership -eq 'OWNERSHIP_UNKNOWN' -or $ownership.Ownership -eq 'FOREIGN_SERVICE') {
            $status.Evidence = 'BLOCKED'
        } elseif ($ownership.Ownership -eq 'NOT_INSTALLED' -and -not $status.ServiceRegistered -and -not $status.PortListening -and -not $status.PingSucceeded) {
            $status.Evidence = 'VERIFIED'
        }
    } finally {
        $password = $null
    }
    return [pscustomobject]$status
}

function New-WinSwXml {
    param([Parameter(Mandatory)]$Layout)
    $doc = New-Object Xml.XmlDocument
    $null = $doc.AppendChild($doc.CreateXmlDeclaration('1.0', 'utf-8', $null))
    $root = $doc.CreateElement('service')
    $null = $doc.AppendChild($root)
    $values = [ordered]@{
        id = $Layout.ServiceName
        name = $Layout.ServiceName
        description = 'WangbinCollector 独立 Redis 服务'
        executable = $Layout.RedisExecutable
        arguments = 'redis.conf'
        workingdirectory = $Layout.ConfigRoot
        logpath = $Layout.RedisLogRoot
        startmode = 'Manual'
        stoptimeout = '120 sec'
        stopparentprocessfirst = 'true'
        resetfailure = '1 hour'
        securityDescriptor = 'D:P(A;;GA;;;SY)(A;;GA;;;BA)'
    }
    foreach ($key in $values.Keys) {
        $element = $doc.CreateElement($key)
        $element.InnerText = [string]$values[$key]
        $null = $root.AppendChild($element)
    }
    $account = $doc.CreateElement('serviceaccount')
    foreach ($pair in @(@('domain', 'NT SERVICE'), @('user', $Layout.ServiceName), @('allowservicelogon', 'false'))) {
        $element = $doc.CreateElement($pair[0])
        $element.InnerText = $pair[1]
        $null = $account.AppendChild($element)
    }
    $null = $root.AppendChild($account)
    foreach ($delay in @('5 sec', '10 sec', '15 sec', '')) {
        $element = $doc.CreateElement('onfailure')
        if ([string]::IsNullOrEmpty($delay)) {
            $element.SetAttribute('action', 'none')
        } else {
            $element.SetAttribute('action', 'restart')
            $element.SetAttribute('delay', $delay)
        }
        $null = $root.AppendChild($element)
    }
    $log = $doc.CreateElement('log')
    $log.SetAttribute('mode', 'roll-by-size')
    $size = $doc.CreateElement('sizeThreshold'); $size.InnerText = '10485760'; $null = $log.AppendChild($size)
    $keep = $doc.CreateElement('keepFiles'); $keep.InnerText = '5'; $null = $log.AppendChild($keep)
    $null = $root.AppendChild($log)
    return $doc.OuterXml
}

function New-RedisConfigText {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)][string]$Password)
    return @"
bind 127.0.0.1
port $($Layout.Port)
protected-mode yes
daemonize no
supervised no
databases 16
requirepass $Password
maxmemory 256mb
maxmemory-policy noeviction
appendonly yes
appendfsync everysec
dir "$($Layout.RedisDataRoot.Replace([char]92, [char]47))"
logfile "$($Layout.RedisLog.Replace([char]92, [char]47))"
save 900 1
save 300 10
save 60 10000
"@
}

function New-InitialMarker {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)]$RedisDistribution, [Parameter(Mandatory)][string]$RedisHash, [Parameter(Mandatory)][string]$WrapperHash, [Parameter(Mandatory)][string]$XmlHash, [Parameter(Mandatory)][string]$ConfigHash, [Parameter(Mandatory)][string]$SecretHash, [string]$RedisVersion, [string]$WrapperVersion, [string]$RedisSource, [string]$WrapperSource, [string]$RedisLicensePath, [string]$WrapperLicensePath, [string]$RedisLicenseHash, [string]$WrapperLicenseHash)
    [ordered]@{
        Product = $script:ProductName
        Schema = $script:Schema
        ServiceName = $Layout.ServiceName
        ServiceAccount = ('NT SERVICE\' + $Layout.ServiceName)
        InstallNonce = [guid]::NewGuid().ToString('N')
        CodeRoot = $Layout.CodeRoot
        DataRoot = $Layout.DataRoot
        Port = $Layout.Port
        RegistrationState = 'Installing'
        CreatedUtc = [datetime]::UtcNow.ToString('o')
        RedisExecutable = $Layout.RedisExecutable
        RedisExecutableSha256 = $RedisHash
        RedisVersion = $RedisVersion
        RedisArchitecture = $RedisDistribution.Architecture
        RedisRuntimeFamily = $RedisDistribution.RuntimeFamily
        RedisArchivePath = $RedisDistribution.ArchivePath
        RedisArchiveSha256 = $RedisDistribution.ArchiveSha256
        RedisDistributionFiles = @($RedisDistribution.Files)
        RedisSource = $RedisSource
        RedisLicensePath = $RedisLicensePath
        RedisLicenseSha256 = $RedisLicenseHash
        WrapperExecutable = $Layout.WrapperExecutable
        WrapperExecutableSha256 = $WrapperHash
        WrapperVersion = $WrapperVersion
        WrapperSource = $WrapperSource
        WrapperLicensePath = $WrapperLicensePath
        WrapperLicenseSha256 = $WrapperLicenseHash
        WrapperXml = $Layout.WrapperXml
        WrapperXmlSha256 = $XmlHash
        RedisConfig = $Layout.RedisConfig
        RedisConfigSha256 = $ConfigHash
        RedisDataRoot = $Layout.RedisDataRoot
        RedisLogRoot = $Layout.RedisLogRoot
        MarkerFile = $Layout.MarkerFile
        SecretFile = $Layout.SecretFile
        SecretFileSha256 = $SecretHash
        ServiceSid = $null
    }
}

function Save-ManagedMarker {
    param([Parameter(Mandatory)]$Layout, [Parameter(Mandatory)]$Marker)
    $json = $Marker | ConvertTo-Json -Depth 8
    Write-ControlledText -Path $Layout.MarkerFile -Text $json -Mode Admin -Replace
    $readBack = Get-ManagedMarker $Layout
    if ($null -eq $readBack -or $readBack.ServiceName -cne $Layout.ServiceName -or $readBack.RegistrationState -cne $Marker.RegistrationState) {
        throw '管理记录原子写入后的身份读回不匹配。'
    }
}

function Install-WangbinCollectorRedis {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RedisDistributionArchive,
        [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedRedisArchiveSha256,
        [Parameter(Mandatory)][string]$WinSwExecutable,
        [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedWinSwSha256,
        [Parameter(Mandatory)][string]$RedisSourceDescription,
        [Parameter(Mandatory)][string]$WinSwSourceDescription,
        [Parameter(Mandatory)][string]$RedisLicenseFile,
        [Parameter(Mandatory)][string]$WinSwLicenseFile,
        [ValidateRange(1, 65535)][int]$Port = $script:DefaultPort
    )
    $operationLock = Enter-WangbinRedisOperationLock
    try {
    Assert-WindowsPlatform
    Assert-Administrator
    Assert-NonSecretDescription -Description $RedisSourceDescription
    Assert-NonSecretDescription -Description $WinSwSourceDescription
    $layout = Get-WangbinCollectorRedisLayout -Port $Port
    Assert-ServiceName $layout.ServiceName
    $redisArchive = ConvertTo-FullPath $RedisDistributionArchive
    $winSwSource = ConvertTo-FullPath $WinSwExecutable
    $redisLicense = ConvertTo-FullPath $RedisLicenseFile
    $winSwLicense = ConvertTo-FullPath $WinSwLicenseFile
    foreach ($path in @($redisArchive, $winSwSource, $redisLicense, $winSwLicense)) { Assert-NoReparsePoint $path }
    if (-not [IO.File]::Exists($redisArchive) -or -not [IO.File]::Exists($winSwSource) -or -not [IO.File]::Exists($redisLicense) -or -not [IO.File]::Exists($winSwLicense)) { throw 'Redis 完整发行包、WinSW 和许可证输入文件必须全部存在。' }
    if (([IO.FileInfo]$redisLicense).Length -le 0 -or ([IO.FileInfo]$winSwLicense).Length -le 0) { throw '许可证证据文件不能为空。' }
    $redisDistribution = Assert-RedisDistributionArchive -ArchivePath $redisArchive -ExpectedSha256 $ExpectedRedisArchiveSha256
    $wrapperHash = Assert-Sha256 -Path $winSwSource -Expected $ExpectedWinSwSha256
    $redisLicenseHash = Get-Sha256 $redisLicense
    $wrapperLicenseHash = Get-Sha256 $winSwLicense
    $serviceExisting = Get-ServiceRecord $layout.ServiceName
    $existingMarker = Get-ManagedMarker $layout
    if ($null -ne $serviceExisting -or $null -ne $existingMarker) {
        if ($null -eq $serviceExisting -or $null -eq $existingMarker -or $existingMarker.RegistrationState -ne 'Installed') {
            throw 'WangbinCollector-Redis 或其管理记录已存在但无法成对证明归属；不接管、不覆盖、不重装。'
        }
        try {
            foreach ($pair in @(
                @($layout.RedisExecutable, 'RedisExecutableSha256'),
                @($layout.WrapperExecutable, 'WrapperExecutableSha256'),
                @($layout.WrapperXml, 'WrapperXmlSha256'),
                @($layout.RedisConfig, 'RedisConfigSha256'),
                @($layout.SecretFile, 'SecretFileSha256')
            )) { Assert-MarkerArtifact -Marker $existingMarker -Path $pair[0] -HashProperty $pair[1] }
            if ([string]$existingMarker.RedisArchiveSha256 -cne $redisDistribution.ArchiveSha256 -or [string]$existingMarker.WrapperExecutableSha256 -cne $wrapperHash) {
                throw '本次提供的 Redis 发行包或 WinSW 构件 SHA-256 与已安装管理记录不一致。'
            }
            if (-not (Test-SamePath $existingMarker.RedisArchivePath $redisArchive) -or
                -not (Test-SamePath $existingMarker.RedisLicensePath $redisLicense) -or
                -not (Test-SamePath $existingMarker.WrapperLicensePath $winSwLicense) -or
                [string]$existingMarker.RedisLicenseSha256 -cne $redisLicenseHash -or
                [string]$existingMarker.WrapperLicenseSha256 -cne $wrapperLicenseHash -or
                [string]$existingMarker.RedisSource -cne $RedisSourceDescription -or
                [string]$existingMarker.WrapperSource -cne $WinSwSourceDescription -or
                [string]$existingMarker.RedisVersion -cne $script:RedisVersion -or
                [string]$existingMarker.WrapperVersion -cne [string](Get-FileVersionText $winSwSource)) {
                throw '本次提供的构件来源、版本或许可证证据与已安装管理记录不一致。'
            }
            Assert-MarkerServiceIdentity -Layout $layout -Marker $existingMarker
            Assert-MarkerLicenseEvidence -Marker $existingMarker
            Assert-MarkerDistribution -Layout $layout -Marker $existingMarker
            Assert-ServiceConfiguration -Layout $layout -Service $serviceExisting
            Assert-ManagedArtifactsAcl -Layout $layout -ServiceSid ([string]$existingMarker.ServiceSid)
            return [pscustomobject]@{ Installed = $true; AlreadyInstalled = $true; ServiceName = $layout.ServiceName; Port = $Port; OwnershipRecorded = $true; DataPreservedOnUninstall = $true }
        } catch {
            throw ('已有 WangbinCollector-Redis 未通过完整归属校验；拒绝幂等接管：{0}。' -f $_.Exception.Message)
        }
    }
    foreach ($root in @($layout.ProductCodeRoot, $layout.ProductDataRoot)) {
        Assert-NoReparsePoint $root
        if (Test-Path -LiteralPath $root) { throw ('目标产品根目录已经存在，拒绝接管：{0}。' -f $root) }
    }
    Assert-PortVacant -Port $Port
    $createdPaths = New-Object System.Collections.Generic.List[string]
    $serviceInstalled = $false
    $installStage = 'INITIALIZE'
    try {
        $installStage = 'CREATE_PARENT_DIRECTORIES'
        $programFilesParent = [IO.Path]::GetDirectoryName($layout.ProductCodeRoot)
        $programDataParent = [IO.Path]::GetDirectoryName($layout.ProductDataRoot)
        foreach ($parent in @($programFilesParent, $programDataParent)) {
            Assert-NoReparsePoint $parent
            if (-not [IO.Directory]::Exists($parent)) { [IO.Directory]::CreateDirectory($parent) | Out-Null }
        }
        $installStage = 'CREATE_DEDICATED_DIRECTORIES'
        [IO.Directory]::CreateDirectory($layout.ProductCodeRoot) | Out-Null; $createdPaths.Add($layout.ProductCodeRoot)
        [IO.Directory]::CreateDirectory($layout.CodeRoot) | Out-Null; $createdPaths.Add($layout.CodeRoot)
        [IO.Directory]::CreateDirectory($layout.ProductDataRoot) | Out-Null; $createdPaths.Add($layout.ProductDataRoot)
        [IO.Directory]::CreateDirectory($layout.DataRoot) | Out-Null; $createdPaths.Add($layout.DataRoot)
        [IO.Directory]::CreateDirectory($layout.ConfigRoot) | Out-Null; $createdPaths.Add($layout.ConfigRoot)
        [IO.Directory]::CreateDirectory($layout.RedisDataRoot) | Out-Null; $createdPaths.Add($layout.RedisDataRoot)
        [IO.Directory]::CreateDirectory($layout.RedisLogRoot) | Out-Null; $createdPaths.Add($layout.RedisLogRoot)
        [IO.Directory]::CreateDirectory($layout.StateRoot) | Out-Null; $createdPaths.Add($layout.StateRoot)
        $installStage = 'APPLY_INITIAL_DIRECTORY_ACL'
        foreach ($entry in @(
            @($layout.ProductCodeRoot, 'Admin'), @($layout.CodeRoot, 'Admin'),
            @($layout.ProductDataRoot, 'Admin'), @($layout.DataRoot, 'Admin'),
            @($layout.ConfigRoot, 'Admin'), @($layout.RedisDataRoot, 'Admin'),
            @($layout.RedisLogRoot, 'Admin'), @($layout.StateRoot, 'Admin')
        )) { Set-ControlledDirectoryAcl -Path $entry[0] -Mode $entry[1] }
        $installStage = 'COPY_REDIS_DISTRIBUTION_CALL'
        $copiedDistribution = @(Copy-RedisDistribution -Distribution $redisDistribution -ArchivePath $redisArchive -DestinationRoot $layout.CodeRoot)
        $installStage = 'COPY_REDIS_DISTRIBUTION_RESULT'
        if ($copiedDistribution.Count -ne $redisDistribution.Files.Count) { throw ('Redis 发行文件复制结果数量不匹配：实际 {0}，预期 {1}。' -f $copiedDistribution.Count, $redisDistribution.Files.Count) }
        $redisDistribution = [pscustomobject][ordered]@{
            ArchivePath = $redisDistribution.ArchivePath
            ArchiveSha256 = $redisDistribution.ArchiveSha256
            Version = $redisDistribution.Version
            Architecture = $redisDistribution.Architecture
            RuntimeFamily = $redisDistribution.RuntimeFamily
            Files = $copiedDistribution
        }
        $installStage = 'COPY_WINSW_EXECUTABLE'
        [IO.File]::Copy($winSwSource, $layout.WrapperExecutable, $false)
        Set-ControlledFileAcl -Path $layout.WrapperExecutable -Mode Code
        Assert-Sha256 -Path $layout.WrapperExecutable -Expected $wrapperHash | Out-Null
        $installStage = 'WRITE_WINSW_XML'
        $xmlText = New-WinSwXml -Layout $layout
        Write-ControlledText -Path $layout.WrapperXml -Text $xmlText -Mode Code
        $installStage = 'WRITE_REDIS_SECRET'
        $password = New-RandomSecret
        $secretText = ([ordered]@{ RedisPassword = $password; CreatedUtc = [datetime]::UtcNow.ToString('o') } | ConvertTo-Json -Depth 4)
        Write-ControlledText -Path $layout.SecretFile -Text $secretText -Mode Admin
        $installStage = 'WRITE_REDIS_CONFIG'
        $configText = New-RedisConfigText -Layout $layout -Password $password
        Write-ControlledText -Path $layout.RedisConfig -Text $configText -Mode Read
        $password = $null; $secretText = $null; $configText = $null
        $installStage = 'WRITE_INSTALLING_MARKER'
        $marker = New-InitialMarker -Layout $layout -RedisDistribution $redisDistribution -RedisHash (Get-Sha256 $layout.RedisExecutable) -WrapperHash $wrapperHash -XmlHash (Get-Sha256 $layout.WrapperXml) -ConfigHash (Get-Sha256 $layout.RedisConfig) -SecretHash (Get-Sha256 $layout.SecretFile) -RedisVersion $script:RedisVersion -WrapperVersion (Get-FileVersionText $winSwSource) -RedisSource $RedisSourceDescription -WrapperSource $WinSwSourceDescription -RedisLicensePath $redisLicense -WrapperLicensePath $winSwLicense -RedisLicenseHash $redisLicenseHash -WrapperLicenseHash $wrapperLicenseHash
        Write-ControlledText -Path $layout.MarkerFile -Text ($marker | ConvertTo-Json -Depth 8) -Mode Admin
        $installStage = 'REGISTER_SERVICE'
        $deadline = [datetime]::UtcNow.AddSeconds(30)
        Invoke-NativeProcess -FilePath $layout.WrapperExecutable -Arguments 'install' -Deadline $deadline
        $serviceInstalled = $true
        $service = Get-ServiceRecord $layout.ServiceName
        if ($null -eq $service) { throw 'WinSW install 返回后 SCM 服务不存在。' }
        Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('sidtype "{0}" unrestricted' -f $layout.ServiceName) -Deadline ([datetime]::UtcNow.AddSeconds(10))
        Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('sdset "{0}" "D:P(A;;GA;;;SY)(A;;GA;;;BA)"' -f $layout.ServiceName) -Deadline ([datetime]::UtcNow.AddSeconds(10))
        $service = Get-ServiceRecord $layout.ServiceName
        Assert-ServiceConfiguration -Layout $layout -Service $service
        $serviceSid = Get-ServiceSid $layout.ServiceName
        foreach ($entry in @(
            @($layout.ProductCodeRoot, 'Code'), @($layout.CodeRoot, 'Code'),
            @($layout.ProductDataRoot, 'Traverse'), @($layout.DataRoot, 'Traverse'),
            @($layout.ConfigRoot, 'Read'), @($layout.RedisDataRoot, 'Write'), @($layout.RedisLogRoot, 'Write'),
            @($layout.StateRoot, 'Admin')
        )) { Set-ControlledDirectoryAcl -Path $entry[0] -Mode $entry[1] -ServiceSid $serviceSid }
        foreach ($file in @($redisDistribution.Files)) { Set-ControlledFileAcl -Path $file.Path -Mode Code -ServiceSid $serviceSid }
        Set-ControlledFileAcl -Path $layout.RedisExecutable -Mode Code -ServiceSid $serviceSid
        Set-ControlledFileAcl -Path $layout.WrapperExecutable -Mode Code -ServiceSid $serviceSid
        Set-ControlledFileAcl -Path $layout.WrapperXml -Mode Code -ServiceSid $serviceSid
        Set-ControlledFileAcl -Path $layout.RedisConfig -Mode Read -ServiceSid $serviceSid
        $marker.ServiceSid = $serviceSid
        $marker.RegistrationState = 'Installed'
        $marker.WrapperXmlSha256 = Get-Sha256 $layout.WrapperXml
        $marker.RedisConfigSha256 = Get-Sha256 $layout.RedisConfig
        Save-ManagedMarker -Layout $layout -Marker $marker
        [pscustomobject]@{ Installed = $true; AlreadyInstalled = $false; ServiceName = $layout.ServiceName; Port = $Port; OwnershipRecorded = $true; DataPreservedOnUninstall = $true }
    } catch {
        $reason = ('阶段 {0}：{1}' -f $installStage, $_.Exception.Message)
        $rollback = 'NOT_ATTEMPTED'
        try {
            $service = Get-ServiceRecord $layout.ServiceName
            if ($null -ne $service -and $serviceInstalled -and $service.State -eq 'Stopped' -and (Test-ServicePath -Actual $service.PathName -Expected $layout.WrapperExecutable -ExpectedTail '')) {
                Invoke-NativeProcess -FilePath $layout.WrapperExecutable -Arguments 'uninstall' -Deadline ([datetime]::UtcNow.AddSeconds(20))
                $rollback = 'OWNED_SERVICE_UNREGISTERED'
            } elseif ($null -ne $service) {
                $rollback = 'SERVICE_RETAINED_UNVERIFIED'
            } else {
                $rollback = 'NO_SERVICE_REGISTERED'
            }
        } catch { $rollback = 'ROLLBACK_BLOCKED' }
        throw ('MANAGED_REDIS 安装失败：{0}；回滚结果：{1}。产品目录保留供人工审计，不自动删除。' -f $reason, $rollback)
    } finally {
        $password = $null; $secretText = $null; $configText = $null
    }
    } finally {
        Exit-WangbinRedisOperationLock -Mutex $operationLock
    }
}

function Start-WangbinCollectorRedis {
    [CmdletBinding()]
    param([ValidateRange(1, 600)][int]$TimeoutSeconds = 60, [ValidateRange(1, 65535)][int]$Port = $script:DefaultPort)
    $operationLock = Enter-WangbinRedisOperationLock
    try {
    Assert-WindowsPlatform
    Assert-Administrator
    $layout = Get-WangbinCollectorRedisLayout -Port $Port
    $owned = Assert-OwnedService -Layout $layout -AllowStopped
    $service = $owned.Service
    if ($service.State -eq 'Running') {
        $status = Get-WangbinCollectorRedisStatus -Port $Port
        if ($status.Mode -eq 'MANAGED_REDIS' -and $status.Ready -and $status.OwnershipVerified) { return [pscustomobject]@{ Started = $true; AlreadyRunning = $true; Status = $status } }
        throw '服务显示 Running 但未通过本产品进程/端口/PING 验证，拒绝接管或重复启动。'
    }
    if ($service.State -in @('Start Pending', 'Stop Pending', 'Paused', 'Pause Pending', 'Continue Pending')) { throw ('服务处于过渡状态 {0}，拒绝重复启动。' -f $service.State) }
    Assert-PortVacant -Port $Port
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('start "{0}"' -f $layout.ServiceName) -Deadline $deadline
    while ([datetime]::UtcNow -lt $deadline) {
        $service = Get-ServiceRecord $layout.ServiceName
        $identity = Get-ManagedRedisProcessIdentity -Layout $layout -Service $service
        if ($service.State -eq 'Running' -and $identity.Owned -and (Test-OwnedListener -Layout $layout -Identity $identity)) {
            $password = Get-ManagedPassword -Layout $layout -Marker $owned.Marker
            try { $ping = Invoke-RedisPing -TargetHost '127.0.0.1' -Port $Port -Password $password -TimeoutMilliseconds (Get-RemainingMilliseconds -Deadline $deadline -Maximum 2000) -ExpectedProcessId ([int]$identity.RedisProcessId) } finally { $password = $null }
            if ($ping.Succeeded) {
                return [pscustomobject]@{ Started = $true; AlreadyRunning = $false; ServiceName = $layout.ServiceName; RedisProcessId = $identity.RedisProcessId; Ping = 'PONG'; Ready = $true }
            }
        }
        Start-Sleep -Milliseconds (Get-RemainingMilliseconds -Deadline $deadline -Maximum 200)
    }
    throw 'MANAGED_REDIS 启动超时；未将 SCM Running 或端口监听直接视为就绪。'
    } finally {
        Exit-WangbinRedisOperationLock -Mutex $operationLock
    }
}

function Stop-WangbinCollectorRedis {
    [CmdletBinding()]
    param([ValidateRange(1, 600)][int]$TimeoutSeconds = 120, [ValidateRange(1, 65535)][int]$Port = $script:DefaultPort)
    $operationLock = Enter-WangbinRedisOperationLock
    try {
    Assert-WindowsPlatform
    Assert-Administrator
    $layout = Get-WangbinCollectorRedisLayout -Port $Port
    $owned = Assert-OwnedService -Layout $layout -AllowStopped
    $service = $owned.Service
    if ($service.State -eq 'Stopped') {
        $listeners = @(Get-ListenerRecord -Port $Port)
        if ($listeners.Count -ne 0) { throw 'SCM 已停止但目标端口仍有监听者，停止结果 UNKNOWN。' }
        return [pscustomobject]@{ Stopped = $true; AlreadyStopped = $true; GracefulShutdownRequested = $false; GracefulShutdownVerified = $false; Diagnostic = 'AlreadyStoppedAndPortVacant' }
    }
    if ($service.State -notin @('Running', 'Stop Pending')) { throw ('服务处于状态 {0}，拒绝停止。' -f $service.State) }
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    $before = Get-ManagedRedisProcessIdentity -Layout $layout -Service $service
    $shutdownRequested = $false
    if ($service.State -in @('Running', 'Stop Pending')) {
        if (-not $before.Owned -or -not (Test-OwnedListener -Layout $layout -Identity $before)) {
            throw '停止前 Redis 未通过 wrapper/子进程/loopback 监听归属核验；拒绝发送 SHUTDOWN 或停止服务。'
        }
        $shutdown = Invoke-RedisShutdownSave -Layout $layout -Marker $owned.Marker -Deadline $deadline -ExpectedProcessId ([int]$before.RedisProcessId)
        $shutdownRequested = [bool]$shutdown.Dispatched
    }
    if ($service.State -eq 'Running') {
        $service = Get-ServiceRecord $layout.ServiceName
        if ($service.State -in @('Running', 'Stop Pending')) {
            try {
                Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('stop "{0}"' -f $layout.ServiceName) -Deadline $deadline
            } catch {
                $afterStopError = Get-ServiceRecord $layout.ServiceName
                if ($afterStopError.State -notin @('Stopped', 'Stop Pending')) { throw }
            }
        }
    }
    while ([datetime]::UtcNow -lt $deadline) {
        $service = Get-ServiceRecord $layout.ServiceName
        $identity = Get-ManagedRedisProcessIdentity -Layout $layout -Service $service
        $listeners = @(Get-ListenerRecord -Port $Port)
        $childPresent = $false
        if ($null -ne $before.RedisProcessId) {
            try {
                $current = Get-ProcessRecord -ProcessId $before.RedisProcessId
                $childPresent = $null -ne $current -and $current.CreationDate -eq $before.RedisCreationDate
            } catch { throw '无法确认受控 Redis 子进程是否退出，停止结果 UNKNOWN。' }
        }
        if ($service.State -eq 'Stopped' -and -not $childPresent -and $listeners.Count -eq 0) {
            $logEvidence = if ([IO.File]::Exists($layout.WrapperLog)) { 'WrapperLogAvailableForReview' } else { 'WrapperLogUnavailable' }
            return [pscustomobject]@{ Stopped = $true; AlreadyStopped = $false; GracefulShutdownRequested = $shutdownRequested; GracefulShutdownVerified = $false; Forced = $false; Diagnostic = ('ScmStoppedChildExitedPortVacant:' + $logEvidence) }
        }
        Start-Sleep -Milliseconds (Get-RemainingMilliseconds -Deadline $deadline -Maximum 200)
    }
    throw '停止超时；没有执行 taskkill/Stop-Process，也没有把未知状态当作成功。'
    } finally {
        Exit-WangbinRedisOperationLock -Mutex $operationLock
    }
}

function Restart-WangbinCollectorRedis {
    [CmdletBinding()]
    param([ValidateRange(1, 600)][int]$TimeoutSeconds = 120, [ValidateRange(1, 65535)][int]$Port = $script:DefaultPort)
    $operationLock = Enter-WangbinRedisOperationLock
    try {
    $null = Stop-WangbinCollectorRedis -TimeoutSeconds $TimeoutSeconds -Port $Port
    Start-WangbinCollectorRedis -TimeoutSeconds ([math]::Min($TimeoutSeconds, 120)) -Port $Port
    } finally {
        Exit-WangbinRedisOperationLock -Mutex $operationLock
    }
}

function Uninstall-WangbinCollectorRedis {
    [CmdletBinding()]
    param([ValidateRange(1, 600)][int]$TimeoutSeconds = 120, [ValidateRange(1, 65535)][int]$Port = $script:DefaultPort)
    $operationLock = Enter-WangbinRedisOperationLock
    try {
    Assert-WindowsPlatform
    Assert-Administrator
    $layout = Get-WangbinCollectorRedisLayout -Port $Port
    $owned = Assert-OwnedService -Layout $layout -AllowStopped
    $stopResult = $null
    if ($owned.Service.State -ne 'Stopped') { $stopResult = Stop-WangbinCollectorRedis -TimeoutSeconds $TimeoutSeconds -Port $Port }
    $listeners = @(Get-ListenerRecord -Port $Port)
    if ($listeners.Count -ne 0) { throw '卸载前目标端口仍有监听者；拒绝注销 SCM 服务。' }
    $service = Get-ServiceRecord $layout.ServiceName
    if ($null -ne $service -and -not (Test-ServicePath -Actual $service.PathName -Expected $layout.WrapperExecutable -ExpectedTail '')) { throw '卸载前 SCM 路径发生变化，拒绝注销服务。' }
    if ($null -ne $service) {
        Invoke-NativeProcess -FilePath $layout.WrapperExecutable -Arguments 'uninstall' -Deadline ([datetime]::UtcNow.AddSeconds(30))
        $deadline = [datetime]::UtcNow.AddSeconds(15)
        while ($null -ne (Get-ServiceRecord $layout.ServiceName)) {
            Start-Sleep -Milliseconds (Get-RemainingMilliseconds -Deadline $deadline -Maximum 200)
        }
    }
    $marker = $owned.Marker
    $marker.RegistrationState = 'Uninstalled'
    Save-ManagedMarker -Layout $layout -Marker $marker
    [pscustomobject]@{ Uninstalled = $true; ServiceName = $layout.ServiceName; CodePreserved = $true; ConfigPreserved = $true; DataPreserved = $true; SecretPreserved = $true; StopResult = $stopResult }
    } finally {
        Exit-WangbinRedisOperationLock -Mutex $operationLock
    }
}

Export-ModuleMember -Function Get-WangbinCollectorRedisLayout, Get-WangbinCollectorRedisStatus, Install-WangbinCollectorRedis, Start-WangbinCollectorRedis, Stop-WangbinCollectorRedis, Restart-WangbinCollectorRedis, Uninstall-WangbinCollectorRedis
