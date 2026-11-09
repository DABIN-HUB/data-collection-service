#requires -Version 5.1
# 本模块仅在显式调用运维接口时修改系统；Import 和路径查询没有系统写入副作用。
Set-StrictMode -Version Latest
$script:ErrorActionPreference = 'Stop'
$script:WinSwHash = 'b5066b7bbdfba1293e5d15cda3caaea88fbeab35bd5b38c41c913d492aadfc4f'
$script:RedisZipHash = '0198ef76124b92ea7c55a86c0d1b9d937370e72e3dcca536a08dde4c656bf3cd'
$script:ServiceSddl = 'D:P(A;;GA;;;SY)(A;;GA;;;BA)'
$script:Utf8 = New-Object System.Text.UTF8Encoding($false)

function Get-PrototypePaths {
  [CmdletBinding()]
  param()
  $code = 'C:/Program Files/REISUN-Prototype/Phase2A'
  $data = 'C:/ProgramData/REISUN-Prototype/Phase2A'
  [pscustomobject][ordered]@{
    CodeRoot = $code
    DataRoot = $data
    RedisWrapper = "$code/REISUN-Redis-Dev.exe"
    CollectorWrapper = "$code/REISUN-Collector-Dev.exe"
    RedisExecutable = "$code/redis/redis-server.exe"
    JavaExecutable = "$code/java/bin/java.exe"
    Jar = "$code/collector/collector.jar"
    RedisConfig = "$data/redis/config/redis.conf"
    CollectorConfig = "$data/collector/config/application-reisun-dev.yml"
    SecretFile = "$data/admin/secrets.json"
    MarkerFile = "$data/admin/phase2a-marker.json"
    RedisLogDirectory = "$data/redis/logs"
    CollectorLogDirectory = "$data/collector/logs"
  }
}

function Get-RoleDefinition {
  param([ValidateSet('Redis', 'Collector')][string]$Role)
  $p = Get-PrototypePaths
  if ($Role -eq 'Redis') {
    return [pscustomobject]@{
      Name = 'REISUN-Redis-Dev'; Wrapper = $p.RedisWrapper
      Xml = [IO.Path]::ChangeExtension($p.RedisWrapper, '.xml')
      Executable = $p.RedisExecutable; Port = 16379
      Arguments = ('"{0}"' -f (ConvertTo-CygwinPath $p.RedisConfig))
      WorkingDirectory = "$($p.DataRoot)/redis/data"; Logs = $p.RedisLogDirectory
    }
  }
  [pscustomobject]@{
    Name = 'REISUN-Collector-Dev'; Wrapper = $p.CollectorWrapper
    Xml = [IO.Path]::ChangeExtension($p.CollectorWrapper, '.xml')
    Executable = $p.JavaExecutable; Port = 19090
    Arguments = ('-jar "{0}" --spring.profiles.active=reisun-dev --spring.profiles.include= --spring.config.name=application --spring.config.location="classpath:/,file:{1}" --spring.config.additional-location= --spring.config.import=' -f $p.Jar, $p.CollectorConfig)
    WorkingDirectory = "$($p.DataRoot)/collector/data"; Logs = $p.CollectorLogDirectory
  }
}

function ConvertTo-CygwinPath {
  param([string]$Path)
  $full = [IO.Path]::GetFullPath($Path).Replace('\', '/')
  if ($full -notmatch '^([A-Za-z]):/(.*)$') { throw '必须使用本机盘符绝对路径。' }
  '/cygdrive/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2]
}

function Test-SamePath {
  param([string]$Left, [string]$Right)
  try {
    [string]::Equals([IO.Path]::GetFullPath($Left).TrimEnd('\', '/'), [IO.Path]::GetFullPath($Right).TrimEnd('\', '/'), [StringComparison]::OrdinalIgnoreCase)
  } catch { $false }
}

function Assert-Administrator {
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
  try {
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
      throw '需要已提升的管理员令牌；模块不会请求 UAC，也不会降级服务账号或放宽 ACL。'
    }
  } finally { $identity.Dispose() }
}

function Initialize-OwnerPrivilegeSupport {
  if ('Reisun.Phase2A.OwnerPrivilege' -as [type]) { return }
  # 仅显式运维操作需要这些原生调用；不在导入时编译或启用特权。
  $source = @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
namespace Reisun.Phase2A {
  public static class OwnerPrivilege {
    [StructLayout(LayoutKind.Sequential)] struct LUID { public UInt32 Low; public Int32 High; }
    [StructLayout(LayoutKind.Sequential)] struct TOKEN_PRIVILEGES { public UInt32 Count; public LUID Luid; public UInt32 Attributes; }

    [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool CloseHandle(IntPtr h);
    [DllImport("advapi32.dll", SetLastError=true)] static extern bool OpenProcessToken(IntPtr process, UInt32 access, out IntPtr token);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool LookupPrivilegeValue(string system, string name, out LUID luid);
    [DllImport("advapi32.dll", SetLastError=true)] static extern bool AdjustTokenPrivileges(IntPtr token, bool disableAll, ref TOKEN_PRIVILEGES privileges, UInt32 length, IntPtr previous, IntPtr returned);

    public static void EnableRestorePrivilege() {
      IntPtr token;
      if (!OpenProcessToken(GetCurrentProcess(), 0x28, out token)) throw new Win32Exception(Marshal.GetLastWin32Error());
      try {
        LUID luid;
        if (!LookupPrivilegeValue(null, "SeRestorePrivilege", out luid)) throw new Win32Exception(Marshal.GetLastWin32Error());
        TOKEN_PRIVILEGES p = new TOKEN_PRIVILEGES(); p.Count=1; p.Luid=luid; p.Attributes=2;
        if (!AdjustTokenPrivileges(token, false, ref p, 0, IntPtr.Zero, IntPtr.Zero)) throw new Win32Exception(Marshal.GetLastWin32Error());
        int error=Marshal.GetLastWin32Error(); if (error!=0) throw new Win32Exception(error);
      } finally { CloseHandle(token); }
    }

  }
}
'@
  Add-Type -TypeDefinition $source -Language CSharp -ErrorAction Stop | Out-Null
}

function Invoke-NativeProcess {
  param([string]$FilePath, [string]$Arguments, [int]$TimeoutMilliseconds = 30000, [switch]$CaptureOutput)
  if ($TimeoutMilliseconds -lt 1) { throw '操作预算已经耗尽。' }
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
    if (-not $process.Start()) { throw '无法创建运维请求进程。' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutMilliseconds)) {
      # 只结束发出 SCM 请求的 sc.exe/install 客户端，绝不终止服务或其子进程。
      try { $process.Kill() } catch { }
      throw ('原生运维请求超时：{0}；SCM 结果可能待确认。' -f [IO.Path]::GetFileName($FilePath))
    }
    $exitCode = $process.ExitCode
    if ($exitCode -ne 0) { throw ('原生运维请求失败：{0}，exit={1}。' -f [IO.Path]::GetFileName($FilePath), $exitCode) }
    if ($CaptureOutput) { return $stdout.GetAwaiter().GetResult() }
    # 不将原生 stdout/stderr 送入返回管道，防止包装器日志污染接口和泄露诊断参数。
    $null = $stdout.GetAwaiter().GetResult()
    $null = $stderr.GetAwaiter().GetResult()
  } finally { $process.Dispose() }
}

function Get-ScPath { Join-Path $env:SystemRoot 'System32/sc.exe' }

function Get-ServiceSid {
  param([string]$Name)
  $output = Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('showsid "{0}"' -f $Name) -TimeoutMilliseconds 5000 -CaptureOutput
  $match = [regex]::Match($output, 'S-1-5-80-(?:\d+-){4}\d+')
  if (-not $match.Success) { throw ('无法只读获取服务 SID：{0}。' -f $Name) }
  $match.Value
}

function Get-Sha256 {
  param([string]$Path)
  (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
}

function Assert-NoReparsePoint {
  param([string]$Path)
  $current = [IO.Path]::GetFullPath($Path)
  while (-not [string]::IsNullOrEmpty($current)) {
    if (Test-Path -LiteralPath $current) {
      $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
      if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw ('拒绝重解析路径：{0}。' -f $current) }
    }
    $parent = [IO.Path]::GetDirectoryName($current.TrimEnd('\', '/'))
    if ($parent -eq $current) { break }
    $current = $parent
  }
}

function New-ControlledAcl {
  param([string]$Mode, [bool]$Directory, [string]$RedisSid, [string]$CollectorSid)
  if ($Directory) { $acl = New-Object Security.AccessControl.DirectorySecurity }
  else { $acl = New-Object Security.AccessControl.FileSecurity }
  $acl.SetAccessRuleProtection($true, $false)
  $acl.SetOwner((New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))
  $inherit = [Security.AccessControl.InheritanceFlags]::None
  if ($Directory) { $inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit' }
  $propagate = [Security.AccessControl.PropagationFlags]::None
  foreach ($sid in @('S-1-5-18', 'S-1-5-32-544')) {
    $rule = New-Object Security.AccessControl.FileSystemAccessRule((New-Object Security.Principal.SecurityIdentifier($sid)), [Security.AccessControl.FileSystemRights]::FullControl, $inherit, $propagate, [Security.AccessControl.AccessControlType]::Allow)
    $acl.AddAccessRule($rule)
  }
  $readSids = @()
  $modifySids = @()
  $traverseSids = @()
  switch ($Mode) {
    Code { $readSids = @('S-1-5-32-545', $RedisSid, $CollectorSid) }
    DataRoot { $traverseSids = @($RedisSid, $CollectorSid) }
    RedisRoot { $modifySids = @($RedisSid) }
    CollectorRoot { $modifySids = @($CollectorSid) }
    RedisRead { $readSids = @($RedisSid) }
    CollectorRead { $readSids = @($CollectorSid) }
    RedisWrite { $modifySids = @($RedisSid) }
    CollectorWrite { $modifySids = @($CollectorSid) }
    Admin { }
    default { throw '未知 ACL 类别。' }
  }
  foreach ($sid in $readSids) {
    $rights = [Security.AccessControl.FileSystemRights]::ReadAndExecute
    if (-not $Directory -and $Mode -ne 'Code') { $rights = [Security.AccessControl.FileSystemRights]::Read }
    $rule = New-Object Security.AccessControl.FileSystemAccessRule((New-Object Security.Principal.SecurityIdentifier($sid)), $rights, $inherit, $propagate, [Security.AccessControl.AccessControlType]::Allow)
    $acl.AddAccessRule($rule)
  }
  foreach ($sid in $modifySids) {
    $rights = [Security.AccessControl.FileSystemRights]::FullControl
    $rule = New-Object Security.AccessControl.FileSystemAccessRule((New-Object Security.Principal.SecurityIdentifier($sid)), $rights, $inherit, $propagate, [Security.AccessControl.AccessControlType]::Allow)
    $acl.AddAccessRule($rule)
  }
  foreach ($sid in $traverseSids) {
    $rule = New-Object Security.AccessControl.FileSystemAccessRule((New-Object Security.Principal.SecurityIdentifier($sid)), [Security.AccessControl.FileSystemRights]'Traverse, ReadAttributes, Synchronize', [Security.AccessControl.InheritanceFlags]::None, $propagate, [Security.AccessControl.AccessControlType]::Allow)
    $acl.AddAccessRule($rule)
  }
  $acl
}

function Assert-ControlledAcl {
  param([string]$Path, [string]$Mode, [string]$RedisSid, [string]$CollectorSid)
  Assert-NoReparsePoint $Path
  $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
  $actual = Get-Acl -LiteralPath $Path -ErrorAction Stop
  $owner = $actual.GetOwner([Security.Principal.SecurityIdentifier]).Value
  if ($owner -ne 'S-1-5-18' -or -not $actual.AreAccessRulesProtected) { throw ('路径 owner/继承不受控：{0}。' -f $Path) }
  $expected = New-ControlledAcl -Mode $Mode -Directory $item.PSIsContainer -RedisSid $RedisSid -CollectorSid $CollectorSid
  # Windows 可补充不改变权限的 AutoInherited 标志；比较实际 ACE 而非整个 SDDL 字符串。
  $actualRules = @($actual.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]) | ForEach-Object {
    '{0}|{1}|{2}|{3}|{4}|{5}' -f $_.IdentityReference.Value, [long]$_.FileSystemRights, [int]$_.InheritanceFlags, [int]$_.PropagationFlags, [int]$_.AccessControlType, $_.IsInherited
  } | Sort-Object)
  $expectedRules = @($expected.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]) | ForEach-Object {
    '{0}|{1}|{2}|{3}|{4}|{5}' -f $_.IdentityReference.Value, [long]$_.FileSystemRights, [int]$_.InheritanceFlags, [int]$_.PropagationFlags, [int]$_.AccessControlType, $_.IsInherited
  } | Sort-Object)
  if (($actualRules -join ';') -cne ($expectedRules -join ';')) {
    throw ('路径 DACL 与最小授权不匹配：{0}。' -f $Path)
  }
}

