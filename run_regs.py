#!/usr/bin/env python3
"""Register I/O against an already-programmed board, using the CORRECT
top-level job-poll route (GET /jobs/{job_id}), not /fpga/{id}/jobs/{job_id}.

Usage: python3 run_regs.py <fpga_id> <n>
"""
import sys, time, requests
from manhattan_reasoning_gym import _credentials

API = "https://api.manhattanreasoning.com"
KEY = _credentials.load(API)
H = {"X-API-Key": KEY}
CTRL, STATUS, N, RESULT = 0x00, 0x04, 0x10, 0x40

fpga = int(sys.argv[1]) if len(sys.argv) > 1 else 3
n_in = int(sys.argv[2]) if len(sys.argv) > 2 else 10

def run_op(op, address, data, count):
    r = requests.post(f"{API}/fpga/{fpga}/run",
                      headers={**H, "Content-Type": "application/json"},
                      json={"op": op, "address": address, "data": data, "count": count})
    r.raise_for_status()
    jid = r.json()["job_id"]
    for _ in range(240):
        s = requests.get(f"{API}/jobs/{jid}", headers=H)
        s.raise_for_status()
        b = s.json()
        st = b.get("status")
        if st == "complete":
            if b.get("data") is not None:
                return b["data"]
            res = b.get("result") or {}
            if res.get("data") is not None:
                return res["data"]
            rr = requests.get(f"{API}/jobs/{jid}/result", headers=H)
            if rr.ok:
                return rr.json().get("data", [])
            return []
        if st in ("failed", "cancelled"):
            raise RuntimeError(f"run job {st}: {b}")
        time.sleep(0.1)
    raise TimeoutError("run op did not complete")

def write(addr, val): run_op(1, addr, [val], 0)
def read(addr):       return run_op(2, addr, [], 1)[0]

print(f"[fpga{fpga}] writing N={n_in}")
write(N, n_in)
write(CTRL, 1)                       # pulse start
for _ in range(200):
    if read(STATUS) & 1:
        break
    time.sleep(0.02)
res = read(RESULT)
print(f"\n>>> sum(0..{n_in-1}) = {res}   (expected {n_in*(n_in-1)//2})")
print(">>> MATCH" if res == n_in*(n_in-1)//2 else ">>> MISMATCH")
