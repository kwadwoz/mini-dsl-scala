#!/usr/bin/env python3
"""Deploy a generated design.v to the Manhattan Reasoning FPGA cloud.

Works around the installed SDK's wrong submit route: the real build-submit
endpoint is the top-level POST /submit (the server assigns a board *after* the
build), not POST /fpga/{id}/submit. Register I/O (/fpga/{id}/run) in the SDK is
correct, so we reuse _client.read/write for that.
"""
import sys, time, requests
from manhattan_reasoning_gym import _credentials, _client

API = "https://api.manhattanreasoning.com"
KEY = _credentials.load(API)
H = {"X-API-Key": KEY}
assert KEY, "no API key (run `mrg login`)"

# Register map (byte offsets) — must match the generated client_sdk.py.
CTRL, STATUS, N, RESULT = 0x00, 0x04, 0x10, 0x40
n_in = int(sys.argv[1]) if len(sys.argv) > 1 else 10

# 1. Submit the build to the CORRECT top-level route.
with open("design.v", "rb") as f:
    r = requests.post(f"{API}/submit", headers=H,
                      files={"file": ("design.v", f)},
                      data={"top": "top"})
print(f"[submit] HTTP {r.status_code}: {r.text[:300]}")
r.raise_for_status()
job_id = r.json()["job_id"]
print(f"[submit] job_id = {job_id}")

# 2. Poll the build job (top-level /jobs/{job_id}); response reveals the board.
fpga_id, status = None, None
deadline = time.time() + 2400
while time.time() < deadline:
    j = requests.get(f"{API}/jobs/{job_id}", headers=H)
    j.raise_for_status()
    body = j.json()
    status = body.get("status")
    fpga_id = (body.get("fpga_id") if body.get("fpga_id") is not None
               else (body.get("result") or {}).get("fpga_id"))
    print(f"[build] status={status} fpga_id={fpga_id}")
    if status in ("complete", "failed", "cancelled"):
        break
    time.sleep(3)

if status != "complete":
    logs = requests.get(f"{API}/jobs/{job_id}/logs", headers=H)
    print("[build] logs:\n" + (logs.text[-3000:] if logs.ok else "no logs"))
    sys.exit(1)
assert fpga_id is not None, f"build complete but no fpga_id in response: {body}"
print(f"[build] complete on fpga{fpga_id}")

# 3. Drive the design over Wishbone registers (SDK's correct /fpga/{id}/run).
_client.write(fpga_id, KEY, N, [n_in], API)     # host input
_client.write(fpga_id, KEY, CTRL, [1], API)     # pulse start
for _ in range(200):
    if _client.read(fpga_id, KEY, STATUS, 1, API)[0] & 1:
        break
    time.sleep(0.02)
res = _client.read(fpga_id, KEY, RESULT, 1, API)[0]
print(f"\n>>> sum(0..{n_in-1}) = {res}   (expected {n_in*(n_in-1)//2})")
print(">>> MATCH" if res == n_in*(n_in-1)//2 else ">>> MISMATCH")
