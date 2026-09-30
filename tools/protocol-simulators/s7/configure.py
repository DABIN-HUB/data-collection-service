"""Seed DB1 on fbarresi/SoftPlc over its management API (real S7 reads use port 11020)."""
import base64
import json
import struct
import sys
import urllib.request

base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18082"
url = base.rstrip("/") + "/api/DataBlocks/1"
with urllib.request.urlopen(url) as response:
    current = json.load(response)
data = bytearray(base64.b64decode(current["data"]))
data[0] = (data[0] & ~3) | 1  # DBX0.0=1, DBX0.1=0
data[1] |= 1  # DBX1.0=1
data[4:8] = struct.pack(">f", 12.34)  # DB1.DBD4 Siemens REAL
adata = struct.pack(">f", 56.78)
data[8:12] = adata  # DB1.DBD8 Siemens REAL
payload = json.dumps(base64.b64encode(data).decode("ascii")).encode()
request = urllib.request.Request(url, data=payload, method="PUT", headers={"Content-Type": "application/json"})
with urllib.request.urlopen(request) as response:
    print("DB1 seeded", response.status, "length", len(data))
with urllib.request.urlopen(url) as response:
    saved = bytearray(base64.b64decode(json.load(response)["data"]))
print("DBX0.0", bool(saved[0] & 1), "DBX0.1", bool(saved[0] & 2),
      "DBX1.0", bool(saved[1] & 1), "DBD4", struct.unpack(">f", saved[4:8])[0],
      "DBD8", struct.unpack(">f", saved[8:12])[0])
