"""Builds the app's index of Maritime Safety Queensland tide stations.

    python tools/msq_tide_index.py app/src/main/assets/tides/msq_stations.json [interval cache folder]

Lists MSQ's "predicted high/low data" datasets on data.qld.gov.au (CKAN API),
keeps each one's yearly CSV links, and reads the first couple of KB of one CSV
per station (an HTTP range request) for its number, position, time zone and
datum. The app downloads the CSVs itself, straight from data.qld.gov.au, and
looks up years not listed here with CKAN's package_show. Run it once a year
(MSQ adds the next year's predictions around September) or when stations change.

Each station's mean sea level above LAT ("mslAboveLatM") is the mean of one
whole calendar year of MSQ's 10-minute predictions for it (the station's
"predicted interval data" dataset, about 1.8 MB a year): the level the
predictions swing about. MSQ doesn't publish it with the data, and the mean of
a curve drawn through the highs and lows alone is out by up to about 9 cm.

The same year shows how well half a cosine between each high and low follows
the 10-minute predictions ("halfCosineP95M", "halfCosineMaxM"). Where it is
poor (95th percentile over 10 cm, or worst over 25 cm: the Gulf, Torres Strait
and much of the reef coast) the station gets "useInterval" and its 10-minute
links ("intervalDataset", "intervalYears"): the app then downloads those too
and draws the tide from them, keeping the high/low file for the published
highs and lows. The files are kept in the cache folder, if given, between runs.

Data: Maritime Safety Queensland, (c) State of Queensland (Department of
Transport and Main Roads), Creative Commons Attribution 4.0.
"""
import datetime
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.request

API = "https://www.data.qld.gov.au/api/3/action/"
AGENT = {"User-Agent": "SeaBathy tide index builder"}

# Friendlier names where the dataset title doesn't say where the gauge is.
NAMES = {
    "100248": "Golding Channel, Gladstone",
    "046047A": "Pinkenba (Gateway Bridge)",
}

# Gauges up rivers and creeks: their tides lag and shrink compared with the open
# water outside, so they are used only close by. Everything else: DEFAULT_REACH_KM.
DEFAULT_REACH_KM = 15
RIVER_REACH_KM = 8
RIVERS = {"046006I", "046047A", "008006A", "042023A", "071004A", "100029"}

# Waters with tides of their own (a long channel or passage behind an island)
# within reach of a gauge outside: no gauge's predictions are used in them.
SEPARATE = [
    {"name": "Pumicestone Passage", "south": -27.075, "west": 153.03, "north": -26.795, "east": 153.155},
    {"name": "Hinchinbrook Channel", "south": -18.53, "west": 146.05, "north": -18.29, "east": 146.27},
    {"name": "Great Sandy Strait narrows", "south": -25.83, "west": 152.85, "north": -25.50, "east": 153.10},
    {"name": "The Narrows, Curtis Island", "south": -23.74, "west": 150.95, "north": -23.45, "east": 151.12},
]


def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=60) as r:
        return json.load(r)


def head(url):
    req = urllib.request.Request(url, headers=dict(AGENT, Range="bytes=0-2500"))
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("latin-1")


def degrees(text):
    d, m = text.split()
    sign = -1 if d.startswith("-") else 1
    return round(int(d) + sign * int(m) / 60.0, 4)


def header(text):
    fields = {}
    for line in text.splitlines():
        if "=," in line:
            k, v = line.split("=,", 1)
            fields[k.strip()] = v.strip().rstrip(",").strip()
    return fields


# Half-cosine curves worse than this use the 10-minute predictions instead.
INTERVAL_P95_M = 0.10
INTERVAL_MAX_M = 0.25
INTERVAL_RE = r"_(\d{4})_10mins?(?:_[a-z]+)?(?:\.csv)+$"


def readings(text, year, inds):
    """(time, height) rows of [year] with Ind in [inds]."""
    out = []
    started = False
    for line in text.splitlines():
        if not started:
            started = "=," not in line and line.startswith("Date") and "Reading" in line
            continue
        p = [x.strip() for x in line.split(",")]
        if len(p) < 4 or p[2] not in inds or not p[0].endswith(str(year)):
            continue
        try:
            h = float(p[3])
        except ValueError:
            continue
        if -2.0 <= h <= 17.0:
            out.append((datetime.datetime.strptime(p[0] + " " + p[1], "%d/%m/%Y %H:%M"), h))
    return out


def cosine_errors(interval, hilo):
    """How far half a cosine between each high and low is from each 10-minute height: (95th percentile, worst)."""
    errors = []
    j = 0
    for t, h in interval:
        while j + 1 < len(hilo) and hilo[j + 1][0] <= t:
            j += 1
        if j + 1 >= len(hilo) or t < hilo[0][0]:
            continue
        (ta, ha), (tb, hb) = hilo[j], hilo[j + 1]
        u = (t - ta).total_seconds() / (tb - ta).total_seconds()
        errors.append(abs((ha + hb) / 2 + (ha - hb) / 2 * math.cos(math.pi * u) - h))
    errors.sort()
    return errors[int(len(errors) * 0.95)], errors[-1]


def fetch_cached(url, path):
    if path and os.path.exists(path):
        return open(path, encoding="latin-1").read()
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=300) as r:
        raw = r.read()
    if path:
        open(path, "wb").write(raw)
    return raw.decode("latin-1")


