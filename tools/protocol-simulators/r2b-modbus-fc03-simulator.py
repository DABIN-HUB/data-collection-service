"""仅供 R2-B 隔离验收使用的 Modbus TCP FC03/FC04 仿真器。"""

import argparse
import socket
import struct
import threading

REGISTERS = {0: 2150, 1: 3275, 2: 4096, 3: 5120}
MAX_FRAME_LENGTH = 254
MAX_CLIENTS = 8


class ClientDisconnected(Exception):
    """客户端正常关闭或连接被重置。"""


def recv_exact(connection, count):
    """读取完整帧片段；合法分包继续等待，EOF 不交给协议解析器。"""
    data = bytearray()
    while len(data) < count:
        try:
            chunk = connection.recv(count - len(data))
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError) as error:
            raise ClientDisconnected(str(error)) from error
        if not chunk:
            raise ClientDisconnected("客户端在帧读取过程中关闭连接")
        data.extend(chunk)
    return bytes(data)


def serve_client(connection, address, permits):
    """单个客户端断连只释放该连接，不影响监听器和其他客户端。"""
    try:
        with connection:
            connection.settimeout(10)
            while True:
                try:
                    header = recv_exact(connection, 7)
                    transaction, protocol, length, unit = struct.unpack(">HHHB", header)
                    if protocol != 0 or not 2 <= length <= MAX_FRAME_LENGTH:
                        print(f"MODBUS_INVALID_HEADER peer={address[0]} length={length} protocol={protocol}", flush=True)
                        return
                    payload = recv_exact(connection, length - 1)
                    function = payload[0]
                    if function in (3, 4):
                        if len(payload) != 5:
                            print(f"MODBUS_INVALID_PDU peer={address[0]} length={len(payload)}", flush=True)
                            return
                        start, quantity = struct.unpack(">HH", payload[1:])
                        if not 1 <= quantity <= 125:
                            body = bytes((function | 0x80, 3))
                        else:
                            values = (REGISTERS.get(start + index, 0) for index in range(quantity))
                            body = bytes((function, quantity * 2)) + b"".join(struct.pack(">H", value) for value in values)
                    else:
                        body = bytes((function | 0x80, 1))
                    response = struct.pack(">HHHB", transaction, 0, len(body) + 1, unit) + body
                    try:
                        connection.sendall(response)
                    except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError) as error:
                        raise ClientDisconnected(str(error)) from error
                    print(f"MODBUS_REQUEST tx={transaction} unit={unit} fc={function} bytes={len(payload)}", flush=True)
                except socket.timeout:
                    print(f"MODBUS_IDLE_TIMEOUT peer={address[0]}", flush=True)
                    return
                except ClientDisconnected as error:
                    print(f"MODBUS_CLIENT_DISCONNECTED peer={address[0]} reason={error}", flush=True)
                    return
    finally:
        permits.release()


def main():
    parser = argparse.ArgumentParser(description="R2-B 隔离 Modbus TCP 双点仿真器")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=36502)
    args = parser.parse_args()
    permits = threading.BoundedSemaphore(MAX_CLIENTS)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((args.host, args.port))
        listener.listen(MAX_CLIENTS)
        print(f"MODBUS_SIM_LISTENING {args.host}:{listener.getsockname()[1]}", flush=True)
        while True:
            connection, address = listener.accept()
            if not permits.acquire(blocking=False):
                print("MODBUS_CLIENT_LIMIT_REACHED", flush=True)
                connection.close()
                continue
            threading.Thread(target=serve_client, args=(connection, address, permits), daemon=True).start()


if __name__ == "__main__":
    main()
