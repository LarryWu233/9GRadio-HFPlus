"""Profile the visible receiver while alternating a specified tuning gesture."""
import argparse
from pathlib import Path
import subprocess
import time
from android_tools import find_adb

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--serial", required=True)
p.add_argument("--seconds", type=float, default=60)
p.add_argument("--x", type=int, required=True)
p.add_argument("--x2", type=int, help="Optional horizontal end position; defaults to --x")
p.add_argument("--y1", type=int, required=True)
p.add_argument("--y2", type=int, required=True)
p.add_argument("--adb", help="ADB executable (otherwise PATH or Android SDK environment)")
p.add_argument("--package", default="com.radiosport.ninegradio.hfplus", help="Explicit target app; use .arch for the architecture preview")
p.add_argument("--output", default="build/audit/tuning-gfxinfo.txt")
args = p.parse_args()
adb = [find_adb(args.adb), "-s", args.serial]
package = args.package
path = Path(args.output).resolve()
if not path.is_relative_to(Path.cwd().resolve()):
    raise SystemExit("Output must stay within this workspace")
if not 1 <= args.seconds <= 120:
    raise SystemExit("Choose a short interaction window (1–120 seconds)")

def run(*words):
    return subprocess.run(adb + list(words), capture_output=True, check=True, timeout=30).stdout

def ensure_foreground():
    windows=run("shell", "dumpsys", "window").decode(errors="replace")
    if not any(package+"/com.radiosport.ninegradio.ui.MainActivity" in line and "mCurrentFocus=" in line for line in windows.splitlines()):
        raise SystemExit("Receiver MainActivity lost focus; refusing to inject input")

ensure_foreground()
run("shell", "dumpsys", "gfxinfo", package, "reset")
start = time.monotonic()
count = 0
while time.monotonic() - start < args.seconds:
    ensure_foreground()
    x2=args.x if args.x2 is None else args.x2
    x1, y1, end_x, y2 = (args.x,args.y1,x2,args.y2) if count%2==0 else (x2,args.y2,args.x,args.y1)
    run("shell", "input", "swipe", str(x1), str(y1), str(end_x), str(y2), "200")
    count += 1
path.write_bytes(run("shell", "dumpsys", "gfxinfo", package, "framestats"))
print({"seconds": round(time.monotonic() - start, 2), "gestures": count, "report": str(path.relative_to(Path.cwd().resolve()))})
