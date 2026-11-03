#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('STATUS', 'INSTALL', 'START', 'STOP', 'RESTART', 'UNINSTALL')][string]$Operation,
    [string]$RedisDistributionArchive,
    [string]$ExpectedRedisArchiveSha256,
    [string]$WinSwExecutable,
    [string]$ExpectedWinSwSha256,
    [string]$RedisSourceDescription,
    [string]$WinSwSourceDescription,
    [string]$RedisLicenseFile,
    [string]$WinSwLicenseFile,
    [ValidateRange(1, 65535)][int]$Port = 6379,
    [string]$ExternalHost = '127.0.0.1',
    [switch]$PromptForExternalPassword
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'WangbinCollectorRedis.psm1'
Import-Module -Name $modulePath -Force

function Write-Result {
    param([Parameter(Mandatory)]$Value)
    $Value | ConvertTo-Json -Depth 12
}

try {
    switch ($Operation) {
        STATUS {
            $securePassword = $null
            if ($PromptForExternalPassword) {
                $securePassword = Read-Host -Prompt '请输入外部 Redis 密码（不会写入输出）' -AsSecureString
            }
            Write-Result (Get-WangbinCollectorRedisStatus -TargetHost $ExternalHost -Port $Port -ExternalPassword $securePassword)
        }
        INSTALL {
            foreach ($value in @($RedisDistributionArchive, $ExpectedRedisArchiveSha256, $WinSwExecutable, $ExpectedWinSwSha256, $RedisSourceDescription, $WinSwSourceDescription, $RedisLicenseFile, $WinSwLicenseFile)) {
                if ([string]::IsNullOrWhiteSpace($value)) { throw 'INSTALL 必须提供完整 Redis 发行包、WinSW、SHA-256、来源说明和许可证证据文件。' }
            }
            Write-Result (Install-WangbinCollectorRedis -RedisDistributionArchive $RedisDistributionArchive -ExpectedRedisArchiveSha256 $ExpectedRedisArchiveSha256 -WinSwExecutable $WinSwExecutable -ExpectedWinSwSha256 $ExpectedWinSwSha256 -RedisSourceDescription $RedisSourceDescription -WinSwSourceDescription $WinSwSourceDescription -RedisLicenseFile $RedisLicenseFile -WinSwLicenseFile $WinSwLicenseFile -Port $Port)
        }
        START { Write-Result (Start-WangbinCollectorRedis -TimeoutSeconds 60 -Port $Port) }
        STOP { Write-Result (Stop-WangbinCollectorRedis -TimeoutSeconds 120 -Port $Port) }
        RESTART { Write-Result (Restart-WangbinCollectorRedis -TimeoutSeconds 120 -Port $Port) }
        UNINSTALL { Write-Result (Uninstall-WangbinCollectorRedis -TimeoutSeconds 120 -Port $Port) }
    }
    exit 0
} catch {
    [pscustomobject]@{
        Operation = $Operation
        Succeeded = $false
        State = 'BLOCKED_OR_FAILED'
        Diagnostic = $_.Exception.Message
    } | ConvertTo-Json -Depth 8
    exit 1
}
