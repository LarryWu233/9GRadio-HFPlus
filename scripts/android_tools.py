"""Locate Android tools without embedding a developer's local SDK path."""
import os
from pathlib import Path
import shutil


def find_adb(explicit=None):
    if explicit:
        return explicit
    installed = shutil.which("adb")
    if installed:
        return installed
    executable = "adb.exe" if os.name == "nt" else "adb"
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk = os.environ.get(variable)
        if sdk:
            candidate = Path(sdk) / "platform-tools" / executable
            if candidate.is_file():
                return str(candidate)
    raise SystemExit("ADB not found. Add platform-tools to PATH, set ANDROID_SDK_ROOT, or pass --adb.")
