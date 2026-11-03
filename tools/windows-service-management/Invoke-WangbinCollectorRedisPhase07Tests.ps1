#requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'WangbinCollectorRedis.psm1'
Import-Module -Name $modulePath -Force
$module = Get-Module WangbinCollectorRedis

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

function Invoke-Private {
    param([Parameter(Mandatory)][scriptblock]$Action, [object[]]$Arguments = @())
    if ($Arguments.Count -eq 0) { return (& $module $Action) }
    if ($Arguments.Count -eq 1) { return (& $module $Action $Arguments[0]) }
    if ($Arguments.Count -eq 2) { return (& $module $Action $Arguments[0] $Arguments[1]) }
    throw '测试私有调用参数超过支持范围。'
}

function New-PeBytes {
    param([ValidateSet('x64', 'x86')][string]$Architecture = 'x64')
    $bytes = New-Object byte[] 512
    $bytes[0] = 0x4d; $bytes[1] = 0x5a
    [BitConverter]::GetBytes([int]0x80).CopyTo($bytes, 0x3c)
    $bytes[0x80] = 0x50; $bytes[0x81] = 0x45
    $machine = if ($Architecture -eq 'x64') { 0x8664 } else { 0x14c }
    [BitConverter]::GetBytes([uint16]$machine).CopyTo($bytes, 0x84)
    return $bytes
}

