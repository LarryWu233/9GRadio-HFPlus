"""Fetch pinned, checksum-verified build tools into build/toolchains (Windows)."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1] / "build/toolchains"
TOOLS = [
    ("gradle", "https://services.gradle.org/distributions/gradle-8.13-bin.zip",
     "sha256", "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"),
    ("ndk", "https://dl.google.com/android/repository/android-ndk-r28c-windows.zip",
     "sha1", "086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d"),
    ("cmake", "https://dl.google.com/android/repository/cmake-3.22.1-windows.zip",
     "sha1", "292778f32a7d5183e1c49c7897b870653f2d2c1b"),
]


def install(tool):
    name, url, algorithm, checksum = tool
    target = ROOT / name
    marker = target / ".verified"
    if marker.exists() and marker.read_text().strip() == checksum:
        print(f"{name}: already verified", flush=True)
        return
    archive = ROOT / url.rsplit("/", 1)[1]
    if not archive.exists():
        temporary = archive.with_suffix(".download")
        received, last = 0, time.monotonic()
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
            while block := response.read(2 * 1024 * 1024):
                output.write(block)
                received += len(block)
                if time.monotonic() - last > 10:
                    print(f"{name}: downloaded {received // 1048576} MiB", flush=True)
                    last = time.monotonic()
        temporary.replace(archive)
    digest = hashlib.new(algorithm)
    with archive.open("rb") as source:
        while block := source.read(4 * 1024 * 1024):
            digest.update(block)
    if digest.hexdigest() != checksum:
        raise RuntimeError(f"{name}: archive checksum mismatch; remove the archive before retrying")
    print(f"{name}: checksum verified, extracting", flush=True)
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            path = (target / entry.filename).resolve()
            if not path.is_relative_to(target.resolve()):
                raise RuntimeError("Unsafe archive path")
        source.extractall(target)
    marker.write_text(checksum + "\n")
    print(f"{name}: ready at {target}", flush=True)


if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(install, TOOLS))
