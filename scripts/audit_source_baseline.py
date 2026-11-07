"""Read-only source/APK comparison. No device installation or network access.

Python 3.10+, standard library only. The JSON contains structural evidence,
not a claim that a named APK class has been functionally tested. Exit 2 with
--fail-on-gap means the source prerequisite failed; exit 1 is an audit error.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile
import zlib


PACKAGE = "com.radiosport.ninegradio"
JAVA_ROOT = Path("app/src/main/java/com/radiosport/ninegradio")
# Missing files are checked alongside actual DEX class definitions, not strings
# that could merely be references to unused types.
PROBES = [
    ("File IQ playback", "source.FileIqSource", "source/FileIqSource.kt"),
    ("Native ACARS", "dsp.AcarsNative", "dsp/AcarsNative.kt"),
    ("Multi-channel ACARS", "dsp.AcarsMultiChannelDecoder", "dsp/AcarsMultiChannelDecoder.kt"),
    ("AIS", "dsp.AisDecoder", "dsp/AisDecoder.kt"),
    ("Native RDS", "dsp.RedseaProcess", "dsp/RedseaProcess.kt"),
    ("readsb ADS-B", "dsp.ReadsbProcess", "dsp/ReadsbProcess.kt"),
    ("9G remote control", "remote.RigControlManager", "remote/RigControlManager.kt"),
    ("MLAT", "mlat.MlatManager", "mlat/MlatManager.kt"),
    ("Satellite tracking", "data.SatTracker", "data/SatTracker.kt"),
    ("SSTV", "dsp.SstvNative", "dsp/SstvNative.kt"),
    ("Native FFT", "dsp.NativeFft", "dsp/NativeFft.kt"),
    ("GPU acceleration", "dsp.GpuAccel", "dsp/GpuAccel.kt"),
    ("Kal calibration", "kal.KalCalibrator", "kal/KalCalibrator.kt"),
    ("NR profiles", "dsp.AudioDsp$NoiseReducer$NoiseProfile", "dsp/AudioDsp.kt"),
]


def command(args, cwd=None):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=45)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]}\n{result.stderr}")
    return result.stdout.strip()


def dex_classes(data):
    """Inventory standard DEX class_defs; verify file size, SHA-1 and Adler32."""
    if not re.fullmatch(rb"dex\n0[0-9]{2}\x00", data[:8]):
        raise ValueError("Unsupported DEX format")
    if len(data) < 112 or struct.unpack_from("<I", data, 32)[0] != len(data):
        raise ValueError("Invalid DEX size")
    if struct.unpack_from("<I", data, 40)[0] != 0x12345678:
        raise ValueError("Unsupported DEX byte order")
    if hashlib.sha1(data[32:]).digest() != data[12:32]:
        raise ValueError("DEX signature mismatch")
    if zlib.adler32(data[12:]) & 0xFFFFFFFF != struct.unpack_from("<I", data, 8)[0]:
        raise ValueError("DEX checksum mismatch")
    string_count, string_offset = struct.unpack_from("<II", data, 56)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from("<I", data, string_offset + 4 * index)[0]
        for _ in range(5):  # string_data_item starts with a ULEB128 UTF-16 size
            value = data[offset]
            offset += 1
            if not value & 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        # Class descriptors used here are ASCII; MUTF-8 replacement in other
        # strings does not change their indexes or descriptor comparisons.
        strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", "replace"))
    type_count, type_offset = struct.unpack_from("<II", data, 64)
    types = [strings[struct.unpack_from("<I", data, type_offset + 4 * i)[0]]
             for i in range(type_count)]
    class_count, class_offset = struct.unpack_from("<II", data, 96)
    return {types[struct.unpack_from("<I", data, class_offset + 32 * i)[0]][1:-1].replace("/", ".")
            for i in range(class_count)}


def declared_source_types(root):
    """Conservative declaration lookup; generated bindings/R classes are excluded later."""
    declarations = set()
    for path in (root / JAVA_ROOT).rglob("*.kt"):
        code = path.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([\w.]+)", code, re.M)
        if package:
            for name in re.findall(r"\b(?:class|object|interface)\s+([A-Za-z_]\w*)", code):
                declarations.add(f"{package[1]}.{name}")
    return declarations


def inspect_device(adb, serial):
    base = [str(adb), "-s", serial, "shell"]
    return {
        "model": command(base + ["getprop", "ro.product.model"]),
        "android_release": command(base + ["getprop", "ro.build.version.release"]),
        "api_level": command(base + ["getprop", "ro.build.version.sdk"]),
        "page_size": command(base + ["getconf", "PAGE_SIZE"]),
        "matching_packages": command(base + ["pm", "list", "packages", PACKAGE]).splitlines(),
        "action": "Read-only ADB properties and package listing; no app installed or modified",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--release-ref", default="625424bbb0cae43cfbce657607942177cd501178")
    parser.add_argument("--legacy-ref", default="508392f")
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--serial")
    parser.add_argument("--fail-on-gap", action="store_true")
    args = parser.parse_args()
    if bool(args.adb) != bool(args.serial):
        parser.error("--adb and --serial must be supplied together")
    root, apk = args.root.resolve(), args.apk.resolve()
    classes, dex_entries = set(), []
    with zipfile.ZipFile(apk) as archive:
        invalid = archive.testzip()
        if invalid:
            raise ValueError(f"Invalid ZIP entry: {invalid}")
        for name in sorted(archive.namelist()):
            if re.fullmatch(r"classes\d*\.dex", name):
                found = dex_classes(archive.read(name))
                classes.update(found)
                dex_entries.append({"name": name, "declared_classes": len(found)})
        native_entries = sorted(n for n in archive.namelist()
                                if re.fullmatch(r"lib/[^/]+/[^/]+\.so", n))
    if not dex_entries:
        raise ValueError("APK contains no supported DEX entries")
    badging = command([str(args.aapt.resolve()), "dump", "badging", str(apk)])
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package[1] != PACKAGE:
        raise ValueError("Unexpected APK package")
    gradle = (root / "app/build.gradle").read_text(encoding="utf-8")
    source_version = re.search(r'versionName\s+"([^"]+)"', gradle)[1]
    source_code = int(re.search(r"versionCode\s+(\d+)", gradle)[1])
    source_types = declared_source_types(root)
    features = []
    for feature, name, relative in PROBES:
        qualified = f"{PACKAGE}.{name}"
        path = root / JAVA_ROOT / relative
        # The profile is nested in an otherwise present but outdated file.
        source_present = qualified in source_types
        if "$" in name:
            source_present = path.is_file() and bool(re.search(
                r"\b(?:class|object|interface)\s+NoiseProfile\b",
                path.read_text(encoding="utf-8")))
        apk_present = qualified in classes
        features.append({"feature": feature, "apk_class": qualified,
                         "apk_class_defined": apk_present,
                         "expected_source": (JAVA_ROOT / relative).as_posix(),
                         "expected_file_present": path.is_file(),
                         "source_declaration_found": source_present,
                         "gap": apk_present and not source_present})
    cmake = (root / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
    shared_targets = sorted(set(re.findall(r"add_library\(\s*([\w-]+)\s+(?:SHARED|MODULE)\b", cmake)))
    library_names = sorted({Path(n).name for n in native_entries})
    missing_targets = sorted(set(library_names) - {f"lib{n}.so" for n in shared_targets}
                             - {"libc++_shared.so"})
    git = lambda *items: command(["git", *items], cwd=root)
    tree_ids = {"head": git("rev-parse", "HEAD:app"),
                "release": git("rev-parse", f"{args.release_ref}:app"),
                "legacy": git("rev-parse", f"{args.legacy_ref}:app")}
    app_classes = sorted(c for c in classes if c.startswith(PACKAGE + "."))
    unmatched = sorted(c for c in app_classes if "$" not in c and ".databinding." not in c
                       and not c.endswith("Kt") and not c.rsplit(".", 1)[-1].startswith("R")
                       and len(c.rsplit(".", 1)[-1]) > 1 and c not in source_types)
    gaps = [f["feature"] for f in features if f["gap"]]
    version_gap = (source_version, source_code) != (package[3], int(package[2]))
    blocked = bool(gaps or version_gap or missing_targets)
    report = {
        "source_gate": "BLOCKED" if blocked else "MANUAL_REVIEW_REQUIRED",
        "scope": "Structural source/APK audit, not runtime feature validation",
        "source": {"commit": git("rev-parse", "HEAD"),
                   "version_name": source_version, "version_code": source_code,
                   "last_app_change": git("log", "-1", "--format=%H %aI %s", "--", "app"),
                   "app_tree_ids": tree_ids,
                   "same_app_tree_as_legacy": len(set(tree_ids.values())) == 1,
                   "kotlin_file_count": len(list((root / JAVA_ROOT).rglob("*.kt"))),
                   "wrapper_files_missing": [p for p in ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar"]
                                             if not (root / p).is_file()]},
        "apk": {"filename": apk.name, "size_bytes": apk.stat().st_size,
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "package": package[1], "version_code": int(package[2]), "version_name": package[3],
                "dex_entries": dex_entries, "app_declared_class_count": len(app_classes),
                "native_library_names": library_names, "native_entries": native_entries},
        "feature_probes": features, "confirmed_feature_gaps": gaps,
        "source_shared_targets": shared_targets,
        "apk_native_libraries_without_shared_target_in_main_cmake": missing_targets,
        "app_declared_classes": app_classes,
        "unmatched_non_generated_top_level_classes": unmatched,
        "limitations": ["Class presence is structural evidence, not proof of successful decoding.",
                        "Unmatched declarations are candidate gaps; Kotlin generated and obfuscated types may appear.",
                        "Feature probes are representative, not an exhaustive parity certification.",
                        "Absence of detected gaps does not certify source completeness."],
    }
    if args.adb:
        report["device"] = inspect_device(args.adb.resolve(), args.serial)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"source_gate": report["source_gate"], "feature_gaps": len(gaps),
                      "native_target_gaps": len(missing_targets), "report": str(args.output)}, ensure_ascii=False))
    return 2 if args.fail_on_gap and blocked else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, IndexError, struct.error, subprocess.SubprocessError, zipfile.BadZipFile, RuntimeError) as error:
        print(f"Audit failed: {error}", file=sys.stderr)
        sys.exit(1)