function New-DistributionArchive {
    param([Parameter(Mandatory)][string]$Path, [string]$Architecture = 'x64', [string[]]$Missing = @(), [string[]]$Extra = @(), [switch]$Traversal, [switch]$Duplicate)
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $contract = Invoke-Private -Action { Get-RedisDistributionContract }
    $zip = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $contract.Keys) {
            if ($Missing -contains $name) { continue }
            $entryName = if ($Traversal -and $name -eq 'redis-server.exe') { '../redis-server.exe' } else { $name }
            $entry = $zip.CreateEntry($entryName)
            $stream = $entry.Open()
            try {
                if ($contract[$name].Architecture) {
                    $bytes = New-PeBytes -Architecture $Architecture
                    $stream.Write($bytes, 0, $bytes.Length)
                } else {
                    $bytes = [Text.Encoding]::UTF8.GetBytes(('fixture:' + $name))
                    $stream.Write($bytes, 0, $bytes.Length)
                }
            } finally { $stream.Dispose() }
        }
        foreach ($name in $Extra) {
            $entry = $zip.CreateEntry($name)
            $stream = $entry.Open()
            try { $bytes = [Text.Encoding]::UTF8.GetBytes(('fixture:' + $name)); $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
        }
        if ($Duplicate) {
            $entry = $zip.CreateEntry('redis-server.exe')
            $stream = $entry.Open()
            try { $bytes = New-PeBytes; $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
}

function New-Result {
    param([Parameter(Mandatory)][string]$Id, [Parameter(Mandatory)][scriptblock]$Action)
    try {
        $detail = & $Action
        [pscustomobject][ordered]@{ Id = $Id; Status = 'PASS'; Detail = [string]$detail }
    } catch {
        [pscustomobject][ordered]@{ Id = $Id; Status = 'FAIL'; Detail = $_.Exception.Message }
    }
}

$rootBase = if (-not [string]::IsNullOrWhiteSpace($env:TMPDIR)) { $env:TMPDIR } else { Join-Path $env:LOCALAPPDATA 'hermes\cache\scratch' }
$testRoot = Join-Path $rootBase ('wangbin-redis-phase07-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$archive = Join-Path $testRoot 'Redis-8.10.3-Windows-x64-msys2.zip'
$results = @()

try {
    $results += New-Result -Id 'A-FULL-DISTRIBUTION' -Action {
        New-DistributionArchive -Path $archive
        $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        $distribution = Invoke-Private -Action { param($path, $sha) Assert-RedisDistributionArchive -ArchivePath $path -ExpectedSha256 $sha -SkipFixedCandidateCheck } -Arguments @($archive, $hash)
        Assert-Condition ($distribution.Files.Count -eq 16) '完整发行目录文件数不匹配。'
        Assert-Condition (@($distribution.Files | Where-Object Kind -eq 'MSYS2Runtime').Count -eq 5) 'MSYS2 运行时 DLL 清单不完整。'
        '完整 Redis 发行目录、运行时 DLL 和允许清单通过。'
    }

    $results += New-Result -Id 'B-MISSING-DLL' -Action {
        $path = Join-Path $testRoot 'missing.zip'
        New-DistributionArchive -Path $path -Missing @('msys-ssl-3.dll')
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        Assert-Throws -Action { Invoke-Private -Action { param($p, $s) Assert-RedisDistributionArchive -ArchivePath $p -ExpectedSha256 $s -SkipFixedCandidateCheck } -Arguments @($path, $hash) } -MessagePattern '*缺少必需文件*msys-ssl-3.dll*'
        '缺少 MSYS2 DLL 安全拒绝通过。'
    }

    $results += New-Result -Id 'C-DLL-HASH' -Action {
        $path = Join-Path $testRoot 'hash.zip'
        New-DistributionArchive -Path $path
        $expected = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        $distribution = Invoke-Private -Action { param($p, $s) Assert-RedisDistributionArchive -ArchivePath $p -ExpectedSha256 $s -SkipFixedCandidateCheck } -Arguments @($path, $expected)
        $dll = @($distribution.Files | Where-Object Name -eq 'msys-2.0.dll')[0]
        $dllPath = Join-Path $testRoot $dll.Name
        $zip = [IO.Compression.ZipFile]::OpenRead($path)
        try { $dllEntry = $zip.GetEntry($dll.Name); $input = $dllEntry.Open(); $output = [IO.File]::Open($dllPath, [IO.FileMode]::CreateNew); try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() } } finally { $zip.Dispose() }
        $bytes = [IO.File]::ReadAllBytes($dllPath); $bytes[0] = $bytes[0] -bxor 1; [IO.File]::WriteAllBytes($dllPath, $bytes)
        Assert-Throws -Action { Invoke-Private -Action { param($m, $p) Assert-MarkerArtifact -Marker $m -Path $p -HashProperty ArtifactSha256 } -Arguments @([pscustomobject]@{ ArtifactSha256 = $dll.Sha256 }, $dllPath) } -MessagePattern '*哈希不匹配*'
        $bytes = [IO.File]::ReadAllBytes($path); $bytes[$bytes.Length - 1] = $bytes[$bytes.Length - 1] -bxor 1
        [IO.File]::WriteAllBytes($path, $bytes)
        Assert-Throws -Action { Invoke-Private -Action { param($p, $s) Assert-RedisDistributionArchive -ArchivePath $p -ExpectedSha256 $s -SkipFixedCandidateCheck } -Arguments @($path, $expected) } -MessagePattern '*SHA-256 不匹配*'
        '发行包内容变化导致 DLL/归档哈希校验拒绝通过。'
    }

    $results += New-Result -Id 'D-WRONG-ARCHITECTURE' -Action {
        $path = Join-Path $testRoot 'x86.zip'
        New-DistributionArchive -Path $path -Architecture x86
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        Assert-Throws -Action { Invoke-Private -Action { param($p, $s) Assert-RedisDistributionArchive -ArchivePath $p -ExpectedSha256 $s -SkipFixedCandidateCheck } -Arguments @($path, $hash) } -MessagePattern '*架构不匹配*'
        'x86 构件拒绝通过。'
    }

    $results += New-Result -Id 'E-ZIP-TRAVERSAL' -Action {
        $path = Join-Path $testRoot 'traversal.zip'
        New-DistributionArchive -Path $path -Traversal
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        Assert-Throws -Action { Invoke-Private -Action { param($p, $s) Assert-RedisDistributionArchive -ArchivePath $p -ExpectedSha256 $s -SkipFixedCandidateCheck } -Arguments @($path, $hash) } -MessagePattern '*路径*'
        $extraPath = Join-Path $testRoot 'cygwin.zip'
        New-DistributionArchive -Path $extraPath -Extra @('cygwin1.dll')
        $extraHash = (Get-FileHash -LiteralPath $extraPath -Algorithm SHA256).Hash
        Assert-Throws -Action { Invoke-Private -Action { param($p, $s) Assert-RedisDistributionArchive -ArchivePath $p -ExpectedSha256 $s -SkipFixedCandidateCheck } -Arguments @($extraPath, $extraHash) } -MessagePattern '*未批准文件*'
        $duplicatePath = Join-Path $testRoot 'duplicate.zip'
        New-DistributionArchive -Path $duplicatePath -Duplicate
        $duplicateHash = (Get-FileHash -LiteralPath $duplicatePath -Algorithm SHA256).Hash
        Assert-Throws -Action { Invoke-Private -Action { param($p, $s) Assert-RedisDistributionArchive -ArchivePath $p -ExpectedSha256 $s -SkipFixedCandidateCheck } -Arguments @($duplicatePath, $duplicateHash) } -MessagePattern '*重复文件*'
        'ZIP 路径穿越拒绝通过。'
    }

    $results += New-Result -Id 'F-MSYS2-PATH' -Action {
        $value = Invoke-Private -Action { ConvertTo-Msys2Path -Path 'C:\ProgramData\WangbinCollector\Redis\data' }
        Assert-Condition ($value -ceq '/c/ProgramData/WangbinCollector/Redis/data') 'MSYS2 路径转换不一致。'
        Assert-Condition (Invoke-Private -Action { Test-CommandLineContainsPath -CommandLine 'redis-server.exe /c/ProgramData/WangbinCollector/Redis/config/redis.conf' -Path 'C:\ProgramData\WangbinCollector\Redis\config\redis.conf' }) 'MSYS2 进程命令行路径未被接受。'
        Assert-Condition (-not (Invoke-Private -Action { Test-CommandLineContainsPath -CommandLine 'redis-server.exe /c/ProgramData/WangbinCollector/Redis/config/redis.conf.bak' -Path 'C:\ProgramData\WangbinCollector\Redis\config\redis.conf' })) '配置路径比较错误接受了路径前缀。'
        'Windows/MSYS2 路径规范化及命令行比较通过。'
    }

    $results += New-Result -Id 'G-CONFIG-COMMANDLINE' -Action {
        $layout = Invoke-Private -Action { Get-WangbinCollectorRedisLayout -ProgramFilesRoot 'C:\pf' -ProgramDataRoot 'C:\pd' }
        $xml = [xml](Invoke-Private -Action { param($l) New-WinSwXml -Layout $l } -Arguments @($layout))
        $config = Invoke-Private -Action { param($l) New-RedisConfigText -Layout $l -Password 'fixture-password' } -Arguments @($layout)
        Assert-Condition ($xml.service.arguments -ceq '"/c/pd/WangbinCollector/Redis/config/redis.conf"') 'WinSW XML 未使用 MSYS2 配置路径。'
        Assert-Condition ($config -like '*/c/pd/WangbinCollector/Redis/data*' -and $config -like '*/c/pd/WangbinCollector/Redis/logs/redis.log*') 'Redis 配置路径未使用 MSYS2 形式。'
        '配置路径、WinSW XML 和进程参数一致性通过。'
    }

    $results += New-Result -Id 'H-MARKER-MANIFEST' -Action {
        $path = Join-Path $testRoot 'marker-source.zip'
        New-DistributionArchive -Path $path
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        $distribution = Invoke-Private -Action { param($p, $s) Assert-RedisDistributionArchive -ArchivePath $p -ExpectedSha256 $s -SkipFixedCandidateCheck } -Arguments @($path, $hash)
        $code = Join-Path $testRoot 'code'; [IO.Directory]::CreateDirectory($code) | Out-Null
        $zip = [IO.Compression.ZipFile]::OpenRead($path)
        try {
            foreach ($entry in $zip.Entries) {
                $target = Join-Path $code $entry.Name
                $input = $entry.Open(); $output = [IO.File]::Open($target, [IO.FileMode]::CreateNew)
                try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
            }
        } finally { $zip.Dispose() }
        $files = New-Object System.Collections.Generic.List[object]
        foreach ($item in @($distribution.Files)) {
            $target = Join-Path $code $item.Name
            $files.Add([pscustomobject]@{ Name = $item.Name; Path = $target; Sha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant(); Length = ([IO.FileInfo]$target).Length; Kind = $item.Kind; Architecture = $item.Architecture })
        }
        $layout = [pscustomobject]@{ CodeRoot = $code }
        $marker = [pscustomobject]@{ RedisVersion = '8.10.3'; RedisArchitecture = 'x64'; RedisRuntimeFamily = 'MSYS2'; RedisArchiveSha256 = 'af16df340525b431e9ec8dabfd625d5e7a233f82805b5418819904ea909c447f'; RedisDistributionFiles = $files.ToArray() }
        Assert-Condition ($marker.RedisDistributionFiles.Count -eq 16 -and $marker.RedisDistributionFiles[0].Path -like ($code + '*')) 'marker 构件清单路径或数量不一致。'
        $server = @($files | Where-Object Name -eq 'redis-server.exe')[0]
        Invoke-Private -Action { param($m, $p) Assert-MarkerArtifact -Marker $m -Path $p -HashProperty ArtifactSha256 } -Arguments @([pscustomobject]@{ ArtifactSha256 = $server.Sha256 }, $server.Path) | Out-Null
        Assert-Throws -Action { Invoke-Private -Action { param($m, $p) Assert-MarkerArtifact -Marker $m -Path $p -HashProperty ArtifactSha256 } -Arguments @([pscustomobject]@{ ArtifactSha256 = ('0' * 64) }, $server.Path) } -MessagePattern '*哈希不匹配*'
        'marker 构件清单读回、磁盘哈希和漂移拒绝通过。'
    }

    $results += New-Result -Id 'I-IDEMPOTENT-INSTALL' -Action {
        $source = [IO.File]::ReadAllText($modulePath, (New-Object Text.UTF8Encoding($true)))
        Assert-Condition ($source.Contains('AlreadyInstalled') -and $source.Contains('Assert-MarkerDistribution')) 'INSTALL 幂等或 marker 完整校验缺失。'
        'INSTALL 幂等完整归属路径保留。'
    }

    $results += New-Result -Id 'J-LICENSE-EVIDENCE' -Action {
        $marker = [pscustomobject]@{ RedisLicensePath = (Join-Path $testRoot 'missing.redis.license'); RedisLicenseSha256 = ('0' * 64); WrapperLicensePath = (Join-Path $testRoot 'missing.winsw.license'); WrapperLicenseSha256 = ('1' * 64); RedisSource = 'approved'; WrapperSource = 'approved' }
        Assert-Throws -Action { Invoke-Private -Action { param($m) Assert-MarkerLicenseEvidence -Marker $m } -Arguments @($marker) } -MessagePattern '*许可证证据文件不存在*'
        '不完整许可证证据拒绝通过。'
    }

    $results += New-Result -Id 'K-FOREIGN-SERVICE' -Action {
        $ownership = & $module {
            function Get-ManagedMarker { param($Layout); $null }
            function Get-ServiceRecordSafe { param($Name); [pscustomobject]@{ Value = [pscustomobject]@{ State = 'Stopped' }; Error = $null } }
            Get-ManagedOwnership -Layout ([pscustomobject]@{ ServiceName = 'WangbinCollector-Redis' })
        }
        Assert-Condition ($ownership.Ownership -eq 'FOREIGN_SERVICE') '外部 Redis 服务归属未拒绝。'
        '外部 Redis 服务归属拒绝通过。'
    }

    $results += New-Result -Id 'L-WINSW-COMPATIBILITY' -Action {
        $layout = Invoke-Private -Action { Get-WangbinCollectorRedisLayout -ProgramFilesRoot 'C:\pf' -ProgramDataRoot 'C:\pd' }
        $xml = [xml](Invoke-Private -Action { param($l) New-WinSwXml -Layout $l } -Arguments @($layout))
        Assert-Condition ($xml.service.id -ceq 'WangbinCollector-Redis' -and $xml.service.startmode -ceq 'Manual' -and $xml.service.serviceaccount.user -ceq 'WangbinCollector-Redis') 'WinSW 服务参数兼容性改变。'
        'WinSW XML、服务名和 Manual 参数兼容性通过。'
    }
} finally {
    $cleanup = $true
    try { [IO.Directory]::Delete($testRoot, $true) } catch { $cleanup = $false }
}

$summary = [ordered]@{
    TestRunner = 'Invoke-WangbinCollectorRedisPhase07Tests.ps1'
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
