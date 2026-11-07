"""Profile the visible test app while alternating vertical Hz-digit tuning gestures."""
import argparse
from pathlib import Path
import subprocess
import time
from android_tools import find_adb

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--serial", required=True)
p.add_argument("--seconds", type=float, default=60)
p.add_argument("--x", type=int, required=True)
p.add_argument("--y1", type=int, required=True)
p.add_argument("--y2", type=int, required=True)
p.add_argument("--adb", help="ADB executable (otherwise PATH or Android SDK environment)")
args = p.parse_args()
adb = [find_adb(args.adb), "-s", args.serial]
package = "com.radiosport.ninegradio.hfplus"

def run(*words):
    return subprocess.run(adb + list(words), capture_output=True, check=True, timeout=30).stdout

activity = run("shell", "dumpsys", "activity", "activities").decode(errors="replace")
if not any(package in line and "topResumedActivity" in line for line in activity.splitlines()):
    raise SystemExit("Test app is not the foreground activity; refusing to inject input")
run("shell", "dumpsys", "gfxinfo", package, "reset")
start = time.monotonic()
count = 0
while time.monotonic() - start < args.seconds:
    y1, y2 = (args.y1, args.y2) if count % 2 == 0 else (args.y2, args.y1)
    run("shell", "input", "swipe", str(args.x), str(y1), str(args.x), str(y2), "200")
    count += 1
path = Path("build/audit/alpha3-tuning-gfxinfo.txt")
path.write_bytes(run("shell", "dumpsys", "gfxinfo", package, "framestats"))
print({"seconds": round(time.monotonic() - start, 2), "gestures": count, "report": str(path)})