function New-ControlledDirectory {
  param([string]$Path, [string]$Mode, [string]$RedisSid, [string]$CollectorSid)
  Assert-NoReparsePoint $Path
  if (Test-Path -LiteralPath $Path) { throw ('目标目录已经存在，拒绝接管：{0}。' -f $Path) }
  $acl = New-ControlledAcl -Mode $Mode -Directory $true -RedisSid $RedisSid -CollectorSid $CollectorSid
  $null = [IO.Directory]::CreateDirectory($Path, $acl)
  Assert-ControlledAcl -Path $Path -Mode $Mode -RedisSid $RedisSid -CollectorSid $CollectorSid
}

function Set-ControlledFileAcl {
  param([string]$Path, [string]$Mode, [string]$RedisSid, [string]$CollectorSid)
  $acl = New-ControlledAcl -Mode $Mode -Directory $false -RedisSid $RedisSid -CollectorSid $CollectorSid
  Set-Acl -LiteralPath $Path -AclObject $acl -ErrorAction Stop
}

function Write-ControlledText {
  param([string]$Path, [string]$Text, [string]$Mode, [string]$RedisSid, [string]$CollectorSid, [switch]$Replace)
  Assert-NoReparsePoint $Path
  if (-not $Replace -and (Test-Path -LiteralPath $Path)) { throw ('拒绝覆盖既有文件：{0}。' -f $Path) }
  # 父目录在写入前已经受控，临时文件只存在于同一受控目录。
  $temporary = $Path + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
  $backup = $Path + '.' + [guid]::NewGuid().ToString('N') + '.bak'
  try {
    [IO.File]::WriteAllText($temporary, $Text, $script:Utf8)
    Set-ControlledFileAcl -Path $temporary -Mode $Mode -RedisSid $RedisSid -CollectorSid $CollectorSid
    if ($Replace -and [IO.File]::Exists($Path)) { [IO.File]::Replace($temporary, $Path, $backup) }
    else { [IO.File]::Move($temporary, $Path) }
  } finally {
    if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    if ([IO.File]::Exists($backup)) { [IO.File]::Delete($backup) }
  }
}

function Get-MachineInjectionNames {
  $names = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
  foreach ($name in @('SPRING_APPLICATION_JSON', 'SPRING_PROFILES_ACTIVE', 'SPRING_PROFILES_INCLUDE', 'SPRING_CONFIG_NAME', 'SPRING_CONFIG_LOCATION', 'SPRING_CONFIG_ADDITIONAL_LOCATION', 'SPRING_CONFIG_IMPORT', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTIONS', 'CLASSPATH', 'JAVA_HOME')) { $null = $names.Add($name) }
  $key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\CurrentControlSet\Control\Session Manager\Environment', $false)
  if ($null -eq $key) { throw '无法只读检查机器环境变量名称。' }
  try {
    # 仅获取名字，不读取或回显任何原机器环境变量值。
    foreach ($name in $key.GetValueNames()) {
      if ($name -match '^(SPRING_|COLLECTOR_|TELEMETRY_)' -or $name -match '^(JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS)$') { $null = $names.Add($name) }
    }
  } finally { $key.Dispose() }
  @($names | Sort-Object)
}

function Assert-EnvironmentCoverage {
  param($Marker)
  foreach ($name in @(Get-MachineInjectionNames)) {
    if (@($Marker.EnvironmentCleared) -notcontains $name) { throw '机器级启动注入变量名称发生变化，拒绝启动；需管理员重新审查受限 wrapper 配置。' }
  }
}

function Get-ServiceRecord {
  param([string]$Name)
  Get-CimInstance -ClassName Win32_Service -Filter ("Name='{0}'" -f $Name) -OperationTimeoutSec 2 -ErrorAction Stop
}

function Get-RemainingMilliseconds {
  param([datetime]$Deadline, [int]$Maximum = 2000)
  $remaining = [math]::Floor(($Deadline - [datetime]::UtcNow).TotalMilliseconds)
  if ($remaining -lt 1) { throw '操作 deadline 已到，不能继续发送请求。' }
  if ($remaining -gt [double]$Maximum) { return $Maximum }
  [int]$remaining
}

function Get-AclModeForPath {
  param([string]$Path)
  $p = Get-PrototypePaths
  $full = [IO.Path]::GetFullPath($Path).Replace('\', '/').TrimEnd('/')
  if ((Test-SamePath $full $p.CodeRoot) -or $full.StartsWith($p.CodeRoot + '/', [StringComparison]::OrdinalIgnoreCase)) { return 'Code' }
  if (Test-SamePath $full $p.DataRoot) { return 'DataRoot' }
  foreach ($role in @('Redis', 'Collector')) {
    $root = $p.DataRoot + '/' + $role.ToLowerInvariant()
    if (Test-SamePath $full $root) { return $role + 'Root' }
    if ($full.StartsWith($root + '/config', [StringComparison]::OrdinalIgnoreCase)) { return $role + 'Read' }
    if ($full.StartsWith($root + '/data', [StringComparison]::OrdinalIgnoreCase) -or $full.StartsWith($root + '/logs', [StringComparison]::OrdinalIgnoreCase)) { return $role + 'Write' }
  }
  if ($full.StartsWith($p.DataRoot + '/admin', [StringComparison]::OrdinalIgnoreCase)) { return 'Admin' }
  throw '路径不在固定受控布局中。'
}

function Assert-ProductParents {
  param([string]$RedisSid, [string]$CollectorSid, [switch]$Create)
  $p = Get-PrototypePaths
  foreach ($entry in @(@([IO.Path]::GetDirectoryName($p.CodeRoot), 'Code'), @([IO.Path]::GetDirectoryName($p.DataRoot), 'DataRoot'))) {
    $parent = [string]$entry[0]
    Assert-NoReparsePoint $parent
    if (-not (Test-Path -LiteralPath $parent)) {
      if (-not $Create) { throw '产品父目录不存在。' }
      New-ControlledDirectory -Path $parent -Mode $entry[1] -RedisSid $RedisSid -CollectorSid $CollectorSid
    } else {
      # 只管理本产品树；既有父目录权限不合格时拒绝，不原地修改其他目录。
      Assert-ControlledAcl -Path $parent -Mode $entry[1] -RedisSid $RedisSid -CollectorSid $CollectorSid
    }
  }
}

function Read-PrototypeMarker {
  param([switch]$Full, [datetime]$Deadline = [datetime]::MaxValue)
  $p = Get-PrototypePaths
  Assert-NoReparsePoint $p.MarkerFile
  if (-not [IO.File]::Exists($p.MarkerFile)) { throw '没有受保护的 Phase2A marker；拒绝管理。' }
  # 先验证不依赖 marker 内容的 admin 边界，再解析身份记录。
  foreach ($path in @($p.DataRoot, "$($p.DataRoot)/admin", $p.MarkerFile)) {
    $acl = Get-Acl -LiteralPath $path -ErrorAction Stop
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne 'S-1-5-18' -or -not $acl.AreAccessRulesProtected) { throw 'Marker 所在边界不受控。' }
    foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
      $sid = $rule.IdentityReference.Value
      $writeMask = [Security.AccessControl.FileSystemRights]'Write, Delete, DeleteSubdirectoriesAndFiles, ChangePermissions, TakeOwnership'
      if ($sid -notin @('S-1-5-18', 'S-1-5-32-544') -and ($rule.FileSystemRights -band $writeMask) -ne 0) { throw 'Marker 父目录存在非管理员写入权限。' }
      if ($path -ne $p.DataRoot -and $sid -notin @('S-1-5-18', 'S-1-5-32-544')) { throw 'Marker/秘密目录授予了非管理员访问。' }
    }
  }
  try { $marker = [IO.File]::ReadAllText($p.MarkerFile, $script:Utf8) | ConvertFrom-Json -ErrorAction Stop }
  catch { throw 'Marker 无法解析，拒绝管理。' }
  if ($marker.Product -ne 'REISUN-Phase2A' -or $marker.Schema -ne 1 -or $marker.Nonce -notmatch '^[a-f0-9]{64}$' -or -not (Test-SamePath $marker.CodeRoot $p.CodeRoot) -or -not (Test-SamePath $marker.DataRoot $p.DataRoot)) { throw 'Marker 产品/固定根路径身份不匹配。' }
  foreach ($role in @('Redis', 'Collector')) {
    $d = Get-RoleDefinition $role
    $record = $marker.Services.$role
    if ($record.Name -cne $d.Name -or -not (Test-SamePath $record.Wrapper $d.Wrapper) -or $record.Sid -notmatch '^S-1-5-80-(?:\d+-){4}\d+$') { throw 'Marker 服务身份不匹配。' }
  }
  if ($marker.Services.Redis.Sid -eq $marker.Services.Collector.Sid) { throw '两个角色的服务 SID 不能相同。' }
  Assert-ProductParents -RedisSid $marker.Services.Redis.Sid -CollectorSid $marker.Services.Collector.Sid
  foreach ($path in @($p.CodeRoot, $p.DataRoot, "$($p.DataRoot)/admin", $p.MarkerFile)) {
    Assert-ControlledAcl -Path $path -Mode (Get-AclModeForPath $path) -RedisSid $marker.Services.Redis.Sid -CollectorSid $marker.Services.Collector.Sid
  }
  foreach ($role in @('Redis', 'Collector')) {
    $root = "$($p.DataRoot)/$($role.ToLowerInvariant())"
    foreach ($path in @($root, "$root/config", "$root/data", "$root/logs")) {
      Assert-ControlledAcl -Path $path -Mode (Get-AclModeForPath $path) -RedisSid $marker.Services.Redis.Sid -CollectorSid $marker.Services.Collector.Sid
    }
  }
  if ($Full) {
    foreach ($root in @($p.CodeRoot, $p.DataRoot)) {
      foreach ($item in @(Get-ChildItem -LiteralPath $root -Recurse -Force -ErrorAction Stop)) {
        $null = Get-RemainingMilliseconds $Deadline
        Assert-NoReparsePoint $item.FullName
        $mode = Get-AclModeForPath $item.FullName
        if ($mode -notin @('RedisWrite', 'CollectorWrite')) {
          Assert-ControlledAcl -Path $item.FullName -Mode $mode -RedisSid $marker.Services.Redis.Sid -CollectorSid $marker.Services.Collector.Sid
        }
      }
    }
    foreach ($entry in @($marker.Artifacts)) {
      $null = Get-RemainingMilliseconds $Deadline
      Assert-Artifact -Marker $marker -Path $entry.Path
    }
  }
  $marker
}

function Assert-Artifact {
  param($Marker, [string]$Path)
  $matches = @($Marker.Artifacts | Where-Object { Test-SamePath $_.Path $Path })
  if ($matches.Count -ne 1) { throw ('构件没有唯一受保护哈希记录：{0}（匹配数={1}）。' -f $Path, $matches.Count) }
  $shaProperty = $matches[0].PSObject.Properties['Sha256']
  if ($null -eq $shaProperty -or [string]$shaProperty.Value -notmatch '^[a-f0-9]{64}$') { throw ('构件哈希字段缺失或格式错误：{0}。' -f $Path) }
  # 不接受 marker 把核心构件路径重定向到别处。
  $mode = Get-AclModeForPath $Path
  Assert-ControlledAcl -Path $Path -Mode $mode -RedisSid $Marker.Services.Redis.Sid -CollectorSid $Marker.Services.Collector.Sid
  $expectedSha = [string]$shaProperty.Value
  $parent = [IO.Path]::GetDirectoryName($Path)
  $p = Get-PrototypePaths
  while (-not (Test-SamePath $parent $p.CodeRoot) -and -not (Test-SamePath $parent $p.DataRoot)) {
    Assert-ControlledAcl -Path $parent -Mode (Get-AclModeForPath $parent) -RedisSid $Marker.Services.Redis.Sid -CollectorSid $Marker.Services.Collector.Sid
    $parent = [IO.Path]::GetDirectoryName($parent)
  }
  if ((Get-Sha256 $Path) -cne $expectedSha) { throw ('构件 SHA256 改变：{0}。' -f [IO.Path]::GetFileName($Path)) }
}

function Save-PrototypeMarker {
  param($Marker)
  Assert-Administrator
  Initialize-OwnerPrivilegeSupport
  [Reisun.Phase2A.OwnerPrivilege]::EnableRestorePrivilege()
  $p = Get-PrototypePaths
  Write-ControlledText -Path $p.MarkerFile -Text ($Marker | ConvertTo-Json -Depth 12) -Mode Admin -RedisSid $Marker.Services.Redis.Sid -CollectorSid $Marker.Services.Collector.Sid -Replace
  $saved = Read-PrototypeMarker
  foreach ($role in @('Redis', 'Collector')) {
    if ($saved.Nonce -cne $Marker.Nonce -or $saved.Services.$role.Registered -ne $Marker.Services.$role.Registered -or $saved.Services.$role.RegistrationState -cne $Marker.Services.$role.RegistrationState -or $saved.Services.$role.EventSource.CreatedByPrototype -ne $Marker.Services.$role.EventSource.CreatedByPrototype) { throw 'Marker 原子更新后的身份记录不匹配。' }
  }
}

function New-RandomHex {
  $bytes = New-Object byte[] 32
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes); ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant() }
  finally { $rng.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
}

