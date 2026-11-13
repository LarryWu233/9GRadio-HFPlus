"""Summarize two private receiver observations without exporting raw device diagnostics."""
import argparse
import json
import re
from pathlib import Path
from statistics import median


def slope(points):
    if len(points) < 2:
        return None
    mx = sum(x for x, _ in points) / len(points)
    my = sum(y for _, y in points) / len(points)
    denominator = sum((x - mx) ** 2 for x, _ in points)
    return sum((x - mx) * (y - my) for x, y in points) / denominator if denominator else None


def summarize(path, include_failed=False):
    data = path.read_bytes()
    text = data.decode("utf-16") if data.startswith((b"\xff\xfe", b"\xfe\xff")) else data.decode("utf-8-sig")
    passed = "OK (1 test)" in text
    if not passed and not include_failed:
        raise ValueError("Observation did not complete successfully")
    structured = next((line.split("=", 1)[1] for line in text.splitlines()
                       if line.startswith("RESOURCE_CLOCK_OBSERVATION=")), None)
    if structured is None:
        raise ValueError("Missing structured observation")
    raw = json.loads(structured)
    version = raw["version"]
    if not re.fullmatch(r"[A-Za-z0-9.+_-]{1,80}", version):
        raise ValueError("Unexpected version identifier")
    samples = raw["samples"]
    rate = raw["sampleRate"]
    if rate != 48000 or len(samples) < 9:
        raise ValueError("Unsupported format or insufficient observation")
    # This observation uses the established 20 ms stereo packet queue at 48 kHz.
    # Decoder mono PCM counts frames too. Removed packets must not appear as latency.
    clock_sampled = raw.get("samplePlaybackClock", True)
    queue_ms = [(s["producedFrames"] - s["headFrames"] - s["drops"] * 960) * 1000 / rate for s in samples] if clock_sampled else []
    times_s = [(s["elapsedNs"] - samples[0]["elapsedNs"]) / 1e9 for s in samples]
    valid = [(s["timestampNanos"], s["timestampFrames"]) for s in samples
             if s["timestampNanos"] > 0 and s["timestampFrames"] >= 0]
    monotonic = all(b[0] > a[0] and b[1] > a[1] for a, b in zip(valid, valid[1:]))
    output_rate = slope([((ns - valid[0][0]) / 1e9, frames - valid[0][1]) for ns, frames in valid]) if valid and monotonic else None
    duration_ms = raw["durationNs"] / 1e6
    queue_slope = slope(list(zip(times_s, queue_ms))) if queue_ms else None
    diagnostics = {}
    for key in ("epoch", "inputMissingBlocks", "inputMissingSamples", "driverMissingSamples", "staleInputBlocks"):
        value = re.search(r"\b" + key + r"=(\d+)", raw.get("endDiagnostics", ""))
        diagnostics[key] = int(value.group(1)) if value else None
    continuous = passed and raw.get("steadyDroppedPackets", 0) == 0 and raw.get("steadyUnderruns", 0) == 0
    continuous = continuous and diagnostics["inputMissingSamples"] in (None, 0)
    return {
        "version": version,
        "inputRate": raw["reportedSourceRate"],
        "testPassed": passed,
        "continuousObservation": continuous,
        "cpuWakeLockHeld": raw.get("cpuWakeLockHeld", False),
        "clockSampled": clock_sampled,
        "steadyDroppedPackets": raw.get("steadyDroppedPackets"),
        "steadyUnderruns": raw.get("steadyUnderruns"),
        "cumulativeInputDiagnostics": diagnostics,
        "durationSeconds": round(duration_ms / 1000, 3),
        "receiverProcessCpuMs": raw["cpuMs"],
        "receiverProcessCpuPercent": round(raw["cpuMs"] / duration_ms * 100, 3),
        "endWindowPssKb": raw["endWindowPssKb"],
        "endWindowAllocatedBytes": raw["endWindowAllocatedBytes"],
        "memorySummaryKb": {key: int(value) for key, value in raw.get("memorySummaryKb", {}).items()
                            if key in ("summary.java-heap", "summary.native-heap", "summary.code", "summary.stack",
                                       "summary.graphics", "summary.private-other", "summary.system")},
        "estimatedQueuedAudioMs": {"min": round(min(queue_ms), 3), "median": round(median(queue_ms), 3), "max": round(max(queue_ms), 3)} if queue_ms and continuous else None,
        "queueTrendMsPerMinute": round(queue_slope * 60, 3) if queue_slope is not None and continuous else None,
        "platformOutputClockPpm": round((output_rate / rate - 1) * 1e6, 3) if output_rate and continuous else None,
        "platformTimestampSamples": len(valid),
        "platformTimestampsMonotonic": monotonic,
        "warmupDroppedPackets": raw["warmDrops"],
        "warmupUnderruns": raw["warmUnderruns"],
        "scope": "Headless RTL WFM; one bounded observation. Memory is one end-window snapshot outside continuity/CPU timing, not a growth/peak measurement. Queue estimate excludes unknown device latency. Platform timestamp slope is not ADC clock calibration or injected-drift acceptance.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("observations", type=Path, nargs="+")
    parser.add_argument("--include-failed", action="store_true", help="Include failed diagnostics; failed continuity never qualifies for a resource comparison")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(Path.cwd().resolve()):
        parser.error("Output must stay within the workspace")
    observations = [summarize(path, args.include_failed) for path in args.observations]
    comparison = None
    if len(observations) == 2:
        baseline, preview = observations
        comparable = all(o["continuousObservation"] and o["cpuWakeLockHeld"] for o in observations)
        comparable = comparable and abs(preview["durationSeconds"] / baseline["durationSeconds"] - 1) < .1
        comparable = comparable and preview["inputRate"] == baseline["inputRate"] and preview["clockSampled"] == baseline["clockSampled"]
        if comparable:
            comparison = {key: round((preview[key] / baseline[key] - 1) * 100, 3)
                          for key in ("receiverProcessCpuPercent", "endWindowPssKb")}
    result = {"observations": observations, "previewChangePercent": comparison,
              "comparisonStatus": "Not qualified: requires two continuous matched observations" if comparison is None else "Representative pair only",
              "limitation": "One pair is not a repeated full-UI/all-mode benchmark. No device identifiers, endpoints, local paths or reception content are exported."}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"observations": len(observations), "passed": sum(o["testPassed"] for o in observations),
                      "comparisonStatus": result["comparisonStatus"]}))


if __name__ == "__main__":
    main()
