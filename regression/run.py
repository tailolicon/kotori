"""Translation regression suite runner.

Usage:
    python regression/run.py [--serial 127.0.0.1:7555] [--bless] [--only PREFIX]

Pushes the fixture corpus to the debug app, has it translate every page with the deterministic
provider, pulls the rendered pages + decision traces back, and compares them against the blessed
goldens in regression/golden/.

    --bless   accept the current output as the new golden (do this once per *reviewed* change)
    --only    run a subset, e.g. --only asg8

Exit code 0 = no differences. Any difference produces a side-by-side sheet in regression/report/
so the change can be judged by eye, plus a text diff of the decision trace that usually names the
guard responsible.

The whole point: every page here exposed a real bug once. If a "fix" regresses one of them, this
fails in minutes, on the desk — not days later in the user's screenshots.
"""
import argparse
import difflib
import os
import shutil
import subprocess
import sys
import time

import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
HERE = os.path.dirname(os.path.abspath(__file__))
CORPUS = os.path.join(HERE, "corpus")
GOLDEN = os.path.join(HERE, "golden")
DEVICE_OUT = os.path.join(HERE, "device-out")
REPORT = os.path.join(HERE, "report")

PKG = "app.mihon.dev"
RECEIVER = f"{PKG}/mihon.feature.translation.debug.RegressionReceiver"
DEV_BASE = f"/storage/emulated/0/Android/data/{PKG}/files/regression"

# Differing-pixel fraction above which a page fails. Zero would be ideal and is usually what we
# get; the epsilon absorbs single-pixel antialiasing wobble without ever hiding a real change.
PIXEL_FAIL_FRACTION = 0.0005


def adb(serial, *args, **kw):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, **kw)


def sh(serial, cmd):
    return adb(serial, "shell", cmd).stdout.strip()


def push_corpus(serial, only):
    sh(serial, f"rm -rf {DEV_BASE}/in {DEV_BASE}/out; mkdir -p {DEV_BASE}/in")
    names = sorted(n for n in os.listdir(CORPUS) if not only or n.startswith(only))
    for name in names:
        r = adb(serial, "push", os.path.join(CORPUS, name), f"{DEV_BASE}/in/{name}")
        if r.returncode != 0:
            sys.exit(f"push failed for {name}: {r.stderr.strip()}")
    return names