function Read-PrototypeSecrets {
  param($Marker)
  $p = Get-PrototypePaths
  Assert-Artifact -Marker $Marker -Path $p.SecretFile
  try { $secret = [IO.File]::ReadAllText($p.SecretFile, $script:Utf8) | ConvertFrom-Json -ErrorAction Stop }
  catch { throw '本轮受限秘密文件无法解析。' }
  if ($secret.RedisPassword -notmatch '^[a-f0-9]{64}$' -or $secret.AuthToken -notmatch '^[a-f0-9]{64}$') { throw '本轮受限秘密格式不正确。' }
  $secret
}

function New-WrapperXml {
  param([string]$Role, [string[]]$EnvironmentNames)
  $d = Get-RoleDefinition $Role
  $document = New-Object Xml.XmlDocument
  $null = $document.AppendChild($document.CreateXmlDeclaration('1.0', 'utf-8', $null))
  $root = $document.CreateElement('service')
  $null = $document.AppendChild($root)
  $values = [ordered]@{
    id = $d.Name; name = $d.Name; description = 'REISUN Phase2A isolated prototype'
    executable = $d.Executable; arguments = $d.Arguments
    workingdirectory = $d.WorkingDirectory; logpath = $d.Logs
    startmode = 'Manual'; hidewindow = 'false'; stoptimeout = '120 sec'
    stopparentprocessfirst = 'true'; resetfailure = '1 hour'
    securityDescriptor = $script:ServiceSddl
  }
  foreach ($key in $values.Keys) {
    $element = $document.CreateElement($key); $element.InnerText = $values[$key]
    $null = $root.AppendChild($element)
  }
  if ($Role -eq 'Collector') {
    $element = $document.CreateElement('depend'); $element.InnerText = 'REISUN-Redis-Dev'; $null = $root.AppendChild($element)
  }
  $account = $document.CreateElement('serviceaccount')
  foreach ($pair in @(@('domain', 'NT SERVICE'), @('user', $d.Name), @('allowservicelogon', 'false'))) {
    $element = $document.CreateElement($pair[0]); $element.InnerText = $pair[1]; $null = $account.AppendChild($element)
  }
  $null = $root.AppendChild($account)
  foreach ($delay in @('5 sec', '10 sec', '15 sec', '')) {
    $element = $document.CreateElement('onfailure')
    if ($delay -eq '') { $element.SetAttribute('action', 'none') }
    else { $element.SetAttribute('action', 'restart'); $element.SetAttribute('delay', $delay) }
    $null = $root.AppendChild($element)
  }
  foreach ($name in $EnvironmentNames) {
    $element = $document.CreateElement('env'); $element.SetAttribute('name', $name); $element.SetAttribute('value', '')
    $null = $root.AppendChild($element)
  }
  $log = $document.CreateElement('log'); $log.SetAttribute('mode', 'roll-by-size')
  foreach ($pair in @(@('sizeThreshold', '10240'), @('keepFiles', '5'))) {
    $element = $document.CreateElement($pair[0]); $element.InnerText = $pair[1]; $null = $log.AppendChild($element)
  }
  $null = $root.AppendChild($log)
  $document.OuterXml
}

