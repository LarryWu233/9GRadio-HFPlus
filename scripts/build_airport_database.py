"""Build an offline receiving-frequency index from OurAirports public-domain CSVs."""
import csv
import hashlib
import io
import json
from pathlib import Path
from datetime import datetime, timezone
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://davidmegginson.github.io/ourairports-data/"
metadata = {"source": "https://ourairports.com/data/", "license": "Public Domain",
            "retrieved_utc": datetime.now(timezone.utc).isoformat(), "files": {}}

def fetch(name):
    request = urllib.request.Request(BASE + name, headers={"User-Agent": "9GRadio-HFPlus-source-build"})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read()
    metadata["files"][name] = {"url": BASE + name, "sha256": hashlib.sha256(data).hexdigest()}
    return csv.DictReader(io.StringIO(data.decode("utf-8-sig")))

airports = {}
for row in fetch("airports.csv"):
    if row["type"] not in ("large_airport", "medium_airport", "small_airport"):
        continue
    try:
        airports[row["ident"]] = {"ident": row["ident"], "name": row["name"], "country": row["iso_country"],
            "lat": float(row["latitude_deg"]), "lon": float(row["longitude_deg"]), "channels": []}
    except ValueError:
        continue
for row in fetch("airport-frequencies.csv"):
    airport = airports.get(row["airport_ident"])
    if airport is None:
        continue
    try:
        hz = round(float(row["frequency_mhz"]) * 1_000_000)
    except ValueError:
        continue
    if not 118_000_000 <= hz <= 136_975_000:
        continue
    channel = {"type": row["type"], "description": row["description"], "hz": hz}
    if channel not in airport["channels"]:
        airport["channels"].append(channel)
result = [airport for airport in airports.values() if airport["channels"]]
metadata["airports"] = len(result)
metadata["channels"] = sum(len(a["channels"]) for a in result)
destination = ROOT / "app/src/main/assets/receiving"
destination.mkdir(parents=True, exist_ok=True)
(destination / "airports.json").write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
(destination / "sources.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
print(json.dumps({"airports": len(result), "channels": metadata["channels"],
                  "china_airports": sum(a["country"] == "CN" for a in result)}))