def run_device(serial, expected):
    # Start from a dead process. A leftover session can still be running reader prefetch, and its
    # detector/OCR then competes with the harness's own — the first time this happened the suite
    # crawled to one page in twenty minutes and timed out. The receiver only schedules work; the
    # process must be alive, idle, and stay alive.
    sh(serial, f"am force-stop {PKG}")
    time.sleep(2)
    sh(serial, f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    time.sleep(6)
    adb(serial, "shell", f"am broadcast -n {RECEIVER}")
    # Wait on *progress*, not a total-time budget. A fixed deadline once expired while the device
    # was healthy and two-thirds done — emulator throughput swings several-fold between runs. What
    # actually indicates trouble is output stalling: no new file for several minutes means the
    # process died or a page wedged.
    last_count, last_change = -1, time.time()
    while True:
        done = sh(serial, f"cat {DEV_BASE}/out/DONE.txt 2>/dev/null")
        if done:
            print(done.strip())
            return
        count = int(sh(serial, f"ls {DEV_BASE}/out 2>/dev/null | wc -l") or 0)
        if count != last_count:
            last_count, last_change = count, time.time()
            print(f"  … {count} output files")
        elif time.time() - last_change > 60 * 6:
            sys.exit("device output stalled for 6 min — check `adb logcat -s RegressionReceiver` "
                     "and whether the process is alive")
        time.sleep(15)


def pull_output(serial):
    shutil.rmtree(DEVICE_OUT, ignore_errors=True)
    os.makedirs(DEVICE_OUT, exist_ok=True)
    r = adb(serial, "pull", f"{DEV_BASE}/out/.", DEVICE_OUT)
    if r.returncode != 0:
        sys.exit(f"pull failed: {r.stderr.strip()}")


def load(path):
    im = Image.open(path)
    im.load()
    return np.asarray(im.convert("RGB")).astype(np.int16)


def recorded_hash(name):
    path = os.path.join(GOLDEN, "PIXELS.sha256")
    if not os.path.exists(path):
        return None
    for line in open(path, encoding="utf8"):
        h, _, f = line.strip().partition("  ")
        if f == name + ".png":
            return h
    return None


def compare_page(name):
    """Returns (status, detail). Sheet + trace diff are written for failures."""
    golden_png = os.path.join(GOLDEN, name + ".png")
    out_png = os.path.join(DEVICE_OUT, name + ".png")
    expected = recorded_hash(name)
    if not os.path.exists(golden_png) and not os.path.exists(out_png) and expected is None:
        return "SKIP", "no output on either side (NothingToTranslate both runs)"
    if not os.path.exists(golden_png) and expected is None:
        return "NEW", "output exists but no golden — run --bless after review"
    if not os.path.exists(out_png):
        return "MISSING", "golden exists but this run produced no output"

    # Golden images live only on the machine that blessed them (203 MB does not belong in git);
    # the committed hash still catches any pixel change on a fresh clone — it just cannot draw the
    # side-by-side sheet, so it says so.
    if not os.path.exists(golden_png):
        import hashlib
        actual = hashlib.sha256(open(out_png, "rb").read()).hexdigest()
        if actual == expected:
            return "OK", "hash match (no local golden image)"
        return "FAIL", "pixels changed vs recorded hash — no local golden to sheet against; " \
                       "bless on a clean checkout first if you need the visual"

    g, o = load(golden_png), load(out_png)
    if g.shape != o.shape:
        return "FAIL", f"size changed {g.shape[1]}x{g.shape[0]} -> {o.shape[1]}x{o.shape[0]}"
    diff = np.abs(g - o).sum(axis=2) > 30
    frac = float(diff.mean())
    if frac <= PIXEL_FAIL_FRACTION:
        return "OK", f"diff {frac:.6f}"

    os.makedirs(REPORT, exist_ok=True)
    rows = np.flatnonzero(diff.sum(axis=1) > 4)
    lo = max(0, int(rows.min()) - 120) if len(rows) else 0
    hi = min(g.shape[0], int(rows.max()) + 120) if len(rows) else min(g.shape[0], 1600)
    side = np.concatenate(
        [g[lo:hi], np.full((hi - lo, 8, 3), 255, np.int16), o[lo:hi]], axis=1,
    ).astype(np.uint8)
    sheet = Image.fromarray(side)
    if sheet.height > 6000:
        sheet = sheet.resize((sheet.width // 2, sheet.height // 2), Image.LANCZOS)
    sheet.save(os.path.join(REPORT, f"{name}.golden-vs-now.png"))
    return "FAIL", f"diff {frac:.4%} rows {lo}..{hi} — see report/{name}.golden-vs-now.png"


# Lines the device emits once per process rather than once per page, so they land in whichever
# fixture happened to trigger them. Dropped on both sides — goldens blessed before the receiver
# started filtering them still compare equal.
TRACE_NOISE = ("Bubble detector ready",)


def read_trace(path):
    lines = open(path, encoding="utf8", errors="replace").read().splitlines()
    return [l for l in lines if not any(n in l for n in TRACE_NOISE)]


def compare_trace(name):
    golden_t = os.path.join(GOLDEN, name + ".trace.txt")
    out_t = os.path.join(DEVICE_OUT, name + ".trace.txt")
    if not (os.path.exists(golden_t) and os.path.exists(out_t)):
        return None
    a = read_trace(golden_t)
    b = read_trace(out_t)
    if a == b:
        return None
    os.makedirs(REPORT, exist_ok=True)
    diff = "\n".join(difflib.unified_diff(a, b, "golden", "now", lineterm=""))
    with open(os.path.join(REPORT, f"{name}.trace.diff"), "w", encoding="utf8") as fh:
        fh.write(diff)
    return f"trace changed ({sum(1 for l in diff.splitlines() if l[:1] in '+-') - 2} lines) — report/{name}.trace.diff"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="127.0.0.1:7555")
    ap.add_argument("--bless", action="store_true")
    ap.add_argument("--only", default=None)
    ap.add_argument("--compare-only", action="store_true",
                    help="skip the device pass; pull whatever is in out/ and compare")
    args = ap.parse_args()

    subprocess.run(["adb", "connect", args.serial], capture_output=True)
    if args.compare_only:
        names = sorted(n for n in os.listdir(CORPUS) if not args.only or n.startswith(args.only))
    else:
        names = push_corpus(args.serial, args.only)
        print(f"pushed {len(names)} fixtures; running on device…")
        run_device(args.serial, len(names))
    pull_output(args.serial)

    if args.bless:
        os.makedirs(GOLDEN, exist_ok=True)
        stems = {os.path.splitext(n)[0] for n in names}
        for f in os.listdir(DEVICE_OUT):
            if f == "DONE.txt" or os.path.splitext(f)[0].replace(".trace", "") not in stems:
                continue
            shutil.copyfile(os.path.join(DEVICE_OUT, f), os.path.join(GOLDEN, f))
        import hashlib
        hashes = []
        for f in sorted(os.listdir(GOLDEN)):
            if f.endswith(".png"):
                digest = hashlib.sha256(open(os.path.join(GOLDEN, f), "rb").read()).hexdigest()
                hashes.append(f"{digest}  {f}")
        with open(os.path.join(GOLDEN, "PIXELS.sha256"), "w", encoding="utf8") as fh:
            fh.write("\n".join(hashes) + "\n")
        print(f"blessed {len(os.listdir(GOLDEN))} golden files — review `git diff --stat regression/golden` before committing")
        return

    shutil.rmtree(REPORT, ignore_errors=True)
    failures = 0
    for name in [os.path.splitext(n)[0] for n in names]:
        status, detail = compare_page(name)
        trace_note = compare_trace(name)
        flag = {"OK": " ", "SKIP": " "}.get(status, "!")
        if status not in ("OK", "SKIP") or trace_note:
            failures += 1
        print(f" {flag} {status:<7} {name}  {detail}" + (f"\n           {trace_note}" if trace_note else ""))
    print(f"\n{'ALL CLEAR' if failures == 0 else f'{failures} page(s) changed'}")
    sys.exit(0 if failures == 0 else 1)


main()