function Initialize-Prototype {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory)][string]$WinSwPath,
    [Parameter(Mandatory)][string]$RedisZip,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$CollectorJar,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedWinSwSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedJarSha256
  )
  Assert-Administrator
  $p = Get-PrototypePaths
  foreach ($role in @('Redis', 'Collector')) {
    if ($null -ne (Get-ServiceRecord (Get-RoleDefinition $role).Name)) { throw '固定服务名已经存在；初始化拒绝接管。' }
  }
  foreach ($root in @($p.CodeRoot, $p.DataRoot)) {
    Assert-NoReparsePoint $root
    if (Test-Path -LiteralPath $root) { throw ('隔离根目录已经存在：{0}。' -f $root) }
  }
  foreach ($path in @($WinSwPath, $RedisZip, $CollectorJar, $JavaHome)) { Assert-NoReparsePoint $path }
  if ($ExpectedWinSwSha256.ToLowerInvariant() -cne $script:WinSwHash -or (Get-Sha256 $WinSwPath) -cne $script:WinSwHash) { throw 'WinSW 不是已固定审核的 v2.12.0 NET461 构件。' }
  if ((Get-Sha256 $RedisZip) -cne $script:RedisZipHash) { throw 'Redis ZIP SHA256 不匹配。' }
  if ((Get-Sha256 $CollectorJar) -cne $ExpectedJarSha256.ToLowerInvariant()) { throw 'Collector JAR SHA256 不匹配。' }
  if (-not [IO.File]::Exists((Join-Path $JavaHome 'bin/java.exe')) -or -not [IO.File]::Exists((Join-Path $JavaHome 'release'))) { throw 'JavaHome 缺少固定 java.exe/release。' }
  if ([IO.File]::ReadAllText((Join-Path $JavaHome 'release')) -notmatch '(?m)^JAVA_VERSION="17(?:\.|\")') { throw '只允许 Java 17。' }
  $javaItems = @(Get-ChildItem -LiteralPath $JavaHome -Recurse -Force -ErrorAction Stop)
  foreach ($item in $javaItems) { if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'JavaHome 包含重解析文件；拒绝复制。' } }
  Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
  $archive = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($RedisZip))
  $allowed = @('redis-server.exe', 'redis-cli.exe', 'cygwin1.dll', 'cyggcc_s-seh-1.dll', 'cygstdc++-6.dll', 'cygiconv-2.dll', 'cygintl-8.dll', 'cygssl-3.dll', 'cygcrypto-3.dll', 'cygz.dll')
  $selected = @{}
  try {
    foreach ($entry in $archive.Entries) {
      $name = $entry.FullName.Replace('\', '/')
      if ($name -match '^/|^[A-Za-z]:|(^|/)\.\.(/|$)|:' -or (($entry.ExternalAttributes -shr 16) -band 0xF000) -eq 0xA000) { throw 'ZIP 含非法路径或符号链接。' }
      $base = [IO.Path]::GetFileName($name)
      if ($allowed -contains $base) {
        if ($selected.ContainsKey($base) -or $entry.Length -le 0) { throw 'ZIP 核心成员重复或为空。' }
        $selected[$base] = $entry
      }
    }
    if ($selected.Count -ne $allowed.Count) { throw 'ZIP 缺少 server/cli 或固定 8 DLL。' }
    $redisSid = Get-ServiceSid 'REISUN-Redis-Dev'
    $collectorSid = Get-ServiceSid 'REISUN-Collector-Dev'
    $envNames = @(Get-MachineInjectionNames)
    $events = @{}
    foreach ($role in @('Redis', 'Collector')) {
      $name = (Get-RoleDefinition $role).Name
      $events[$role] = [ordered]@{ ExistedBefore = [Diagnostics.EventLog]::SourceExists($name); CreatedByPrototype = $false }
    }
    Initialize-OwnerPrivilegeSupport
    [Reisun.Phase2A.OwnerPrivilege]::EnableRestorePrivilege()
    Assert-ProductParents -RedisSid $redisSid -CollectorSid $collectorSid -Create
    New-ControlledDirectory -Path $p.CodeRoot -Mode Code -RedisSid $redisSid -CollectorSid $collectorSid
    New-ControlledDirectory -Path $p.DataRoot -Mode DataRoot -RedisSid $redisSid -CollectorSid $collectorSid
    foreach ($relative in @('redis', 'java', 'collector')) {
      New-ControlledDirectory -Path "$($p.CodeRoot)/$relative" -Mode Code -RedisSid $redisSid -CollectorSid $collectorSid
    }
    New-ControlledDirectory -Path "$($p.DataRoot)/admin" -Mode Admin -RedisSid $redisSid -CollectorSid $collectorSid
    foreach ($role in @('Redis', 'Collector')) {
      $base = "$($p.DataRoot)/$($role.ToLowerInvariant())"
      New-ControlledDirectory -Path $base -Mode ($role + 'Root') -RedisSid $redisSid -CollectorSid $collectorSid
      foreach ($sub in @('config', 'data', 'logs')) {
        $mode = $role + 'Write'; if ($sub -eq 'config') { $mode = $role + 'Read' }
        New-ControlledDirectory -Path "$base/$sub" -Mode $mode -RedisSid $redisSid -CollectorSid $collectorSid
      }
      if ($role -eq 'Redis') { New-ControlledDirectory -Path "$base/data/appendonlydir" -Mode RedisWrite -RedisSid $redisSid -CollectorSid $collectorSid }
    }
    foreach ($base in $allowed) {
      $target = "$($p.CodeRoot)/redis/$base"
      [IO.Compression.ZipFileExtensions]::ExtractToFile($selected[$base], $target, $false)
      Set-ControlledFileAcl -Path $target -Mode Code -RedisSid $redisSid -CollectorSid $collectorSid
    }
    $sourceRoot = [IO.Path]::GetFullPath($JavaHome).TrimEnd('\', '/')
    foreach ($item in @($javaItems | Sort-Object { $_.FullName.Length })) {
      $relative = $item.FullName.Substring($sourceRoot.Length).TrimStart('\', '/')
      $target = Join-Path "$($p.CodeRoot)/java" $relative
      if ($item.PSIsContainer) { New-ControlledDirectory -Path $target -Mode Code -RedisSid $redisSid -CollectorSid $collectorSid }
      else { [IO.File]::Copy($item.FullName, $target, $false); Set-ControlledFileAcl -Path $target -Mode Code -RedisSid $redisSid -CollectorSid $collectorSid }
    }
    foreach ($target in @($p.RedisWrapper, $p.CollectorWrapper)) {
      [IO.File]::Copy([IO.Path]::GetFullPath($WinSwPath), $target, $false)
      Set-ControlledFileAcl -Path $target -Mode Code -RedisSid $redisSid -CollectorSid $collectorSid
    }
    [IO.File]::Copy([IO.Path]::GetFullPath($CollectorJar), $p.Jar, $false)
    Set-ControlledFileAcl -Path $p.Jar -Mode Code -RedisSid $redisSid -CollectorSid $collectorSid
    if ((Get-Sha256 $p.Jar) -cne $ExpectedJarSha256.ToLowerInvariant()) { throw '复制后的 JAR 哈希不匹配。' }
    $redisPassword = New-RandomHex
    $authToken = New-RandomHex
    $secretText = [ordered]@{ RedisPassword = $redisPassword; AuthToken = $authToken } | ConvertTo-Json
    Write-ControlledText -Path $p.SecretFile -Text $secretText -Mode Admin -RedisSid $redisSid -CollectorSid $collectorSid
    $redisText = @"
bind 127.0.0.1
port 16379
protected-mode yes
daemonize no
databases 1
requirepass $redisPassword
maxmemory 256mb
maxmemory-policy noeviction
appendonly yes
appendfsync everysec
dir "$($p.DataRoot)/redis/data"
logfile "$($p.RedisLogDirectory)/redis.log"
save 900 1
save 300 10
save 60 10000
"@
    Write-ControlledText -Path $p.RedisConfig -Text $redisText -Mode RedisRead -RedisSid $redisSid -CollectorSid $collectorSid
    $collectorText = @"
server:
  address: 127.0.0.1
  port: 19090
  servlet:
    context-path: /collector
  shutdown: graceful
spring:
  main:
    register-shutdown-hook: true
  lifecycle:
    timeout-per-shutdown-phase: 35s
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration
  sql:
    init:
      mode: never
  data:
    redis:
      host: 127.0.0.1
      port: 16379
      database: 0
      password: '$redisPassword'
      timeout: 2s
      connect-timeout: 2s
management:
  health:
    db:
      enabled: false
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,redis
      status:
        http-mapping:
          down: 503
          out-of-service: 503
telemetry:
  tdengine:
    enabled: false
wangbin:
  config:
    persistence:
      mode: sqlite
    sqlite:
      enabled: true
      database-path: '$($p.DataRoot)/collector/data/sqlite/collector.db'
collector:
  config:
    loader: file
    yun-url: http://127.0.0.1:1
    local-device-snapshot-file: '$($p.DataRoot)/collector/data/local-device-configs.json'
    file:
      devices: ''
      points-dir: ''
      connections-dir: ''
  report:
    enabled: false
    mqtt:
      enabled: false
    outbox:
      enabled: false
  auth:
    enabled: true
    allow-ip-authentication: false
    permit-all-paths: ['/health', '/actuator/health', '/actuator/health/**']
    token-header: X-Collector-Token
    default-required-scope: VIEW
    ops-tokens:
      '$authToken': phase2a-operator
    ops-scopes:
      phase2a-operator: [VIEW, CONFIG_MANAGE, DEVICE_CONTROL]
    access-rules:
      - methods: [POST, PUT, DELETE]
        paths: ['/api/config/**']
        required-scope: CONFIG_MANAGE
      - methods: [POST]
        paths: ['/api/device/**']
        required-scope: DEVICE_CONTROL
logging:
  level:
    com.wangbin.collector.GracefulShutdown: INFO
  file:
    name: '$($p.CollectorLogDirectory)/collector.log'
  logback:
    rollingpolicy:
      max-file-size: 10MB
      max-history: 5
      total-size-cap: 50MB
"@
    Write-ControlledText -Path $p.CollectorConfig -Text $collectorText -Mode CollectorRead -RedisSid $redisSid -CollectorSid $collectorSid
    $redisPassword = $null; $authToken = $null; $secretText = $null; $redisText = $null; $collectorText = $null
    foreach ($role in @('Redis', 'Collector')) {
      Write-ControlledText -Path (Get-RoleDefinition $role).Xml -Text (New-WrapperXml -Role $role -EnvironmentNames $envNames) -Mode Code -RedisSid $redisSid -CollectorSid $collectorSid
    }
    $artifacts = New-Object 'Collections.Generic.List[object]'
    foreach ($item in @(Get-ChildItem -LiteralPath $p.CodeRoot -File -Recurse -Force -ErrorAction Stop)) {
      $artifacts.Add([ordered]@{ Path = $item.FullName.Replace('\', '/'); Sha256 = Get-Sha256 $item.FullName })
    }
    foreach ($path in @($p.RedisConfig, $p.CollectorConfig, $p.SecretFile)) { $artifacts.Add([ordered]@{ Path = $path; Sha256 = Get-Sha256 $path }) }
    $services = [ordered]@{}
    foreach ($role in @('Redis', 'Collector')) {
      $sid = $redisSid; if ($role -eq 'Collector') { $sid = $collectorSid }
      $d = Get-RoleDefinition $role
      $services[$role] = [ordered]@{ Name = $d.Name; Wrapper = $d.Wrapper; Sid = $sid; Registered = $false; RegistrationState = 'NotInstalled'; EventSource = $events[$role] }
    }
    $marker = [ordered]@{
      Product = 'REISUN-Phase2A'; Schema = 1; CodeRoot = $p.CodeRoot; DataRoot = $p.DataRoot
      Nonce = New-RandomHex; CreatedUtc = [datetime]::UtcNow.ToString('o')
      Services = $services; EnvironmentCleared = $envNames; Artifacts = $artifacts.ToArray()
    }
    Write-ControlledText -Path $p.MarkerFile -Text ($marker | ConvertTo-Json -Depth 12) -Mode Admin -RedisSid $redisSid -CollectorSid $collectorSid
    $null = Read-PrototypeMarker -Full
    $p
  } catch {
    # 不删除部分目录、不接管异物；父级可审计受保护现场后决定如何处理。
    throw ('Phase2A 初始化失败；保留部分现场，不自动重试或删除。原因：{0}；位置：{1}' -f $_.Exception.Message, $_.InvocationInfo.PositionMessage)
  } finally {
    $archive.Dispose()
    $redisPassword = $null; $authToken = $null; $secretText = $null; $redisText = $null; $collectorText = $null
  }
}

function Get-CommandTail {
  param([string]$CommandLine, [string]$Executable)
  if ([string]::IsNullOrWhiteSpace($CommandLine)) { return $null }
  $pathPattern = [regex]::Escape([IO.Path]::GetFullPath($Executable).Replace('\', '/')).Replace('/', '[/\\]')
  $match = [regex]::Match($CommandLine, '^\s*(?:"' + $pathPattern + '"|' + $pathPattern + ')(?:\s+(.*))?\s*$', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
  if (-not $match.Success) { return $null }
  $match.Groups[1].Value.Trim()
}

function Get-PrototypeServiceIdentity {
  [CmdletBinding()]
  param([Parameter(Mandatory)][ValidateSet('Redis', 'Collector')][string]$Role)
  $d = Get-RoleDefinition $Role
  $result = [ordered]@{ ServiceName = $d.Name; ScmState = 'NotInstalled'; ServiceProcessId = 0; ChildProcess = $null; Owned = $false }
  try {
    $service = Get-ServiceRecord $d.Name
    if ($null -eq $service) { return [pscustomobject]$result }
    $result.ScmState = [string]$service.State
    $result.ServiceProcessId = [int]$service.ProcessId
    $marker = Read-PrototypeMarker
    if (-not ($marker.Services.$Role.Registered -is [bool]) -or -not $marker.Services.$Role.Registered) { return [pscustomobject]$result }
    foreach ($path in @($d.Wrapper, $d.Xml, $d.Executable)) { Assert-Artifact -Marker $marker -Path $path }
    $config = (Get-PrototypePaths).RedisConfig
    if ($Role -eq 'Collector') { $config = (Get-PrototypePaths).CollectorConfig; Assert-Artifact -Marker $marker -Path (Get-PrototypePaths).Jar }
    Assert-Artifact -Marker $marker -Path $config
    if ((Get-Sha256 $d.Wrapper) -cne $script:WinSwHash) { return [pscustomobject]$result }
    $tail = Get-CommandTail -CommandLine $service.PathName -Executable $d.Wrapper
    if ($null -eq $tail -or $tail -ne '' -or $service.StartName -ine ('NT SERVICE\' + $d.Name) -or $service.StartMode -ne 'Manual') { return [pscustomobject]$result }
    if ([int]$service.ProcessId -eq 0) {
      $result.Owned = $service.State -eq 'Stopped'
      return [pscustomobject]$result
    }
    $wrapper = Get-CimInstance Win32_Process -Filter ('ProcessId={0}' -f [int]$service.ProcessId) -OperationTimeoutSec 2 -ErrorAction Stop
    if ($null -eq $wrapper -or -not (Test-SamePath $wrapper.ExecutablePath $d.Wrapper) -or $null -eq $wrapper.CreationDate) { return [pscustomobject]$result }
    $wrapperTail = Get-CommandTail -CommandLine $wrapper.CommandLine -Executable $d.Wrapper
    if ($null -eq $wrapperTail -or $wrapperTail -ne '') { return [pscustomobject]$result }
    $children = @(Get-CimInstance Win32_Process -Filter ('ParentProcessId={0}' -f [int]$wrapper.ProcessId) -OperationTimeoutSec 2 -ErrorAction Stop)
    $valid = @($children | Where-Object {
      (Test-SamePath $_.ExecutablePath $d.Executable) -and $null -ne $_.CreationDate -and $_.CreationDate -ge $wrapper.CreationDate -and (Get-CommandTail -CommandLine $_.CommandLine -Executable $d.Executable) -ceq $d.Arguments
    })
    if ($valid.Count -gt 1 -or $children.Count -ne $valid.Count) { return [pscustomobject]$result }
    if ($valid.Count -eq 1) {
      $child = $valid[0]
      $result.ChildProcess = [pscustomobject]@{
        ProcessId = [int]$child.ProcessId; CreationDate = $child.CreationDate
        ExecutablePath = $child.ExecutablePath; CommandLine = $child.CommandLine
        ParentProcessId = [int]$child.ParentProcessId
      }
    }
    # 再读 SCM，防止鉴别过程中 wrapper 已退出且 PID 被复用。
    $again = Get-ServiceRecord $d.Name
    if ($null -ne $again -and [int]$again.ProcessId -eq [int]$wrapper.ProcessId -and $again.PathName -ceq $service.PathName) { $result.Owned = $true }
  } catch {
    # 只返回固定角色的安全状态；任何缺失、权限或身份异常都不能提升为 Owned。
    $result.Owned = $false
    $result.ChildProcess = $null
    if ($result.ScmState -eq 'NotInstalled') { $result.ScmState = 'Unknown' }
  }
  [pscustomobject]$result
}

function Assert-ServiceConfiguration {
  param([string]$Role, [datetime]$Deadline = [datetime]::MaxValue)
  $d = Get-RoleDefinition $Role
  $identity = Get-PrototypeServiceIdentity -Role $Role
  if (-not $identity.Owned) { throw ('服务未能通过路径/构件/虚拟账号归属验证：{0}。' -f $d.Name) }
  $key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey(('SYSTEM\CurrentControlSet\Services\' + $d.Name), $false)
  if ($null -eq $key) { throw '无法读取已注册服务配置。' }
  try {
    if ([int]$key.GetValue('ServiceSidType', 0) -ne 1 -or [int]$key.GetValue('Start', 0) -ne 3) { throw '服务 SID 类型/Manual 配置不匹配。' }
    $dependencies = @($key.GetValue('DependOnService', @()))
    if (($Role -eq 'Collector' -and ($dependencies.Count -ne 1 -or $dependencies[0] -ine 'REISUN-Redis-Dev')) -or ($Role -eq 'Redis' -and $dependencies.Count -ne 0)) { throw '服务真实依赖不匹配。' }
  } finally { $key.Dispose() }
  # 不解析本地化 sc.exe 文本；FailureActions REG_BINARY 是 SCM 的结构化持久值。
  $failureKey = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey(('SYSTEM\CurrentControlSet\Services\' + $d.Name), $false)
  if ($null -eq $failureKey) { throw '无法读取服务恢复配置。' }
  try {
    $failureBytes = [byte[]]$failureKey.GetValue('FailureActions', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
  } finally { $failureKey.Dispose() }
  if ($null -eq $failureBytes -or $failureBytes.Length -lt 20) { throw '服务没有完整的结构化恢复配置。' }
  $resetPeriod = [BitConverter]::ToInt32($failureBytes, 0)
  $actionCount = [BitConverter]::ToInt32($failureBytes, 12)
  # WinSW/SCM 的 REG_BINARY 在 cActions 后带有 4 字节 action-array metadata（值为 20）；
  # 真实 SC_ACTION 数组从偏移 20 开始，而不是直接紧随偏移 16。
  $actionsOffset = 20
  if ($resetPeriod -ne 3600 -or $actionCount -ne 4 -or $failureBytes.Length -lt ($actionsOffset + ($actionCount * 8)) -or [BitConverter]::ToInt32($failureBytes, 16) -ne 20) { throw 'SCM 恢复动作不是 5/10/15 秒后 none。' }
  $actions = @()
  for ($i = 0; $i -lt $actionCount; $i++) {
    $offset = $actionsOffset + ($i * 8)
    $type = [BitConverter]::ToInt32($failureBytes, $offset)
    $delay = [BitConverter]::ToInt32($failureBytes, $offset + 4)
    $actions += ('{0}:{1}' -f $type, $delay)
  }
  if (($actions -join ',') -cne '1:5000,1:10000,1:15000,0:0') { throw ('SCM 恢复动作不是 5/10/15 秒后 none：reset={0}, count={1}, actions={2}。' -f $resetPeriod, $actionCount, ($actions -join ',')) }
  $sddlText = Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('sdshow "{0}"' -f $d.Name) -TimeoutMilliseconds (Get-RemainingMilliseconds -Deadline $Deadline -Maximum 5000) -CaptureOutput
  $sddl = @($sddlText -split '\r?\n' | Where-Object { $_.Trim().StartsWith('D:') })
  if ($sddl.Count -ne 1) { throw '服务对象 DACL 无法唯一解析。' }
  $security = New-Object Security.AccessControl.RawSecurityDescriptor($sddl[0].Trim())
  $seen = @()
  foreach ($ace in $security.DiscretionaryAcl) {
    if ($ace.AceType -ne [Security.AccessControl.AceType]::AccessAllowed -or $ace.SecurityIdentifier.Value -notin @('S-1-5-18', 'S-1-5-32-544')) { throw '服务对象存在非管理员控制授权。' }
    if (($ace.AccessMask -band 0xF01FF) -ne 0xF01FF -and ($ace.AccessMask -band 0x10000000) -eq 0) { throw '服务管理员控制权限不完整。' }
    $seen += $ace.SecurityIdentifier.Value
  }
  if ($seen.Count -ne 2 -or $seen -notcontains 'S-1-5-18' -or $seen -notcontains 'S-1-5-32-544') { throw '服务对象 DACL 不匹配。' }
}

function Install-Prototype {
  [CmdletBinding()]
  param()
  Assert-Administrator
  $marker = Read-PrototypeMarker -Full
  Assert-EnvironmentCoverage $marker
  foreach ($role in @('Redis', 'Collector')) {
    if ($null -ne (Get-ServiceRecord (Get-RoleDefinition $role).Name)) { throw '已有同名服务；Install 不接管，也不覆盖注册。' }
  }
  $attempted = New-Object 'Collections.Generic.List[string]'
  try {
    foreach ($role in @('Redis', 'Collector')) {
      $d = Get-RoleDefinition $role
      $record = $marker.Services.$role
      if ([Diagnostics.EventLog]::SourceExists($d.Name) -and -not $record.EventSource.ExistedBefore -and -not $record.EventSource.CreatedByPrototype) { throw 'EventLog source 在初始化后被外部创建，拒绝认领。' }
      $record.RegistrationState = 'Installing'
      Save-PrototypeMarker $marker
      $attempted.Add($role)
      Invoke-NativeProcess -FilePath $d.Wrapper -Arguments 'install' -TimeoutMilliseconds 30000
      # 原生 install 可能部分成功，先记录可核对的路径/账号，再配置 SID；不使用 LocalSystem 兜底。
      $service = Get-ServiceRecord $d.Name
      if ($null -eq $service -or (Get-CommandTail $service.PathName $d.Wrapper) -cne '' -or $service.StartName -ine ('NT SERVICE\' + $d.Name)) { throw 'SCM 注册路径或虚拟账号不符合预期。' }
      $record.Registered = $true
      if (-not $record.EventSource.ExistedBefore -and [Diagnostics.EventLog]::SourceExists($d.Name)) { $record.EventSource.CreatedByPrototype = $true }
      Save-PrototypeMarker $marker
      Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('sidtype "{0}" unrestricted' -f $d.Name)
      Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('sdset "{0}" "{1}"' -f $d.Name, $script:ServiceSddl)
      Assert-ServiceConfiguration $role
      $record.RegistrationState = 'Installed'
      Save-PrototypeMarker $marker
    }
    [pscustomobject]@{ Installed = $true; Services = @('REISUN-Redis-Dev', 'REISUN-Collector-Dev'); StartMode = 'Manual'; SecretsRegenerated = $false }
  } catch {
    $reason = $_.Exception.Message
    $cleanup = New-Object 'Collections.Generic.List[string]'
    for ($i = $attempted.Count - 1; $i -ge 0; $i--) {
      $role = $attempted[$i]; $d = Get-RoleDefinition $role
      try {
        $record = $marker.Services.$role
        $service = Get-ServiceRecord $d.Name
        # 只清理由本调用在“服务原先不存在”后发起的注册；还必须同时匹配受保护路径和虚拟账号。
        if ($null -ne $service -and $service.State -eq 'Stopped' -and (Get-CommandTail $service.PathName $d.Wrapper) -ceq '' -and $service.StartName -ieq ('NT SERVICE\' + $d.Name)) {
          Assert-Artifact -Marker $marker -Path $d.Wrapper
          Invoke-NativeProcess -FilePath $d.Wrapper -Arguments 'uninstall' -TimeoutMilliseconds 30000
          if ($null -ne (Get-ServiceRecord $d.Name)) { throw '部分注册仍存在。' }
          $record.Registered = $false; $record.RegistrationState = 'NotInstalled'
          if (-not $record.EventSource.ExistedBefore -and [Diagnostics.EventLog]::SourceExists($d.Name)) { $record.EventSource.CreatedByPrototype = $true }
          Save-PrototypeMarker $marker
          $cleanup.Add($d.Name + ':removed')
        } elseif ($null -ne $service) { $cleanup.Add($d.Name + ':retained-unverified') }
      } catch { $cleanup.Add($d.Name + ':cleanup-failed') }
    }
    throw ('Install 失败：{0} 部分注册处理：{1}。' -f $reason, ($cleanup -join ', '))
  }
}

function Get-RoleListeners {
  param([int]$Port)
  @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq $Port })
}

function Assert-OwnedListener {
  param([string]$Role, $Identity, [switch]$AllowStopPending)
  $d = Get-RoleDefinition $Role
  if (-not $Identity.Owned -or ($Identity.ScmState -ne 'Running' -and -not ($AllowStopPending -and $Role -eq 'Redis' -and $Identity.ScmState -eq 'Stop Pending')) -or $null -eq $Identity.ChildProcess) { throw '服务不是已证实运行的本轮子进程。' }
  $listeners = @(Get-RoleListeners $d.Port)
  if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1' -or [int]$listeners[0].OwningProcess -ne [int]$Identity.ChildProcess.ProcessId) { throw '端口监听者不是本轮固定 loopback 子进程，拒绝连接。' }
  $current = Get-CimInstance Win32_Process -Filter ('ProcessId={0}' -f [int]$Identity.ChildProcess.ProcessId) -OperationTimeoutSec 2 -ErrorAction Stop
  if ($null -eq $current -or $current.CreationDate -ne $Identity.ChildProcess.CreationDate -or [int]$current.ParentProcessId -ne [int]$Identity.ServiceProcessId -or -not (Test-SamePath $current.ExecutablePath $d.Executable)) { throw '监听进程身份在请求前发生变化。' }
}

function Assert-PortVacant {
  param([int]$Port)
  $socket = New-Object Net.Sockets.Socket([Net.Sockets.AddressFamily]::InterNetwork, [Net.Sockets.SocketType]::Stream, [Net.Sockets.ProtocolType]::Tcp)
  try {
    $socket.ExclusiveAddressUse = $true
    $socket.Bind((New-Object Net.IPEndPoint([Net.IPAddress]::Loopback, $Port)))
    $socket.Listen(1)
  } catch { throw ('独占绑定检查失败：127.0.0.1:{0}；不会接管或终止监听者。' -f $Port) }
  finally { $socket.Dispose() }
}

function Assert-OwnedRedisConnection {
  param($Identity, [Net.Sockets.TcpClient]$Client)
  $clientPort = ([Net.IPEndPoint]$Client.Client.LocalEndPoint).Port
  # 不只确认 listen socket；认证前还核对这条已建立连接的服务端 PID。
  $connections = @(Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 16379 -RemoteAddress '127.0.0.1' -RemotePort $clientPort -State Established -ErrorAction Stop)
  if ($connections.Count -ne 1 -or [int]$connections[0].OwningProcess -ne [int]$Identity.ChildProcess.ProcessId) { throw 'Redis 已建立连接不是本轮子进程，拒绝发送认证。' }
}

function Assert-RedisCommand {
  param([string[]]$Command)
  if ($null -eq $Command -or $Command.Count -lt 1 -or $Command.Count -gt 4) { throw 'Redis 命令参数数量不在白名单中。' }
  foreach ($part in $Command) { if ($null -eq $part -or $script:Utf8.GetByteCount($part) -gt 1048576) { throw 'Redis 命令参数为空或过长。' } }
  $verb = $Command[0].ToUpperInvariant()
  $allowed = $false
  switch ($verb) {
    PING { $allowed = $Command.Count -eq 1 }
    INFO { $allowed = $Command.Count -eq 1 -or ($Command.Count -eq 2 -and $Command[1] -match '^[a-zA-Z]+$') }
    ECHO { $allowed = $Command.Count -eq 2 }
    ROLE { $allowed = $Command.Count -eq 1 }
    CONFIG { $allowed = $Command.Count -eq 3 -and $Command[1] -ieq 'GET' -and $Command[2].ToLowerInvariant() -in @('bind', 'port', 'dir', 'appendonly', 'appendfsync', 'maxmemory', 'maxmemory-policy') }
    SET { $allowed = $Command.Count -eq 3 -and $Command[1].StartsWith('phase2a:', [StringComparison]::Ordinal) }
    GET { $allowed = $Command.Count -eq 2 -and $Command[1].StartsWith('phase2a:', [StringComparison]::Ordinal) }
    BGSAVE { $allowed = $Command.Count -eq 1 }
    BGREWRITEAOF { $allowed = $Command.Count -eq 1 }
    SHUTDOWN { $allowed = $Command.Count -eq 2 -and $Command[1] -ieq 'SAVE' }
  }
  if (-not $allowed) { throw 'Redis 命令不在 Phase2A 最小运维白名单中。' }
}

function Write-RespCommand {
  param([IO.Stream]$Stream, [string[]]$Command, [datetime]$Deadline)
  $memory = New-Object IO.MemoryStream
  try {
    $prefix = [Text.Encoding]::ASCII.GetBytes(('*' + $Command.Count + "`r`n"))
    $memory.Write($prefix, 0, $prefix.Length)
    foreach ($part in $Command) {
      $bytes = $script:Utf8.GetBytes($part)
      $header = [Text.Encoding]::ASCII.GetBytes(('$' + $bytes.Length + "`r`n"))
      $memory.Write($header, 0, $header.Length); $memory.Write($bytes, 0, $bytes.Length)
      $memory.WriteByte(13); $memory.WriteByte(10)
    }
    $Stream.WriteTimeout = Get-RemainingMilliseconds $Deadline
    $buffer = $memory.ToArray()
    $Stream.Write($buffer, 0, $buffer.Length)
    [Array]::Clear($buffer, 0, $buffer.Length)
  } finally { $memory.Dispose() }
}

function Read-RespByte {
  param([IO.Stream]$Stream, [datetime]$Deadline)
  $Stream.ReadTimeout = Get-RemainingMilliseconds $Deadline
  $value = $Stream.ReadByte()
  if ($value -lt 0) { throw 'Redis 响应提前结束。' }
  $value
}

function Read-RespLine {
  param([IO.Stream]$Stream, [datetime]$Deadline)
  $bytes = New-Object 'Collections.Generic.List[byte]'
  while ($bytes.Count -lt 8192) {
    $value = Read-RespByte $Stream $Deadline
    if ($value -eq 13) {
      if ((Read-RespByte $Stream $Deadline) -ne 10) { throw 'Redis RESP 行结束符错误。' }
      return $script:Utf8.GetString($bytes.ToArray())
    }
    $bytes.Add([byte]$value)
  }
  throw 'Redis RESP 行超过上限。'
}

function Read-RespValue {
  param([IO.Stream]$Stream, [datetime]$Deadline, [int]$Depth = 0)
  if ($Depth -gt 4) { throw 'Redis RESP 嵌套超过上限。' }
  $type = [char](Read-RespByte $Stream $Deadline)
  $line = Read-RespLine $Stream $Deadline
  switch ($type) {
    '+' { return $line }
    '-' {
      # 错误只保留协议错误类别；不回显服务器可能包含的命令值、key 或 AUTH 内容。
      $code = 'ERR'
      if ($line -match '^(NOAUTH|WRONGPASS|LOADING|BUSY|MISCONF|NOPERM|OOM)\b') { $code = $Matches[1] }
      throw ('Redis 返回真实错误：{0}。' -f $code)
    }
    ':' { return [long]::Parse($line, [Globalization.CultureInfo]::InvariantCulture) }
    '$' {
      $length = [int]::Parse($line, [Globalization.CultureInfo]::InvariantCulture)
      if ($length -eq -1) { return $null }
      if ($length -lt 0 -or $length -gt 1048576) { throw 'Redis bulk 长度超限。' }
      $bytes = New-Object byte[] $length
      $offset = 0
      while ($offset -lt $length) {
        $Stream.ReadTimeout = Get-RemainingMilliseconds $Deadline
        $count = $Stream.Read($bytes, $offset, $length - $offset)
        if ($count -eq 0) { throw 'Redis bulk 响应提前结束。' }
        $offset += $count
      }
      if ((Read-RespByte $Stream $Deadline) -ne 13 -or (Read-RespByte $Stream $Deadline) -ne 10) { throw 'Redis bulk 结束符错误。' }
      return $script:Utf8.GetString($bytes)
    }
    '*' {
      $length = [int]::Parse($line, [Globalization.CultureInfo]::InvariantCulture)
      if ($length -eq -1) { return $null }
      if ($length -lt 0 -or $length -gt 512) { throw 'Redis array 长度超限。' }
      $values = New-Object object[] $length
      for ($i = 0; $i -lt $length; $i++) { $values[$i] = Read-RespValue $Stream $Deadline ($Depth + 1) }
      return ,$values
    }
    default { throw '不支持的 Redis RESP 类型。' }
  }
}

function Invoke-RedisCore {
  param([string[]]$Command, [switch]$NoAuthentication, [datetime]$Deadline = [datetime]::MaxValue)
  Assert-RedisCommand $Command
  $identity = Get-PrototypeServiceIdentity Redis
  $shutdownSave = $Command.Count -eq 2 -and $Command[0] -ieq 'SHUTDOWN' -and $Command[1] -ieq 'SAVE' -and -not $NoAuthentication
  Assert-OwnedListener -Role Redis -Identity $identity -AllowStopPending:$shutdownSave
  $client = New-Object Net.Sockets.TcpClient([Net.Sockets.AddressFamily]::InterNetwork)
  $stream = $null
  $secret = $null
  try {
    $timeout = Get-RemainingMilliseconds $Deadline
    $connect = $client.BeginConnect([Net.IPAddress]::Loopback, 16379, $null, $null)
    try {
      if (-not $connect.AsyncWaitHandle.WaitOne($timeout)) { throw 'Redis 连接超过 2 秒或剩余 deadline。' }
      $client.EndConnect($connect)
    } finally { $connect.AsyncWaitHandle.Close() }
    # 连接建立后再核对监听者，认证材料不发送给未知实例。
    Assert-OwnedListener -Role Redis -Identity $identity -AllowStopPending:$shutdownSave
    Assert-OwnedRedisConnection -Identity $identity -Client $client
    $stream = $client.GetStream()
    $ioDeadline = [datetime]::UtcNow.AddMilliseconds((Get-RemainingMilliseconds $Deadline))
    if (-not $NoAuthentication) {
      $marker = Read-PrototypeMarker
      $secret = Read-PrototypeSecrets $marker
      Write-RespCommand -Stream $stream -Command @('AUTH', $secret.RedisPassword) -Deadline $ioDeadline
      if ((Read-RespValue $stream $ioDeadline) -cne 'OK') { throw 'Redis AUTH 未被确认。' }
      $secret = $null
    }
    Write-RespCommand -Stream $stream -Command $Command -Deadline $ioDeadline
    $value = Read-RespValue $stream $ioDeadline
    # SHUTDOWN SAVE 会主动撤销监听，退出后由 SCM、wrapper 和持久化证据校验。
    if (-not $shutdownSave) { Assert-OwnedListener -Role Redis -Identity $identity }
    return ,$value
  } finally {
    $secret = $null
    if ($null -ne $stream) { $stream.Dispose() }
    $client.Dispose()
  }
}

function Invoke-PrototypeRedis {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string[]]$Command, [switch]$NoAuthentication)
  # 包括 SET/BGSAVE 的所有写操作都只能由管理员令牌发出；诊断不提供秘密 getter。
  Assert-Administrator
  Invoke-RedisCore -Command $Command -NoAuthentication:$NoAuthentication
}

function ConvertFrom-RedisInfo {
  param([string]$Info)
  $map = @{}
  foreach ($line in ($Info -split "`r?`n")) {
    if ($line -match '^([^#:\s]+):(.*)$') { $map[$Matches[1]] = $Matches[2] }
  }
  $map
}

function Get-RedisHealth {
  param([datetime]$Deadline = [datetime]::MaxValue)
  $identity = Get-PrototypeServiceIdentity Redis
  $state = [ordered]@{
    ScmState = $identity.ScmState; Owned = [bool]$identity.Owned; Ready = $false
    ProcessId = $null; RunId = $null; Version = $null; PersistentState = $null
    MaxMemoryPolicy = $null; Diagnostic = 'NotReady'
  }
  if ($null -ne $identity.ChildProcess) { $state.ProcessId = $identity.ChildProcess.ProcessId }
  if (-not $identity.Owned -or $identity.ScmState -ne 'Running' -or $null -eq $identity.ChildProcess) { return [pscustomobject]$state }
  try {
    $info = ConvertFrom-RedisInfo ([string](Invoke-RedisCore -Command @('INFO') -Deadline $Deadline))
    $state.RunId = $info['run_id']; $state.Version = $info['redis_version']
    $state.PersistentState = [pscustomobject]@{
      Loading = $info['loading']; AofEnabled = $info['aof_enabled']
      AofLastWriteStatus = $info['aof_last_write_status']; AofLastRewriteStatus = $info['aof_last_bgrewrite_status']
      RdbLastSaveStatus = $info['rdb_last_bgsave_status']; AofRewriteInProgress = $info['aof_rewrite_in_progress']
      RdbSaveInProgress = $info['rdb_bgsave_in_progress']
    }
    $config = @{}
    foreach ($key in @('bind', 'port', 'dir', 'appendonly', 'appendfsync', 'maxmemory', 'maxmemory-policy')) {
      $pair = Invoke-RedisCore -Command @('CONFIG', 'GET', $key) -Deadline $Deadline
      if ($null -eq $pair -or $pair.Count -ne 2 -or $pair[0] -ine $key) { throw 'CONFIG GET 未返回预期键值。' }
      $config[$key] = [string]$pair[1]
    }
    $state.MaxMemoryPolicy = $config['maxmemory-policy']
    $expectedDir = (Get-PrototypePaths).DataRoot + '/redis/data'
    $actualDir = $config['dir']
    if ($actualDir -match '^/cygdrive/([a-z])/(.*)$') { $actualDir = $Matches[1] + ':/' + $Matches[2] }
    $persistent = $info['loading'] -eq '0' -and $info['aof_enabled'] -eq '1' -and $info['aof_last_write_status'] -eq 'ok' -and $info['aof_last_bgrewrite_status'] -eq 'ok' -and $info['rdb_last_bgsave_status'] -eq 'ok'
    $settings = $config['bind'] -eq '127.0.0.1' -and $config['port'] -eq '16379' -and (Test-SamePath $actualDir $expectedDir) -and $config['appendonly'] -eq 'yes' -and $config['appendfsync'] -eq 'everysec' -and $config['maxmemory'] -eq [string](256L * 1024 * 1024) -and $config['maxmemory-policy'] -eq 'noeviction'
    $state.Ready = [bool]($persistent -and $settings -and $state.Version -eq '7.2.16' -and $state.RunId -match '^[a-f0-9]{40}$' -and $info['role'] -eq 'master')
    if ($state.Ready) { $state.Diagnostic = 'AuthenticatedInfoConfigPersistenceReady' }
    else { $state.Diagnostic = 'InfoConfigOrPersistenceNotReady' }
  } catch { $state.Diagnostic = 'OwnedRedisProbeFailed' }
  [pscustomobject]$state
}

function Get-CollectorHealth {
  param([bool]$RedisReady, [datetime]$Deadline = [datetime]::MaxValue)
  $identity = Get-PrototypeServiceIdentity Collector
  $state = [ordered]@{
    ScmState = $identity.ScmState; Owned = [bool]$identity.Owned; Ready = $false
    JavaProcessId = $null; ReadinessStatus = $null; HttpStatus = $null
    RedisReady = $RedisReady; Diagnostic = 'NotReady'
  }
  if ($null -ne $identity.ChildProcess) { $state.JavaProcessId = $identity.ChildProcess.ProcessId }
  if (-not $identity.Owned -or $identity.ScmState -ne 'Running' -or $null -eq $identity.ChildProcess) { return [pscustomobject]$state }
  $response = $null; $stream = $null
  try {
    Assert-OwnedListener -Role Collector -Identity $identity
    # 固定 profile 明确放行 readiness；此探针无需发送 token，避免无必要的秘密传递。
    $request = [Net.HttpWebRequest]::Create('http://127.0.0.1:19090/collector/actuator/health/readiness')
    $request.Method = 'GET'; $request.Proxy = $null; $request.AllowAutoRedirect = $false
    $request.Timeout = Get-RemainingMilliseconds $Deadline
    $request.ReadWriteTimeout = $request.Timeout
    try { $response = $request.GetResponse() }
    catch [Net.WebException] {
      if ($null -eq $_.Exception.Response) { throw 'Collector readiness 无 HTTP 响应。' }
      $response = $_.Exception.Response
    }
    $state.HttpStatus = [int]$response.StatusCode
    $stream = $response.GetResponseStream()
    $memory = New-Object IO.MemoryStream
    try {
      $buffer = New-Object byte[] 4096
      $readDeadline = [datetime]::UtcNow.AddMilliseconds((Get-RemainingMilliseconds $Deadline))
      while ($true) {
        if ($stream.CanTimeout) { $stream.ReadTimeout = Get-RemainingMilliseconds $readDeadline }
        $count = $stream.Read($buffer, 0, $buffer.Length)
        if ($count -eq 0) { break }
        if ($memory.Length + $count -gt 65536) { throw 'Collector readiness 响应超过 64KB。' }
        $memory.Write($buffer, 0, $count)
      }
      $json = $script:Utf8.GetString($memory.ToArray()) | ConvertFrom-Json -ErrorAction Stop
      $state.ReadinessStatus = [string]$json.status
    } finally { $memory.Dispose() }
    Assert-OwnedListener -Role Collector -Identity $identity
    $state.Ready = [bool]($RedisReady -and $state.HttpStatus -eq 200 -and $state.ReadinessStatus -ceq 'UP')
    $state.Diagnostic = 'ReadinessJsonObserved'
  } catch { $state.Diagnostic = 'OwnedCollectorProbeFailed' }
  finally {
    if ($null -ne $stream) { $stream.Dispose() }
    if ($null -ne $response) { $response.Dispose() }
  }
  [pscustomobject]$state
}

function Get-PrototypeStatus {
  [CmdletBinding()]
  param()
  $redis = Get-RedisHealth
  $collector = Get-CollectorHealth -RedisReady $redis.Ready
  [pscustomobject]@{ Redis = $redis; Collector = $collector }
}

function Wait-RoleReady {
  param([string]$Role, [datetime]$Deadline)
  while ([datetime]::UtcNow -lt $Deadline) {
    $redis = Get-RedisHealth -Deadline $Deadline
    if ($Role -eq 'Redis' -and $redis.Ready) { return }
    if ($Role -eq 'Collector') {
      $collector = Get-CollectorHealth -RedisReady $redis.Ready -Deadline $Deadline
      if ($collector.Ready) { return }
    }
    $null = Get-RemainingMilliseconds $Deadline
    Start-Sleep -Milliseconds (Get-RemainingMilliseconds -Deadline $Deadline -Maximum 200)
  }
  throw ('服务在 startup deadline 前未真正就绪：{0}。' -f $Role)
}

function Repair-RedisDataAcl {
  param($Marker)
  $p = Get-PrototypePaths
  $sidText = [string]$Marker.Services.Redis.Sid
  if ($sidText -notmatch '^S-1-5-80-(?:\d+-){4}\d+$') { throw 'Redis 服务 SID 不符合固定虚拟服务账号格式。' }
  $aofRoot = "$($p.DataRoot)/redis/data/appendonlydir"
  Assert-NoReparsePoint $aofRoot
  foreach ($item in @(Get-ChildItem -LiteralPath $aofRoot -Force -File -ErrorAction Stop)) {
    Assert-NoReparsePoint $item.FullName
    # Cygwin may leave a POSIX-shaped DACL that denies administrators WRITE_DAC.
    # Take ownership only for this verified AOF file, then add one explicit ACE
    # for the owning Redis service SID; never recurse over the product tree.
    Invoke-NativeProcess -FilePath (Join-Path $env:SystemRoot 'System32/takeown.exe') -Arguments ('/F "{0}" /A' -f $item.FullName) -TimeoutMilliseconds 10000
    Invoke-NativeProcess -FilePath (Join-Path $env:SystemRoot 'System32/icacls.exe') -Arguments ('"{0}" /grant *{1}:F' -f $item.FullName, $sidText) -TimeoutMilliseconds 10000
    $aclText = Invoke-NativeProcess -FilePath (Join-Path $env:SystemRoot 'System32/icacls.exe') -Arguments ('"{0}"' -f $item.FullName) -TimeoutMilliseconds 10000 -CaptureOutput
    $serviceName = [string]$Marker.Services.Redis.Name
    $sidAce = $aclText -match ([regex]::Escape($sidText) + ':\(F\)')
    $serviceAce = $aclText -match ('NT SERVICE\\' + [regex]::Escape($serviceName) + ':\(F\)')
    if (-not ($sidAce -or $serviceAce)) { throw ('Redis AOF ACL readback does not contain the owning service SID: {0}。' -f $item.Name) }
  }
}

function Start-Prototype {
  [CmdletBinding()]
  param(
    [ValidateSet('Both', 'Redis', 'Collector')][string]$Role = 'Both',
    [ValidateRange(1, 360)][int]$StartupTimeoutSeconds = 120
  )
  Assert-Administrator
  $deadline = [datetime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
  $marker = Read-PrototypeMarker -Full -Deadline $deadline
  Assert-EnvironmentCoverage $marker
  $roles = @($Role); if ($Role -eq 'Both') { $roles = @('Redis', 'Collector') }
  $newlyStarted = New-Object 'Collections.Generic.List[string]'
  try {
    # 启动任一服务前先检查本次全部目标端口，避免先写 Redis 再发现 HTTP 冲突。
    foreach ($currentRole in $roles) {
      Assert-ServiceConfiguration -Role $currentRole -Deadline $deadline
      $identity = Get-PrototypeServiceIdentity $currentRole
      $d = Get-RoleDefinition $currentRole
      if ($identity.ScmState -eq 'Stopped') { Assert-PortVacant $d.Port }
      elseif ($identity.ScmState -eq 'Running') {
        $redisHealth = Get-RedisHealth -Deadline $deadline
        if ($currentRole -eq 'Redis') { $health = $redisHealth }
        else { $health = Get-CollectorHealth -RedisReady $redisHealth.Ready -Deadline $deadline }
        if (-not $health.Owned -or -not $health.Ready) { throw '已运行但不健康的实例不能被本次 Start 接管。' }
      } else { throw '服务处于过渡/未知状态，拒绝重复启动。' }
      $null = Get-RemainingMilliseconds $deadline
    }
    foreach ($currentRole in $roles) {
      if ($currentRole -eq 'Collector') {
        $redis = Get-RedisHealth -Deadline $deadline
        if (-not $redis.Owned -or -not $redis.Ready) { throw 'Collector 只能在本轮 Redis 已认证健康时启动。' }
      }
      $identity = Get-PrototypeServiceIdentity $currentRole
      if ($identity.ScmState -eq 'Stopped') {
        Assert-PortVacant (Get-RoleDefinition $currentRole).Port
        if ($currentRole -eq 'Redis') { Repair-RedisDataAcl -Marker $marker }
        $newlyStarted.Add($currentRole)
        # sc.exe 是隔离、有界的原生 SCM API 请求客户端，不调用可能同步阻塞的 Start-Service。
        Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('start "{0}"' -f (Get-RoleDefinition $currentRole).Name) -TimeoutMilliseconds (Get-RemainingMilliseconds -Deadline $deadline -Maximum 30000)
      }
      Wait-RoleReady -Role $currentRole -Deadline $deadline
    }
    $redis = Get-RedisHealth -Deadline $deadline
    $collector = Get-CollectorHealth -RedisReady $redis.Ready -Deadline $deadline
    $null = Get-RemainingMilliseconds $deadline
    if (($roles -contains 'Redis' -and -not $redis.Ready) -or ($roles -contains 'Collector' -and -not $collector.Ready)) { throw '返回启动结果前最终就绪复核失败。' }
    [pscustomobject]@{ Redis = $redis; Collector = $collector }
  } catch {
    $reason = $_.Exception.Message
    $rollback = New-Object 'Collections.Generic.List[string]'
    for ($i = $newlyStarted.Count - 1; $i -ge 0; $i--) {
      $currentRole = $newlyStarted[$i]
      try { $null = Stop-Prototype -Role $currentRole -TimeoutSeconds 150; $rollback.Add($currentRole + ':stopped') }
      catch { $rollback.Add($currentRole + ':retained-stop-failed-or-unverified') }
    }
    throw ('Start 失败：{0} 本调用新启动服务回退：{1}。' -f $reason, ($rollback -join ', '))
  }
}

function Get-OwnedDescendants {
  param([string]$Role, $Identity, [datetime]$Deadline = [datetime]::MaxValue)
  $found = New-Object 'Collections.Generic.List[object]'
  $d = Get-RoleDefinition $Role
  $allowedExecutables = @($d.Executable)
  if ($Role -in @('Redis', 'Collector')) { $allowedExecutables += (Join-Path $env:SystemRoot 'System32/conhost.exe') }
  $null = Get-RemainingMilliseconds $Deadline
  $wrapper = Get-CimInstance Win32_Process -Filter ('ProcessId={0}' -f [int]$Identity.ServiceProcessId) -OperationTimeoutSec 2 -ErrorAction Stop
  if ($null -eq $wrapper -or $null -eq $wrapper.CreationDate -or -not (Test-SamePath $wrapper.ExecutablePath $d.Wrapper)) { throw '停止前无法重新取得固定 wrapper 的进程身份。' }
  $found.Add([pscustomobject]@{ ProcessId = [int]$wrapper.ProcessId; CreationDate = $wrapper.CreationDate; ExecutablePath = $wrapper.ExecutablePath })
  if ($null -eq $Identity.ChildProcess) { return ,$found.ToArray() }
  $found.Add($Identity.ChildProcess)
  $queue = New-Object 'Collections.Generic.Queue[object]'
  $queue.Enqueue($Identity.ChildProcess)
  while ($queue.Count -gt 0) {
    $null = Get-RemainingMilliseconds $Deadline
    if ($found.Count -gt 128) { throw '本轮进程树超出安全上限，拒绝猜测归属。' }
    $parent = $queue.Dequeue()
    foreach ($child in @(Get-CimInstance Win32_Process -Filter ('ParentProcessId={0}' -f [int]$parent.ProcessId) -OperationTimeoutSec 2 -ErrorAction Stop)) {
      if ($child.CreationDate -lt $parent.CreationDate) { continue }
      if (@($allowedExecutables | Where-Object { Test-SamePath $child.ExecutablePath $_ }).Count -ne 1) { throw ('本轮子进程树出现未预期的可执行程序：{0}；角色：{1}；PID：{2}；父PID：{3}。' -f $child.ExecutablePath, $Role, $child.ProcessId, $child.ParentProcessId) }
      $record = [pscustomobject]@{ ProcessId = [int]$child.ProcessId; CreationDate = $child.CreationDate; ExecutablePath = $child.ExecutablePath }
      $found.Add($record); $queue.Enqueue($record)
    }
  }
  return ,$found.ToArray()
}

function Assert-NoWrapperKillFallback {
  param([string]$LogPath, [long]$Offset)
  Assert-NoReparsePoint $LogPath
  $stream = New-Object IO.FileStream($LogPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
  try {
    $length = $stream.Length - $Offset
    if ($length -lt 0 -or $length -gt 1048576) { throw '停止期间 wrapper 日志被截断或增量超限，无法证明没有强杀兜底。' }
    $null = $stream.Seek($Offset, [IO.SeekOrigin]::Begin)
    $buffer = New-Object byte[] ([int]$length)
    $position = 0
    while ($position -lt $buffer.Length) {
      $count = $stream.Read($buffer, $position, $buffer.Length - $position)
      if ($count -eq 0) { throw '停止日志增量读取不完整。' }
      $position += $count
    }
    $text = $script:Utf8.GetString($buffer)
    # 固定 v2.12.0 ProcessHelper 在 Kill 后写这条 DEBUG；文件日志固定 DEBUG+ImmediateFlush。
    if ($text -match '\bProcess \d+ terminated\.') { throw 'WinSW 本轮停止走了强杀兜底；SCM STOPPED 不能算正常停止成功。' }
    if ($text -notmatch '\bProcess \d+ (?:canceled with code|has already exited)') { throw '缺少本轮 wrapper 正常退出观察，不能仅凭 SCM STOPPED 宣告停止成功。' }
  } finally { $stream.Dispose() }
}

function Request-RedisGracefulShutdown {
  param([datetime]$Deadline)
  try {
    # 先通过已归属且已认证的 loopback 连接请求 Redis 自身退出；Cygwin
    # 子进程对 WinSW 的控制台停止信号不总能及时传递，不能因此退化为强杀。
    $null = Invoke-RedisCore -Command @('SHUTDOWN', 'SAVE') -Deadline $Deadline
  } catch {
    # 只接受 SHUTDOWN 主动关闭连接造成的 RESP EOF，不吞掉白名单、身份或 Redis 错误。
    if ($_.Exception.Message -cne 'Redis 响应提前结束。') { throw }
  }
}

function Stop-OwnedRole {
  param([string]$Role, [datetime]$Deadline)
  $d = Get-RoleDefinition $Role
  $identity = Get-PrototypeServiceIdentity $Role
  if (-not $identity.Owned) { throw ('无法证明服务归属，禁止停止：{0}。' -f $d.Name) }
  if ($identity.ScmState -eq 'Stopped') { Assert-PortVacant $d.Port; return }
  $descendants = Get-OwnedDescendants -Role $Role -Identity $identity -Deadline $Deadline
  $wrapperLog = Join-Path $d.Logs ($d.Name + '.wrapper.log')
  Assert-NoReparsePoint $wrapperLog
  if (-not [IO.File]::Exists($wrapperLog)) { throw '缺少固定 wrapper 日志，无法区分正常停止与强杀兜底。' }
  $logOffset = (Get-Item -LiteralPath $wrapperLog -ErrorAction Stop).Length
  # StopPending 不再发第二次 stop，避免 Redis 接到第二次 SIGINT。
  if ($identity.ScmState -ne 'Stop Pending') {
    Invoke-NativeProcess -FilePath (Get-ScPath) -Arguments ('stop "{0}"' -f $d.Name) -TimeoutMilliseconds (Get-RemainingMilliseconds -Deadline $Deadline -Maximum 30000)
    if ($Role -eq 'Redis') {
      # 先让 WinSW 接受 SCM STOP；仅对仍在监听的已归属 Redis 子进程
      # 发送认证 SHUTDOWN SAVE，避免子进程先退出时 WinSW SignalStopped 竞争。
      $afterSignal = Get-PrototypeServiceIdentity Redis
      if (-not $afterSignal.Owned) { throw 'SCM 停止信号后无法证明 Redis 归属。' }
      if ($afterSignal.ScmState -in @('Running', 'Stop Pending') -and $null -ne $afterSignal.ChildProcess) {
        $listeners = @(Get-RoleListeners 16379)
        if ($listeners.Count -gt 0) { Request-RedisGracefulShutdown -Deadline $Deadline }
      }
    }
  }
  while ([datetime]::UtcNow -lt $Deadline) {
    $record = Get-ServiceRecord $d.Name
    if ($null -eq $record -or (Get-CommandTail $record.PathName $d.Wrapper) -cne '' -or $record.StartName -ine ('NT SERVICE\' + $d.Name)) { throw '停止期间 SCM 服务身份改变。' }
    $alive = $false
    foreach ($child in $descendants) {
      $null = Get-RemainingMilliseconds $Deadline
      $process = Get-CimInstance Win32_Process -Filter ('ProcessId={0}' -f [int]$child.ProcessId) -OperationTimeoutSec 2 -ErrorAction Stop
      if ($null -ne $process -and $process.CreationDate -eq $child.CreationDate) { $alive = $true }
    }
    if ($record.State -eq 'Stopped' -and -not $alive) {
      Assert-PortVacant $d.Port
      Assert-NoWrapperKillFallback -LogPath $wrapperLog -Offset $logOffset
      return
    }
    Start-Sleep -Milliseconds (Get-RemainingMilliseconds -Deadline $Deadline -Maximum 200)
  }
  throw ('正常停止超时：{0}；没有执行盲杀或把超时当作成功。' -f $d.Name)
}

function Stop-Prototype {
  [CmdletBinding()]
  param(
    [ValidateSet('Both', 'Redis', 'Collector')][string]$Role = 'Both',
    [ValidateRange(1, 600)][int]$TimeoutSeconds = 150
  )
  Assert-Administrator
  $null = Read-PrototypeMarker
  $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
  if ($Role -eq 'Redis') {
    $collector = Get-ServiceRecord 'REISUN-Collector-Dev'
    if ($null -ne $collector -and $collector.State -ne 'Stopped') { throw 'Collector 尚未完全停止，拒绝单独停止 Redis。' }
  }
  $roles = @($Role); if ($Role -eq 'Both') { $roles = @('Collector', 'Redis') }
  foreach ($currentRole in $roles) {
    if ($currentRole -eq 'Redis') {
      $collector = Get-ServiceRecord 'REISUN-Collector-Dev'
      if ($null -ne $collector -and $collector.State -ne 'Stopped') { throw 'Collector 尚未完全退出，不能停止 Redis。' }
    }
    Stop-OwnedRole -Role $currentRole -Deadline $deadline
  }
  [pscustomobject]@{ Stopped = $true; Roles = $roles; Forced = $false; Evidence = 'ScmStoppedTrackedChildrenExitedAndPortsVacant' }
}

function Remove-OwnedEventSource {
  param($Marker, [string]$Role)
  $record = $Marker.Services.$Role
  $name = (Get-RoleDefinition $Role).Name
  if ($record.EventSource.ExistedBefore -or -not $record.EventSource.CreatedByPrototype) { return }
  if ([Diagnostics.EventLog]::SourceExists($name)) {
    if ([Diagnostics.EventLog]::LogNameFromSourceName($name, '.') -ne 'Application') { throw '本轮 EventLog source 已被重定向，拒绝删除。' }
    [Diagnostics.EventLog]::DeleteEventSource($name)
    if ([Diagnostics.EventLog]::SourceExists($name)) { throw '本轮 EventLog source 删除后仍存在。' }
  }
  $record.EventSource.CreatedByPrototype = $false
}

function Uninstall-Prototype {
  [CmdletBinding()]
  param()
  Assert-Administrator
  $marker = Read-PrototypeMarker -Full
  $present = @{}
  # 在停止前验证所有现存服务，防止只凭固定名字处理外来注册。
  foreach ($role in @('Redis', 'Collector')) {
    $service = Get-ServiceRecord (Get-RoleDefinition $role).Name
    $present[$role] = $null -ne $service
    if ($present[$role] -and -not (Get-PrototypeServiceIdentity $role).Owned) { throw '现存服务不是本轮已证明归属的注册；卸载拒绝执行。' }
  }
  foreach ($role in @('Collector', 'Redis')) {
    if ($present[$role]) { $null = Stop-Prototype -Role $role -TimeoutSeconds 150 }
  }
  $removed = New-Object 'Collections.Generic.List[string]'
  foreach ($role in @('Collector', 'Redis')) {
    $d = Get-RoleDefinition $role
    if ($present[$role]) {
      if (-not (Get-PrototypeServiceIdentity $role).Owned) { throw '卸载前最后归属校验失败。' }
      Invoke-NativeProcess -FilePath $d.Wrapper -Arguments 'uninstall' -TimeoutMilliseconds 30000
      $deadline = [datetime]::UtcNow.AddSeconds(10)
      while ($null -ne (Get-ServiceRecord $d.Name)) {
        Start-Sleep -Milliseconds (Get-RemainingMilliseconds -Deadline $deadline -Maximum 200)
      }
      $removed.Add($d.Name)
    }
    $marker.Services.$role.Registered = $false
    $marker.Services.$role.RegistrationState = 'NotInstalled'
    Save-PrototypeMarker $marker
    Remove-OwnedEventSource -Marker $marker -Role $role
    Save-PrototypeMarker $marker
  }
  [pscustomobject]@{
    Uninstalled = $true; RemovedServices = $removed.ToArray()
    CodePreserved = $true; ConfigPreserved = $true; DataPreserved = $true; SecretsRegenerated = $false
  }
}

Export-ModuleMember -Function Get-PrototypePaths, Initialize-Prototype, Install-Prototype, Get-PrototypeServiceIdentity, Invoke-PrototypeRedis, Get-PrototypeStatus, Start-Prototype, Stop-Prototype, Uninstall-Prototype
