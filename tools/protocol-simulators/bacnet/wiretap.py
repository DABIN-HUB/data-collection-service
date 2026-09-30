"""Loopback BACnet/IP UDP packet capture and forwarding for Windows without Npcap.

Logs actual datagrams (BVLC, NPDU, APDU) sent in either direction; forwards
unchanged bytes to the real BACpypes3 simulator. Do not use as a BACnet mock.
"""
import logging
import socket

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("127.0.0.1", 14781))
backend = ("127.0.0.1", 14780)
client = None
logging.info("BACnet wire tap ready 127.0.0.1:14781 -> 127.0.0.1:14780")
while True:
    payload, addr = sock.recvfrom(4096)
    if addr == backend:
        if client:
            logging.info("SIM->COLLECTOR %s to=%s BVLC=%s payload=%s", addr, client,
                         payload[:4].hex(), payload.hex())
            sock.sendto(payload, client)
    else:
        client = addr
        logging.info("COLLECTOR->SIM %s BVLC=%s payload=%s", addr,
                     payload[:4].hex(), payload.hex())
        sock.sendto(payload, backend)