def interval_years(package, year_from):
    years = {}
    for r in (package or {}).get("resources", []):
        m = re.search(INTERVAL_RE, r["url"], re.I)
        if m and r["url"].startswith("https://www.data.qld.gov.au/") and int(m.group(1)) >= year_from:
            years[m.group(1)] = r["url"]
    return dict(sorted(years.items()))


def mean_sea_level(package, number, cache, year_limit):
    """(Mean of a whole calendar year of 10-minute predictions in metres above LAT, the year, its (time, height) rows); Nones without one."""
    if package is None:
        return None, None, None
    years = {}
    for r in package["resources"]:
        m = re.search(INTERVAL_RE, r["url"], re.I)
        if m and r["url"].startswith("https://www.data.qld.gov.au/") and int(m.group(1)) <= year_limit:
            years[int(m.group(1))] = r["url"]
    for year in sorted(years, reverse=True):
        path = os.path.join(cache, f"{number}_{year}_10min.csv") if cache else None
        try:
            text = fetch_cached(years[year], path)
        except urllib.error.HTTPError as error:
            print("no 10-minute file yet:", number, year, error.code)
            continue
        fields = header(text)
        refer = text.split("Readings in this file are referred to", 1)[-1][:80]
        if "LOWEST ASTRONOMICAL TIDE" not in refer or fields.get("Data Class") != "INTERVAL":
            print("10-minute file not to LAT:", number, year)
            continue
        rows = readings(text, year, {"9", "1", "-1", "0"})
        days = (datetime.date(year + 1, 1, 1) - datetime.date(year, 1, 1)).days
        if len(rows) < days * 144 * 0.99:
            print("10-minute file incomplete:", number, year, len(rows))
            continue
        return round(sum(h for _, h in rows) / len(rows), 3), year, rows
    return None, None, None


def main(out, cache=None):
    if cache:
        os.makedirs(cache, exist_ok=True)
    packages = {}
    for query in ("fq=tags:msq", "q=%22tide+gauge%22+predicted"):
        for p in get_json(API + "package_search?rows=1000&" + query)["result"]["results"]:
            packages.setdefault(p["name"], p)
    this_year = datetime.date.today().year
    stations = []
    for name, p in sorted(packages.items()):
        if not name.endswith("-tide-gauge-predicted-high-low-data") or p.get("license_id") != "CC-BY-4.0":
            continue
        years = {}
        for r in p["resources"]:
            m = re.search(r"_(\d{4})_hilo(?:_[a-z]+)?\.csv$", r["url"], re.I)
            if m:
                years[m.group(1)] = r["url"]
        if not years:
            continue
        # A year can be listed before its file is up (it answers 404 until then).
        text = None
        for year in sorted(years, reverse=True):
            try:
                text = head(years[year])
                break
            except urllib.error.HTTPError as error:
                print("no file yet:", name, year, error.code)
        if text is None:
            continue
        fields = header(text)
        if "LOWEST ASTRONOMICAL TIDE" not in text.split("Readings in this file are referred to", 1)[-1][:80]:
            print("skipped, not to LAT:", name)
            continue
        if fields.get("Time Zone") != "1000E":
            print("skipped, time zone", fields.get("Time Zone"), name)
            continue
        number = fields["Tidal Station Number"]
        title = re.split(r"\s*tide gauge|—", p["title"])[0].strip()
        interval = packages.get(name.replace("-predicted-high-low-data", "-predicted-interval-data"))
        msl, msl_year, interval_rows = mean_sea_level(interval, number, cache, this_year)
        station = {
            "id": number,
            "name": NAMES.get(number, title),
            "lat": degrees(fields["Latitude  Degrees Minutes"]),
            "lng": degrees(fields["Longitude Degrees Minutes"]),
            "reachKm": RIVER_REACH_KM if number in RIVERS else DEFAULT_REACH_KM,
            "dataset": name,
            "years": dict(sorted(years.items())),
        }
        if msl is not None:
            station["mslAboveLatM"] = msl
            station["mslFrom"] = f"mean of the {msl_year} 10-minute predictions"
            hilo_url = years.get(str(msl_year))
            if hilo_url:
                path = os.path.join(cache, f"{number}_{msl_year}_hilo.csv") if cache else None
                hilo_rows = readings(fetch_cached(hilo_url, path), msl_year, {"1", "-1"})
                p95, worst = cosine_errors(interval_rows, hilo_rows)
                station["halfCosineP95M"] = round(p95, 3)
                station["halfCosineMaxM"] = round(worst, 3)
                if p95 > INTERVAL_P95_M or worst > INTERVAL_MAX_M:
                    station["useInterval"] = True
                    station["intervalDataset"] = interval["name"]
                    station["intervalYears"] = interval_years(interval, msl_year)
        else:
            print("no mean sea level:", name)
        stations.append(station)
        time.sleep(0.2)
    stations.sort(key=lambda s: (-s["lat"], s["lng"]))
    index = {
        "source": "Maritime Safety Queensland predicted high/low tides, data.qld.gov.au",
        "licence": "CC BY 4.0, (c) State of Queensland (Department of Transport and Main Roads)",
        "built": datetime.date.today().isoformat(),
        "separate": SEPARATE,
        "stations": stations,
    }
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(index, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(len(stations), "stations written to", out, "-", sum(1 for s in stations if "mslAboveLatM" in s), "with mean sea level,",
          sum(1 for s in stations if s.get("useInterval")), "using 10-minute predictions")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
