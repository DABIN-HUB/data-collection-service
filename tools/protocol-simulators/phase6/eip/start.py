#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""启动固定版本 cpppo，并通过真实 CIP 写入初始化本轮独占的五个普通 Tag。"""
from __future__ import print_function

import binascii
import signal
import socket
import struct
import subprocess
import sys
import time

TAGS = [
    ("Run", "BOOL", 0xC1, "B", 1),
    ("CPULoad", "REAL", 0xCA, "f", 31.25),
    ("MotorSpeed", "REAL", 0xCA, "f", 1475.5),
    ("LinePressure", "REAL", 0xCA, "f", 0.625),
    ("Count", "DINT", 0xC4, "i", 246810),
]


def receive_exact(sock, size):
    data = b""
    while len(data) < size:
        part = sock.recv(size - len(data))
        if not part:
            raise IOError("CIP connection closed before a complete frame")
        data += part
    return data


def exchange(sock, command, session, payload):
    context = b"PHASE6  "
    request = struct.pack("<HHII8sI", command, len(payload), session, 0, context, 0) + payload
    sock.sendall(request)
    header = receive_exact(sock, 24)
    actual_command, length, actual_session, status, actual_context, options = struct.unpack("<HHII8sI", header)
    response = receive_exact(sock, length)
    if actual_command != command or status != 0 or actual_context != context or options != 0:
        raise IOError("Invalid EtherNet/IP encapsulation response")
    if command != 0x65 and actual_session != session:
        raise IOError("EtherNet/IP session mismatch")
    return actual_session, response


def initialize_tags():
    sock = socket.create_connection(("127.0.0.1", 44818), 2)
    try:
        sock.settimeout(2)
        session, response = exchange(sock, 0x65, 0, struct.pack("<HH", 1, 0))
        if session == 0 or response != struct.pack("<HH", 1, 0):
            raise IOError("RegisterSession failed")
        for name, label, cip_type, encoding, value in TAGS:
            symbol = name.encode("ascii")
            path = struct.pack("BB", 0x91, len(symbol)) + symbol
            if len(path) % 2:
                path += b"\0"
            cip = struct.pack("BB", 0x4D, len(path) // 2) + path
            cip += struct.pack("<HH", cip_type, 1) + struct.pack("<" + encoding, value)
            payload = struct.pack("<IHHHHHH", 0, 0, 2, 0, 0, 0xB2, len(cip)) + cip
            _, response = exchange(sock, 0x6F, session, payload)
            offset = 8
            result = None
            for _ in range(struct.unpack_from("<H", response, 6)[0]):
                item_type, size = struct.unpack_from("<HH", response, offset)
                offset += 4
                if item_type == 0xB2:
                    result = response[offset:offset + size]
                offset += size
            if result is None or len(result) < 4 or bytearray(result)[0] != 0xCD or bytearray(result)[2] != 0:
                raise IOError("CIP Write Tag failed for " + name)
            print("CIP_MEMORY_INITIALIZED tag=%s type=%s value=%s ack=%s" %
                  (name, label, value, binascii.hexlify(result).decode("ascii")))
        sock.sendall(struct.pack("<HHII8sI", 0x66, 0, session, 0, b"PHASE6  ", 0))
    finally:
        sock.close()


def main():
    command = [sys.executable, "-u", "-m", "cpppo.server.enip", "--address", "0.0.0.0:44818", "--no-udp", "--print"]
    command += [name + "=" + label for name, label, _, _, _ in TAGS]
    process = subprocess.Popen(command)
    stopping = [False]

    def stop(signum, frame):
        stopping[0] = True
        if process.poll() is None:
            process.terminate()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        deadline = time.time() + 20
        while not stopping[0]:
            try:
                initialize_tags()
                print("EIP_DOCKER_SIM_READY tags=5 source=cpppo native=true")
                break
            except (IOError, socket.error):
                if process.poll() is not None or time.time() >= deadline:
                    raise
                time.sleep(0.25)
        return process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
        process.wait()


if __name__ == "__main__":
    sys.exit(main())
