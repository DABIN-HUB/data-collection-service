[CmdletBinding()]
param(
    [string]$ServerHost = "127.0.0.1",
    [int]$Port = 502,
    [int]$UnitId = 1,
    [int]$StartRegister = 4001,
    [int]$PointCount = 10,
    [int]$SampleIntervalMs = 1500,
    [string]$DataType = "UINT16",
    [string]$ByteOrder = "BIG_ENDIAN"
)

$ErrorActionPreference = "Stop"

if ($PointCount -le 0) {
    throw "PointCount must be greater than zero."
}
if ($StartRegister -le 0 -or ($StartRegister + $PointCount - 1) -gt 9999) {
    throw "The holding register range must be between 1 and 9999."
}

$client = [System.Net.Sockets.TcpClient]::new()
try {
    try {
        $connectTask = $client.ConnectAsync($ServerHost, $Port)
        if (-not $connectTask.Wait(3000) -or -not $client.Connected) {
            throw "connection timeout"
        }
    } catch {
        throw "Cannot connect to Modbus TCP server ${ServerHost}:${Port}."
    }
} finally {
    $client.Dispose()
}

$mavenArguments = @(
    "-q",
    "-Dtest=ModbusTcpCollectorRealServerIT",
    "-Dmodbus.real.enabled=true",
    "-Dmodbus.real.host=$ServerHost",
    "-Dmodbus.real.port=$Port",
    "-Dmodbus.real.unit-id=$UnitId",
    "-Dmodbus.real.start-register=$StartRegister",
    "-Dmodbus.real.point-count=$PointCount",
    "-Dmodbus.real.sample-interval-ms=$SampleIntervalMs",
    "-Dmodbus.real.data-type=$DataType",
    "-Dmodbus.real.byte-order=$ByteOrder",
    "test"
)

Write-Host "Testing Modbus TCP server ${ServerHost}:${Port}, registers ${StartRegister} to $($StartRegister + $PointCount - 1)."
& mvn @mavenArguments
if ($LASTEXITCODE -ne 0) {
    throw "Modbus TCP acceptance failed. Maven exit code: $LASTEXITCODE"
}
Write-Host "Modbus TCP acceptance passed."
