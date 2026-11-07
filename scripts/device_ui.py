"""Inspect/tap only this test app through a fresh Android UI hierarchy."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from android_tools import find_adb

PACKAGE = "com.radiosport.ninegradio.hfplus"


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, timeout=45).stdout


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", help="ADB executable (otherwise PATH or Android SDK environment)")
    p.add_argument("--serial", required=True)
    p.add_argument("--start", action="store_true")
    p.add_argument("--tap-text")
    p.add_argument("--tap-desc")
    p.add_argument("--tap-id")
    args = p.parse_args()
    adb = [find_adb(args.adb), "-s", args.serial]
    if args.start:
        run(*adb, "shell", "am", "start", "-W", "-n", f"{PACKAGE}/com.radiosport.ninegradio.ui.MainActivity")
    remote = f"/data/local/tmp/9gradio-ui-{time.time_ns()}.xml"
    dump = run(*adb, "shell", "uiautomator", "dump", remote)
    if b"dumped" not in dump:
        raise SystemExit("UI dump failed or never became idle; refusing to read an old hierarchy")
    data = run(*adb, "shell", "cat", remote)
    run(*adb, "shell", "rm", remote)
    Path("build/audit/device-ui.xml").write_bytes(data)
    nodes = [n.attrib for n in ET.fromstring(data).iter("node")]
    if not any(n.get("package") == PACKAGE for n in nodes):
        raise SystemExit("Test app is not visible; refusing to interact with another app")
    selector = next(((key, value) for key, value in [
        ("text", args.tap_text), ("content-desc", args.tap_desc),
        ("resource-id", args.tap_id)] if value is not None), None)
    if selector:
        key, value = selector
        matches = [n for n in nodes if n.get("package") == PACKAGE and n.get(key) == value
                   and n.get("bounds") != "[0,0][0,0]"]
        if len(matches) != 1:
            raise SystemExit(f"Expected one visible match for {selector}, found {len(matches)}")
        n = matches[0]
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n["bounds"]))
        run(*adb, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        print(json.dumps({"tapped": selector, "bounds": n["bounds"]}))
    else:
        for n in nodes:
            if n.get("package") == PACKAGE and (n.get("text") or n.get("clickable") == "true"):
                print(json.dumps({k: n.get(k) for k in ["text", "content-desc", "resource-id", "bounds"]}))
