using System.Net;
using System.Net.Sockets;
using Dreamine.PLC.Abstractions.Devices;
using Dreamine.PLC.Core.Memory;
using Dreamine.PLC.Mitsubishi.MC.Simulation;

const int publicPort = 15001;
const int dreaminePort = 15002;
var memory = new InMemoryPlcMemory();

// MC 浮点值按相邻两个 16 位 D 寄存器的小端字序写入。
WriteWords(memory, 0, 1, 2);
WriteWords(memory, 100, 0x0000, 0x4148); // 12.5f
WriteWords(memory, 102, 0x0000, unchecked((short)0xC050)); // -3.25f
WriteWords(memory, 200, unchecked((short)0x8000), 0x42B1); // 88.75f

// Dreamine 1.0.1 将 3E 子头序列化为 00 50 / 00 D0，和标准字节次序相反。
// 仅在仿真器边界翻转这两个子头字节；命令、地址、内存和载荷仍交由真实库处理。
var options = new MitsubishiMcSimulatorServerOptions
{
    Host = "127.0.0.1",
    Port = dreaminePort,
    EnableAutoWordResponse = false,
};

await using var server = new MitsubishiMcTcpSimulatorServer(options, memory);
server.StatusChanged += (_, message) => Console.WriteLine($"[{DateTimeOffset.UtcNow:O}] {message}");
using var shutdown = new CancellationTokenSource();
Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    shutdown.Cancel();
};

await server.StartAsync(shutdown.Token);
using var publicListener = new TcpListener(IPAddress.Any, publicPort);
publicListener.Start();
Console.WriteLine($"MC_DOCKER_SIM_READY endpoint=0.0.0.0:{publicPort} source=Dreamine.PLC.Mitsubishi.MC@1.0.1 wire=standard-binary-3e shimTarget=127.0.0.1:{dreaminePort}");

var sessions = new List<Task>();
try
{
    while (!shutdown.IsCancellationRequested)
    {
        try
        {
            var client = await publicListener.AcceptTcpClientAsync(shutdown.Token);
            sessions.Add(HandleStandardClientAsync(client, dreaminePort, shutdown.Token));
        }
        catch (OperationCanceledException)
        {
            break;
        }
        catch (ObjectDisposedException)
        {
            break;
        }
    }
}
finally
{
    publicListener.Stop();
    try
    {
        await Task.WhenAll(sessions);
    }
    catch
    {
        // 关闭期间允许各独立客户端正常断开。
    }
    await server.StopAsync();
}

static async Task HandleStandardClientAsync(TcpClient client, int dreaminePort, CancellationToken cancellationToken)
{
    using (client)
    {
        using var upstream = new TcpClient();
        await upstream.ConnectAsync(IPAddress.Loopback, dreaminePort, cancellationToken);
        await using NetworkStream clientStream = client.GetStream();
        await using NetworkStream upstreamStream = upstream.GetStream();

        while (!cancellationToken.IsCancellationRequested)
        {
            var request = await ReadBinary3EFrameAsync(clientStream, cancellationToken);
            if (request is null)
            {
                return;
            }
            if (request.Length < 21 || request[0] != 0x50 || request[1] != 0x00)
            {
                throw new IOException("Expected a standard MC Binary 3E request with sub-header 50 00.");
            }

            var translatedRequest = (byte[])request.Clone();
            translatedRequest[0] = 0x00;
            translatedRequest[1] = 0x50;
            await upstreamStream.WriteAsync(translatedRequest, cancellationToken);
            await upstreamStream.FlushAsync(cancellationToken);

            var translatedResponse = await ReadBinary3EFrameAsync(upstreamStream, cancellationToken)
                ?? throw new IOException("Dreamine MC server closed before sending a response.");
            if (translatedResponse.Length < 11 || translatedResponse[0] != 0x00 || translatedResponse[1] != 0xD0)
            {
                throw new IOException("Dreamine MC server returned an unexpected Binary 3E response.");
            }

            var standardResponse = (byte[])translatedResponse.Clone();
            standardResponse[0] = 0xD0;
            standardResponse[1] = 0x00;
            await clientStream.WriteAsync(standardResponse, cancellationToken);
            await clientStream.FlushAsync(cancellationToken);
        }
    }
}

static async Task<byte[]?> ReadBinary3EFrameAsync(NetworkStream stream, CancellationToken cancellationToken)
{
    var header = new byte[9];
    var firstRead = await stream.ReadAsync(header.AsMemory(0, 1), cancellationToken);
    if (firstRead == 0)
    {
        return null;
    }

    await ReadExactlyAsync(stream, header.AsMemory(1), cancellationToken);
    var dataLength = header[7] | (header[8] << 8);
    if (dataLength < 2 || dataLength > 8192)
    {
        throw new IOException($"Invalid MC Binary 3E data length: {dataLength}.");
    }

    var body = new byte[dataLength];
    await ReadExactlyAsync(stream, body, cancellationToken);
    var frame = new byte[header.Length + body.Length];
    Buffer.BlockCopy(header, 0, frame, 0, header.Length);
    Buffer.BlockCopy(body, 0, frame, header.Length, body.Length);
    return frame;
}

static async Task ReadExactlyAsync(NetworkStream stream, Memory<byte> buffer, CancellationToken cancellationToken)
{
    var offset = 0;
    while (offset < buffer.Length)
    {
        var read = await stream.ReadAsync(buffer[offset..], cancellationToken);
        if (read == 0)
        {
            throw new IOException("MC client closed the connection before a complete frame arrived.");
        }
        offset += read;
    }
}

static void WriteWords(InMemoryPlcMemory memory, int offset, params short[] values)
{
    var result = memory.WriteWords(new PlcAddress(PlcDeviceType.D, offset), values);
    if (!result.IsSuccess)
    {
        throw new InvalidOperationException(result.Message ?? $"Unable to initialize D{offset}");
    }
}
