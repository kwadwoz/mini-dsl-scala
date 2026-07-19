#!/usr/bin/env python3
"""Generic deploy: build the current design.v on the Manhattan cloud and drive
it with one or more input sets, using the corrected top-level API routes.

Reads the register map from the generated client_sdk.py, so it works for any
program. Build once, then run every input set on the assigned board.

Usage:
  # generate design.v + client_sdk.py first:  scala-cli run . -- examples/max.dsl
  python3 deploy_any.py "a=7,b=3" "a=2,b=9" "a=4,b=4"
"""
import re, sys, time, requests
from manhattan_reasoning_gym import _credentials

API = "https://api.manhattanreasoning.com"
KEY = _credentials.load(API)
H = {"X-API-Key": KEY}
assert KEY, "no API key (run `mrg login`)"

# --- parse the register map out of client_sdk.py ---
regs = {}  # NAME -> (offset, dir)
for line in open("client_sdk.py"):
    m = re.match(r"\s*([A-Z0-9_]+)\s*=\s*(0x[0-9A-Fa-f]+)\s*#\s*(in|out)", line)
    if m:
        regs[m.group(1)] = (int(m.group(2), 16), m.group(3))
CTRL = regs["CTRL"][0]
STATUS = regs["STATUS"][0]
RESULT = regs["RESULT"][0]
inputs = [n for n, (_, d) in regs.items() if d == "in" and n != "CTRL"]
print(f"[regmap] inputs={inputs}  CTRL={CTRL:#x} STATUS={STATUS:#x} RESULT={RESULT:#x}")

# --- 1. build (top-level /submit; board assigned after build) ---
with open("design.v", "rb") as f:
    r = requests.post(f"{API}/submit", headers=H,
                      files={"file": ("design.v", f)}, data={"top": "top"})
r.raise_for_status()
job_id = r.json()["job_id"]
print(f"[build] job_id={job_id} … waiting")
fpga, status = None, None
deadline = time.time() + 2400
while time.time() < deadline:
    b = requests.get(f"{API}/jobs/{job_id}", headers=H); b.raise_for_status()
    body = b.json(); status = body.get("status"); fpga = body.get("fpga_id")
    if status in ("complete", "failed", "cancelled"):
        break
    time.sleep(3)
if status != "complete":
    logs = requests.get(f"{API}/jobs/{job_id}/logs", headers=H)
    sys.exit("[build] failed:\n" + (logs.text[-2000:] if logs.ok else "no logs"))
print(f"[build] complete on fpga{fpga}")

# --- register I/O (POST /fpga/{id}/run, poll top-level /jobs/{job_id}) ---
def run_op(op, address, data, count):
    r = requests.post(f"{API}/fpga/{fpga}/run",
                      headers={**H, "Content-Type": "application/json"},
                      json={"op": op, "address": address, "data": data, "count": count})
    r.raise_for_status()
    jid = r.json()["job_id"]
    for _ in range(240):
        s = requests.get(f"{API}/jobs/{jid}", headers=H); s.raise_for_status()
        j = s.json()
        if j.get("status") == "complete":
            if j.get("data") is not None: return j["data"]
            if (j.get("result") or {}).get("data") is not None: return j["result"]["data"]
            rr = requests.get(f"{API}/jobs/{jid}/result", headers=H)
            return rr.json().get("data", []) if rr.ok else []
        if j.get("status") in ("failed", "cancelled"):
            raise RuntimeError(f"run job {j.get('status')}: {j}")
        time.sleep(0.1)
    raise TimeoutError("run op timeout")

def write(addr, val): run_op(1, addr, [val], 0)
def read(addr):       return run_op(2, addr, [], 1)[0]

# --- 2. drive each input set ---
sets = sys.argv[1:] or ["a=7,b=3"]
print()
for spec in sets:
    vals = dict(kv.split("=") for kv in spec.split(","))
    for name in inputs:
        write(regs[name][0], int(vals[name.lower()] if name.lower() in vals else vals[name]))
    write(CTRL, 1)
    for _ in range(400):
        if read(STATUS) & 1: break
        time.sleep(0.02)
    res = read(RESULT)
    print(f">>> {spec:16s} -> RESULT = {res}")
