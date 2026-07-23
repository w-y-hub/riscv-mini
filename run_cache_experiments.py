#!/usr/bin/env python3
"""Cache Parameter Tuning Experiment"""
import subprocess, re, os, csv, shutil

BASE_DIR = os.path.expanduser("~/riscv-mini")
CONFIG_FILE = os.path.join(BASE_DIR, "src/main/scala/mini/Config.scala")
RESULT_FILE = os.path.join(BASE_DIR, "cache_experiment_results.csv")
TEST_DIR = os.path.join(BASE_DIR, "tests")
GEN_DIR = os.path.join(BASE_DIR, "generated-src")
VTILE_BIN = os.path.join(BASE_DIR, "VTile")
BENCHMARKS = ["median.riscv.hex", "multiply.riscv.hex", "qsort.riscv.hex"]

CONFIGS = [
    ("baseline_2w256s16b",  2, 256, 16),
    ("largeset_2w512s16b",  2, 512, 16),
    ("smallset_2w64s16b",   2,  64, 16),
    ("largeblk_2w256s32b",  2, 256, 32),
    ("smallblk_2w256s8b",   2, 256,  8),
]

# Backup original Config.scala
with open(CONFIG_FILE, "r") as f:
    original = f.read()

results = []
sep = "=" * 50

for name, nways, nsets, bbytes in CONFIGS:
    print(f"\n{sep}")
    print(f"  Config: {name} (nWays={nways}, nSets={nsets}, blockBytes={bbytes})")
    print(sep)

    # Modify Config.scala
    with open(CONFIG_FILE, "r") as f:
        content = f.read()
    content = re.sub(r"nWays\s*=\s*\d+", f"nWays = {nways}", content)
    content = re.sub(r"nSets\s*=\s*\d+", f"nSets = {nsets}", content)
    content = re.sub(r"blockBytes\s*=\s*\d+\s*\*\s*\(xlen\s*/\s*\d+\)", f"blockBytes = {bbytes}", content)
    content = re.sub(r"blockBytes\s*=\s*\d+", f"blockBytes = {bbytes}", content)
    with open(CONFIG_FILE, "w") as f:
        f.write(content)

    # CRITICAL: delete generated-src, target, AND VTile binary
    print("  Cleaning...")
    for d in [GEN_DIR, os.path.join(BASE_DIR, "target"), VTile_BIN]:
        if os.path.isdir(d):
            shutil.rmtree(d)
        elif os.path.isfile(d):
            os.remove(d)

    # Compile Chisel
    print("  Compiling Chisel (sbt)...")
    ret = subprocess.run(
        ["sbt", "-ivy", ".ivy2", "run", f"--target-dir={GEN_DIR}", "--dump-fir"],
        cwd=BASE_DIR, capture_output=True, text=True, timeout=600
    )
    if "success" not in ret.stdout + ret.stderr:
        print(f"  SBT WARNING: {ret.stderr[-200:]}")

    # Compile Verilator
    print("  Compiling Verilator (make verilator)...")
    ret = subprocess.run(
        ["make", "verilator"],
        cwd=BASE_DIR, capture_output=True, text=True, timeout=600
    )
    if ret.returncode != 0:
        print(f"  Verilator FAILED: {ret.stderr[-300:]}")
        continue

    # Run benchmarks
    for bmark in BENCHMARKS:
        hex_file = os.path.join(TEST_DIR, bmark)
        if not os.path.exists(hex_file):
            print(f"  SKIP {bmark}: not found")
            continue

        print(f"  Running {bmark}...", end=" ", flush=True)
        try:
            ret = subprocess.run(
                [VTILE_BIN, hex_file],
                cwd=BASE_DIR, capture_output=True, text=True, timeout=180
            )
        except subprocess.TimeoutExpired:
            print("TIMEOUT")
            continue

        output = ret.stderr

        def parse_int(pattern, text):
            m = re.search(pattern + r"\s*[:=]\s*(\d+)", text)
            return int(m.group(1)) if m else 0

        def parse_float(pattern, text):
            m = re.search(pattern + r"\s*[:=]\s*([\d.]+)", text)
            return float(m.group(1)) if m else 0.0

        total_cycles = parse_int("Total Cycles", output)
        instructions = parse_int("Instructions", output)
        cpi = parse_float("CPI", output)
        icache_access = parse_int(r"I\$ Access", output)
        icache_miss = parse_int(r"I\$ Miss\b", output)
        icache_miss_rate = parse_float(r"I\$ Miss Rate", output)
        icache_stall = parse_int(r"I\$ Stall", output)
        dcache_access = parse_int(r"D\$ Access", output)
        dcache_miss = parse_int(r"D\$ Miss\b", output)
        dcache_miss_rate = parse_float(r"D\$ Miss Rate", output)
        dcache_stall = parse_int(r"D\$ Stall", output)

        print(f"CPI={cpi:.2f} I={icache_miss_rate:.1f}% D={dcache_miss_rate:.1f}%")

        results.append({
            "name": name, "nWays": nways, "nSets": nsets, "blockBytes": bbytes,
            "benchmark": bmark,
            "total_cycles": total_cycles, "instructions": instructions, "cpi": cpi,
            "icache_access": icache_access, "icache_miss": icache_miss,
            "icache_miss_rate": icache_miss_rate, "icache_stall": icache_stall,
            "dcache_access": dcache_access, "dcache_miss": dcache_miss,
            "dcache_miss_rate": dcache_miss_rate, "dcache_stall": dcache_stall,
        })

# Restore original config
with open(CONFIG_FILE, "w") as f:
    f.write(original)

# Restore build
print(f"\n{sep}")
print("  Restoring original build...")
for d in [GEN_DIR, os.path.join(BASE_DIR, "target"), VTile_BIN]:
    if os.path.isdir(d):
        shutil.rmtree(d)
    elif os.path.isfile(d):
        os.remove(d)

subprocess.run(
    ["sbt", "-ivy", ".ivy2", "run", f"--target-dir={GEN_DIR}", "--dump-fir"],
    cwd=BASE_DIR, capture_output=True, timeout=600
)
subprocess.run(["make", "verilator"], cwd=BASE_DIR, capture_output=True, timeout=600)

# Write CSV
with open(RESULT_FILE, "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=[
        "name","nWays","nSets","blockBytes","benchmark",
        "total_cycles","instructions","cpi",
        "icache_access","icache_miss","icache_miss_rate","icache_stall",
        "dcache_access","dcache_miss","dcache_miss_rate","dcache_stall",
    ])
    w.writeheader()
    w.writerows(results)

print(f"\n{sep}")
print(f"  Experiment complete! {len(results)} results in {RESULT_FILE}")
print(sep)
