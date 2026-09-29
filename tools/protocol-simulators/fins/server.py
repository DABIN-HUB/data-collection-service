"""Standalone FINS/UDP memory-area-read server for reference-device validation.

Implements actual FINS/UDP 01 01 memory-area-read frames (CIO word/bit, DM word),
with fixed memory and explicit FINS end codes; not a Collector-internal mock.
"""
import argparse
import logging
import socket
import struct

DM = [0] * 128
DM[0] = 75
for offset, value in ((1, 23.5), (3, 101.25), (5, 14.75)):
    DM[offset:offset + 2] = struct.unpack(">HH", struct.pack(">f", value))
DM[7] = 9
CIO = [1] + [0] * 127


def respond(frame):
    if len(frame) < 18 or frame[10:12] != b"\x01\x01":
        return None
    area = frame[12]
    word = int.from_bytes(frame[13:15], "big")
    bit = frame[15]
    count = int.from_bytes(frame[16:18], "big")
    memory = DM if area in (0x82, 0x02) else CIO if area in (0xB0, 0x30) else None
    bit_read = area in (0x02, 0x30)
    valid = (memory is not None and 0 < count <= 512 and word < len(memory)
             and (word * 16 + bit + count <= len(memory) * 16 if bit_read else word + count <= len(memory)))
    payload = bytearray()
    if valid:
        if bit_read:
            payload.extend((memory[(word * 16 + bit + i) // 16] >> ((word * 16 + bit + i) % 16)) & 1 for i in range(count))
        else:
            payload.extend(b"".join(struct.pack(">H", value) for value in memory[word:word + count]))
    header = bytes([0xC0, frame[1], frame[2], frame[6], frame[7], frame[8],
                    frame[3], frame[4], frame[5], frame[9]])
    logging.info("FINS read SID=%d area=0x%02X word=%d bit=%d count=%d end=%s",
                 frame[9], area, word, bit, count, "0000" if valid else "1103")
    return header + frame[10:12] + (b"\x00\x00" if valid else b"\x11\x03") + payload


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=19600)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.port))
    logging.info("FINS/UDP server ready port=%d DM0=75 CIO0=0x0001", args.port)
    while True:
        frame, sender = sock.recvfrom(4096)
        response = respond(frame)
        if response is not None:
            sock.sendto(response, sender)


if __name__ == "__main__":
    main()
