"""Builds the Australian fishing layers bundled in app/src/main/assets/fishing.

    python -I -B tools/fishing_layers/build_fishing_layers.py --raw <download cache folder>

Needs Python 3.10+ and shapely 2. Downloads are kept in the --raw folder (outside the repo), so
a rebuild only fetches what is missing; delete it to fetch everything again.

Writes:
  wrecks.geojson    shipwrecks: the national database (AUCHD, via IMAS Seamap), the WA Museum's
                    and SA DEW's own records, merged and de-duplicated
  sites.geojson     artificial reefs (Qld, Gold Coast) and Queensland Shark Control Program gear
  closures.geojson  fishing closures and management areas: GBRMPA special management areas and
                    no-anchoring areas, Queensland Fish Habitat Areas, WA DPIRD fishing orders

Sources and licences (checked 2026-11-21 at each primary source):
  AUCHD wrecks          data.gov.au "australian-national-shipwrecks", DCCEEW, CC BY 3.0 AU
  WA Museum wrecks      catalogue.data.wa.gov.au "shipwrecks" (WAM-002), CC BY 4.0
  SA shipwrecks         data.sa.gov.au "shipwrecks", SA DEW, CC BY 4.0
  Qld artificial reefs  data.qld.gov.au "artificial-reef-sites-queensland", CC BY 4.0
  Gold Coast reef       data.gov.au "artific", City of Gold Coast, CC BY 3.0 AU
  Qld shark gear        publications.qld.gov.au "queensland-shark-control-program", CC BY 4.0
  GBRMPA areas          ArcGIS Online, owner gbrmpa_open_data, CC BY 4.0
  Qld Fish Habitat Areas data.qld.gov.au "fish-habitat-areas-queensland", CC BY 4.0
  WA DPIRD orders       catalogue.data.wa.gov.au DPIRD-061, CC BY 4.0
Port Phillip Bay's artificial reefs (DataVic) are not included: the DataVic record no longer
resolves and Victoria's open-data WFS timed out when this was built.
"""
import argparse
import io
import json
import math
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

UA = {"User-Agent": "SeaBathy data build"}
# What a source shows for a character it couldn't encode (a dash, mostly: "18?008").
UNREADABLE = chr(0xFFFD)
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(REPO, "app", "src", "main", "assets", "fishing")

AUCHD = ("https://geoserver.imas.utas.edu.au/geoserver/seamap/wfs?service=WFS&version=2.0.0&request=GetFeature"
         "&typeNames=seamap:AUCHD_wrecks_ships_singlept&outputFormat=application/json")
WA_WRECKS = "https://public-services.slip.wa.gov.au/public/rest/services/SLIP_Public_Services/People_and_Society/MapServer/0"
SA_WRECKS = "https://www.waterconnect.sa.gov.au/Content/Downloads/DEW/TOPO_Shipwrecks_geojson.zip"
QLD_PARKS = "https://spatial-gis.information.qld.gov.au/arcgis/rest/services/Environment/ParksMarineProtectedAreas/MapServer"
GOLD_COAST = ("https://data.gov.au/geoserver/artific/wfs?service=WFS&version=1.0.0&request=GetFeature"
              "&typeName=ckan_a916e5f4_5d73_43c4_bff3_f03312fe3f00&outputFormat=json&srsName=EPSG:4326")
SHARK_XLSX = ("https://www.publications.qld.gov.au/dataset/e20e6bcd-c076-42a2-9e17-7d549b02254e/resource/"
              "d3b7093a-1781-4d1e-ae9c-5c538be8d1ab/download/shark-gear-locations.xlsx")
GBRMPA = "https://services-ap1.arcgis.com/8gXWSCxaJlFIfiTr/arcgis/rest/services"
WA_GUIDE = "https://public-services.slip.wa.gov.au/public/rest/services/SLIP_Public_Services/DPIRD_Fisheries_Guide/MapServer"


# Fetching -------------------------------------------------------------------------------------

def fetch(raw, name, url, binary=False):
    path = os.path.join(raw, name)
    if not os.path.exists(path):
        data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300).read()
        with open(path, "wb") as f:
            f.write(data)
    with open(path, "rb") as f:
        data = f.read()
    return data if binary else json.loads(data)


def arcgis(raw, name, layer_url, where="1=1", offset_deg=None):
    """Every feature of an ArcGIS layer as GeoJSON (WGS84), paged."""
    path = os.path.join(raw, name)
    if not os.path.exists(path):
        feats, start = [], 0
        while True:
            params = {"where": where, "outFields": "*", "outSR": "4326", "returnGeometry": "true",
                      "geometryPrecision": "6", "resultOffset": start, "resultRecordCount": 1000, "f": "geojson"}
            if offset_deg:
                params["maxAllowableOffset"] = offset_deg
            url = layer_url + "/query?" + urllib.parse.urlencode(params)
            page = json.loads(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300).read())
            feats += page.get("features", [])
            if len(page.get("features", [])) < 1000:
                break
            start += 1000
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"type": "FeatureCollection", "features": feats}, f)
    with open(path, encoding="utf-8") as f:
        return json.load(f)["features"]


# Shipwrecks -----------------------------------------------------------------------------------

AU_STATES = {"Western Australia", "Queensland", "Tasmania", "South Australia", "Victoria", "New South Wales",
             "Northern Territory", "Norfolk Island", "Australian Government", "Australian Capital Territory"}
NAME_PREFIXES = ("hmas ", "hms ", "ss ", "ps ", "sv ", "mv ", "fv ", "ms ", "hmcs ", "usns ", "uss ", "the ")


def norm_name(name):
    """A wreck's name for matching: no accents, case, punctuation, spaces ("Glen Morry" is
    "Glenmorry"), notes in brackets (even one cut off), ship prefixes or "ex ..." suffixes."""
    s = unicodedata.normalize("NFKD", name or "").encode("ascii", "ignore").decode().lower()
    s = re.sub(r"\(.*?\)", " ", s)
    s = re.sub(r"\(.*$", " ", s)
    s = re.sub(r"[^a-z0-9 ]+", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"\bex\b.*$", "", s).strip()
    changed = True
    while changed:
        changed = False
        for p in NAME_PREFIXES:
            if s.startswith(p):
                s, changed = s[len(p):], True
    return s.replace(" ", "")


def decimals(v):
    """Decimal places a coordinate was given to (trailing zeros don't count)."""
    s = repr(float(v)) if not isinstance(v, int) else str(v)
    if "e" in s or "." not in s:
        return 0
    return len(s.split(".")[1].rstrip("0"))


def year_of(text):
    """The year in a loss date such as "1942/03/03", "6/1848" or "11/08/1848"; None if there isn't one."""
    if text is None:
        return None
    if isinstance(text, (int, float)):
        return int(text) if 1500 <= int(text) <= 2100 else None
    # A year standing on its own: "31821" (a typing slip in one record) is not 1821.
    m = re.findall(r"(?<!\d)(1[5-9]\d\d|20\d\d)(?!\d)", str(text))
    return int(m[0]) if m else None


def tidy_text(text, limit=90):
    if text is None:
        return None
    s = re.sub(r"\s+", " ", str(text).replace("\ufffd", "")).strip(" ,.;")
    if not s or s.lower() in ("unknown", "none", "null"):
        return None
    if len(s) > limit:
        s = s[:limit].rsplit(" ", 1)[0].rstrip(" ,.;") + "..."
    return s


def sentence_case(text):
    t = tidy_text(text, 40)
    return t[0].upper() + t[1:].lower() if t else None


def wa_position(text):
    """The WA Museum's note on where a position came from, as a short code."""
    t = (text or "").lower()
    if not t.strip() or t.strip() == "unknown":
        return None
    if "check" in t or "suspect" in t:
        return "chk"
    if "aerial" in t or "skyview" in t or "dola" in t:
        return "ae"
    if "gps" in t or "gsp" in t or "sidescan" in t or "wgs84" in t:
        return "g"
    if "chart" in t or "map" in t or "harbour master" in t:
        return "ch"
    return None


def distance_km(a, b):
    lat1, lon1, lat2, lon2 = map(math.radians, (a[1], a[0], b[1], b[0]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 6371.0 * 2 * math.asin(math.sqrt(h))


# How far apart two records of one wreck can be: the national record is often only a rough position.
MATCH_KM = {"p1": 25.0, "p2": 12.0, "p3": 8.0}


def years_agree(a, b):
    """Two loss years that can be one wreck's: either unknown, a year apart at most, or the national
    record's year a round decade (many of its years are only to the decade) within ten years."""
    if not a or not b:
        return True
    return abs(a - b) <= 1 or (b % 10 == 0 and abs(a - b) < 10)


# Two records this close, with the same name, are one wreck whatever years they give.
SAME_SPOT_KM = 0.5


def same_wreck(state, national):
    """Whether a state record and a national record are one wreck: the same name, and a position
    close enough for how rough the national one is with no clash of years, or the very same spot.
    Unnamed wrecks only match at the very same spot."""
    if state["key"] != national["key"]:
        return False
    d = distance_km(state["pos"], national["pos"])
    if not state["key"]:
        return d <= 0.2 or (d <= 1.0 and state.get("y") is not None and state.get("y") == national.get("y"))
    if d <= SAME_SPOT_KM:
        return True
    return years_agree(state.get("y"), national.get("y")) and d <= MATCH_KM.get(national["a"], 8.0)


def auchd_records(features):
    out = []
    for f in features:
        p, g = f["properties"], f.get("geometry")
        if not g or p.get("State") not in AU_STATES:
            continue
        lon, lat = g["coordinates"][:2]
        if not (95 <= lon <= 170 and -56 <= lat <= -8):
            continue  # Australian warships lost abroad (Turkey, Libya, Sri Lanka...): not Australian waters
        prec = max(decimals(lon), decimals(lat))
        if prec == 0:
            continue  # whole degrees only: "Gulf of Carpentaria", far too rough to plot
        out.append({"src": "A", "n": tidy_text(p.get("NAME"), 60), "y": year_of(p.get("Year")),
                    "t": sentence_case(p.get("Type")), "l": tidy_text(p.get("Location"), 60),
                    "a": "p%d" % min(prec, 3), "pos": (lon, lat), "state": p.get("State")})
    return out


def wa_records(features):
    out = []
    for f in features:
        p, g = f["properties"], f.get("geometry")
        if not g:
            continue
        lon, lat = g["coordinates"][:2]
        kind = p.get("type_of_si") or ""
        t = "Aircraft" if kind == "Aircraft" else sentence_case(p.get("rig")) or sentence_case(kind)
        rec = {"src": "W", "n": tidy_text(p.get("name"), 60), "y": year_of(p.get("when_lost")), "t": t,
               "l": tidy_text(p.get("where_lost"), 60), "a": wa_position(p.get("position_i")), "pos": (lon, lat)}
        lo, hi = p.get("min_depth") or 0, p.get("max_depth") or 0
        if hi and hi > 0:
            rec["d0"], rec["d1"] = round(lo or hi, 1), round(hi, 1)
        if kind == "Aircraft":
            rec["air"] = 1
        out.append(rec)
    return out


def sa_records(features):
    out = []
    for f in features:
        p, g = f["properties"], f.get("geometry")
        if not g:
            continue
        lon, lat = g["coordinates"][:2]
        t = sentence_case(p.get("SHIPTYPEDE")) or sentence_case(p.get("RIGDESC"))
        if t in ("None", "Unknown"):
            t = None
        out.append({"src": "S", "n": tidy_text(p.get("WRECKNAME"), 60), "y": year_of(p.get("LOSSDATE")), "t": t,
                    "l": tidy_text(p.get("LOSSLOCATI"), 60), "a": "f" if p.get("ISFOUND") == "Y" else "nf",
                    "pos": (lon, lat)})
    return out


def merge_wrecks(national, state_lists):
    """The state records (better positions, depths, found or not) replace the national record of
    the same wreck, which lends its year and vessel type where the state record has none. A
    national record that is a second copy of a wreck a state record has (the national database
    lists some wrecks twice) goes too, as does a national record repeated at the same spot.
    Different wrecks of one name (there are many Ellens) are all kept."""
    for r in national + [r for lst in state_lists for r in lst]:
        r["key"] = norm_name(r["n"])
    merged, used = [], set()
    by_key = {}
    for i, r in enumerate(national):
        by_key.setdefault(r["key"], []).append(i)
    states = [s for lst in state_lists for s in lst]
    for s in states:
        best, best_d = None, None
        for i in by_key.get(s["key"], []):
            if i in used or not same_wreck(s, national[i]):
                continue
            d = distance_km(s["pos"], national[i]["pos"])
            if best is None or d < best_d:
                best, best_d = i, d
        if best is not None:
            used.add(best)
            n = national[best]
            s["y"] = s.get("y") or n.get("y")
            s["t"] = s.get("t") or n.get("t")
            s["l"] = s.get("l") or n.get("l")
            s["also"] = "A"
        merged.append(s)
    # Second copies: a national record left over that is still the same wreck as a state record.
    for s in states:
        if s.get("also"):
            for i in by_key.get(s["key"], []):
                if i not in used and s["key"] and same_wreck(s, national[i]):
                    used.add(i)
    kept, kept_by_key = [], {}
    for i, r in enumerate(national):
        if i in used:
            continue
        # The national database's own repeats: the same name at the same spot, years agreeing.
        if r["key"] and any(distance_km(k["pos"], r["pos"]) <= SAME_SPOT_KM and years_agree(k.get("y"), r.get("y"))
                            for k in kept_by_key.get(r["key"], [])):
            continue
        kept.append(r)
        kept_by_key.setdefault(r["key"], []).append(r)
    merged += kept
    seen, out = set(), []
    for r in merged:
        k = (r["src"], r["key"], round(r["pos"][0], 3), round(r["pos"][1], 3))
        if k in seen:
            continue
        seen.add(k)
        out.append(r)
    return out


# How sure a position is, best first: drawn on top, and faded when only rough.
RANK = {"f": 0, "g": 0, "ae": 1, "ch": 1, "p3": 1, "chk": 2, "p2": 2, None: 2, "p1": 3, "nf": 3}


def wreck_features(records):
    feats = []
    for r in records:
        props = {k: r[k] for k in ("n", "y", "t", "l", "a", "d0", "d1", "air", "also") if r.get(k) is not None}
        if r["src"] != "A":
            props["s"] = r["src"]  # the national database unless it says otherwise
        feats.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [round(r["pos"][0], 5), round(r["pos"][1], 5)]},
                      "properties": props})
    feats.sort(key=lambda f: (RANK.get(f["properties"].get("a"), 2), f["properties"].get("n") or ""))
    return feats


# Artificial reefs and shark gear ----------------------------------------------------------------

def dms(deg, minutes):
    """Degrees and decimal minutes from the spreadsheet ("23", "6.841'") as decimal degrees, or None."""
    try:
        d = float(str(deg).strip())
        m = float(str(minutes).replace("'", "").replace("\u2019", "").strip())
    except (TypeError, ValueError):
        return None
    if not (0 <= m < 60):
        return None
    return d + m / 60.0


XLSX_NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def xlsx_sheets(data):
    """Each sheet of an .xlsx file as rows of cell text (no third-party packages)."""
    z = zipfile.ZipFile(io.BytesIO(data))
    shared = []
    if "xl/sharedStrings.xml" in z.namelist():
        for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", XLSX_NS):
            shared.append("".join(t.text or "" for t in si.iter("{%s}t" % XLSX_NS["m"])))
    names = [s.get("name") for s in ET.fromstring(z.read("xl/workbook.xml")).find("m:sheets", XLSX_NS)]
    sheets = {}
    for i, name in enumerate(names, start=1):
        part = f"xl/worksheets/sheet{i}.xml"
        if part not in z.namelist():
            continue
        rows = []
        for row in ET.fromstring(z.read(part)).iter("{%s}row" % XLSX_NS["m"]):
            cells = {}
            for c in row.findall("m:c", XLSX_NS):
                v = c.find("m:v", XLSX_NS)
                col = 0
                for ch in re.match(r"[A-Z]+", c.get("r")).group(0):
                    col = col * 26 + ord(ch) - 64
                if c.get("t") == "s" and v is not None:
                    cells[col - 1] = shared[int(v.text)]
                elif c.get("t") == "inlineStr":
                    cells[col - 1] = "".join(x.text or "" for x in c.iter("{%s}t" % XLSX_NS["m"]))
                else:
                    cells[col - 1] = v.text if v is not None else None
            if cells:
                rows.append([cells.get(k) for k in range(max(cells) + 1)])
        sheets[name] = rows
    return sheets


AREA_NAMES = {"Nth Stradbroke Island": "North Stradbroke Island", "Woongarra Coast": "Bundaberg",
              "Townsville": "Townsville and Magnetic Island"}


def shark_gear(sheets):
    """Drumlines as points and nets as lines (from end to end), with their beach and area."""
    feats = []
    for sheet, rows in sheets.items():
        if sheet.lower() == "summary":
            continue
        area = AREA_NAMES.get(sheet, sheet)
        beach = None
        started = False
        for row in rows:
            cells = [(c or "").strip() if isinstance(c, str) else c for c in row] + [None] * 12
            if not started:
                started = (cells[0] or "").lower() == "location"
                continue
            if cells[0]:
                beach = re.sub(r"\s+", " ", cells[0]).strip()
            gear = (cells[1] or "").lower()
            if "drum" not in gear and "net" not in gear:
                continue
            lat, lon = dms(cells[3], cells[4]), dms(cells[5], cells[6])
            if lat is None or lon is None:
                continue
            lat = -lat
            number = re.sub(r"(?i)^net\s*", "", str(cells[2] or "").strip())
            if "net" in gear:
                lat2, lon2 = dms(cells[7], cells[8]), dms(cells[9], cells[10])
                props = {"k": "net", "n": "Shark net" + (f" {number}" if number else ""), "b": beach, "ar": area}
                if lat2 is not None and lon2 is not None:
                    feats.append({"type": "Feature", "properties": props, "geometry": {"type": "LineString", "coordinates": [
                        [round(lon, 5), round(lat, 5)], [round(lon2, 5), round(-lat2, 5)]]}})
                else:
                    feats.append({"type": "Feature", "properties": props, "geometry": {"type": "Point", "coordinates": [round(lon, 5), round(lat, 5)]}})
            else:
                feats.append({"type": "Feature", "properties": {"k": "drum", "n": "Drumline" + (f" {number}" if number else ""), "b": beach, "ar": area},
                              "geometry": {"type": "Point", "coordinates": [round(lon, 5), round(lat, 5)]}})
    for f in feats:
        f["properties"] = {k: v for k, v in f["properties"].items() if v}
    return feats


# Geometry ---------------------------------------------------------------------------------------

def tidy_polygon(geometry, tolerance, min_area=0.0):
    from shapely.geometry import shape, mapping, Polygon, MultiPolygon
    from shapely.validation import make_valid
    g = make_valid(shape(geometry).buffer(0)).simplify(tolerance, preserve_topology=True)
    parts = []
    for p in getattr(g, "geoms", [g]):
        polys = list(p.geoms) if p.geom_type == "MultiPolygon" else [p] if p.geom_type == "Polygon" else []
        for q in polys:
            if q.area < min_area:
                continue
            parts.append(Polygon(q.exterior, [r for r in q.interiors if Polygon(r).area >= max(min_area, 1e-9)]))
    if not parts:
        return None, 0.0
    shp = MultiPolygon(parts) if len(parts) > 1 else parts[0]
    return rounded(mapping(shp)), shp


def rounded(geo):
    def r(c):
        return [round(c[0], 5), round(c[1], 5)] if isinstance(c[0], (int, float)) else [r(x) for x in c]
    coords = r(geo["coordinates"])
    # Rings keep 4+ points after rounding, or MapLibre drops the whole file.
    if geo["type"] == "Polygon":
        coords = [ring for ring in coords if len(ring) >= 4]
    else:
        coords = [[ring for ring in poly if len(ring) >= 4] for poly in coords]
        coords = [poly for poly in coords if poly]
    return {"type": geo["type"], "coordinates": [list(map(list, c)) for c in coords]} if coords else None


def km2(shp):
    """Area in square kilometres, roughly (degrees scaled at the shape's latitude)."""
    lat = shp.representative_point().y
    return shp.area * 111.32 * 111.32 * math.cos(math.radians(lat))


def artificial_reefs(qld, gold_coast):
    feats = []
    for f in qld:
        p = f["properties"]
        geo, shp = tidy_polygon(f["geometry"], 0.00002)
        if not geo:
            continue
        ha = p.get("hectares")
        props = {"k": "reef", "n": tidy_text(p.get("reef_name"), 50) or "Artificial reef",
                 "l": tidy_text(p.get("location"), 50), "src": "Q"}
        if ha:
            props["ha"] = round(ha)
        feats.append({"type": "Feature", "properties": dict(props, k="reef_area"), "geometry": geo})
        c = shp.representative_point()
        feats.append({"type": "Feature", "properties": props, "geometry": {"type": "Point", "coordinates": [round(c.x, 5), round(c.y, 5)]}})
    from shapely.geometry import shape
    from shapely.ops import unary_union
    if gold_coast:
        shapes = [shape(f["geometry"]) for f in gold_coast]
        geo, shp = tidy_polygon(unary_union(shapes).__geo_interface__, 0.00002)
        built = year_of((gold_coast[0]["properties"].get("CONSTRUCTI") or "")[:4])
        props = {"k": "reef", "n": "Narrowneck Artificial Reef", "l": "Gold Coast", "src": "G"}
        if built:
            props["yb"] = built
        feats.append({"type": "Feature", "properties": dict(props, k="reef_area"), "geometry": geo})
        c = shp.representative_point()
        feats.append({"type": "Feature", "properties": props, "geometry": {"type": "Point", "coordinates": [round(c.x, 5), round(c.y, 5)]}})
    return feats


# Fishing closures -------------------------------------------------------------------------------

# GBR special management areas that limit recreational fishers, by type. Dugong protection,
# set-mesh-net, dory and Princess Charlotte Bay areas only limit commercial netting and line
# fishing, so they are left out.
GBR_SMA = {
    "Seasonal Closure": ("limits", "Seasonal closure: some or all use is closed at times of the year."),
    "Restricted Access": ("closed", "Restricted access: you need permission to go in."),
    "Public Appreciation": ("limits", "Public appreciation area: special limits on fishing and other uses."),
    "Maritime Cultural Heritage Protection": ("limits", "Protects a historic wreck: limits on fishing, anchoring and diving."),
    "Natural Resources Conservation": ("limits", "Extra protection for marine life: limits on fishing and collecting."),
}


def gbr_name(text):
    s = re.sub(r"^Special Management Areas\s*-\s*", "", text or "")
    return tidy_text(s.replace("\u2011", "-"), 80)


# WA Fisheries Guide (DPIRD-061) layers for recreational fishers that the marine park zones don't
# already show (those take the "Prohibition on (Recreational) Fishing (<park>)" orders). Orders that
# bind commercial fishers only, cover whole coasts (rock lobster 2018, abalone, coral) or inland
# rivers (pointed instruments, Kimberley nets) are left out, as are the south coast's designated
# fishing zones (commercial salmon teams).
WA_LAYERS = {
    112: ("closed", "Reef observation area: no fishing."),
    116: ("limits", "No fishing from a boat here."),
    149: ("limits", "Some recreational fishing activities are banned here."),
    152: ("closed", "No fishing at this wreck site."), 153: ("closed", "No fishing at this wreck site."),
    154: ("closed", "No fishing at these wreck sites."), 155: ("closed", "No fishing at this wreck site."),
    156: ("closed", "No fishing at this wreck site."), 157: ("closed", "No fishing at this wreck site."),
    158: ("closed", "No fishing at this wreck site."), 159: ("closed", "No fishing at this wreck site."),
    131: ("limits", "Seasonal closure for some fishing (pink snapper spawning)."),
    196: ("limits", "Limits on fishing for crabs."),
    198: ("limits", "Limits on fishing for mud crabs."),
    199: ("limits", "Limits on fishing for prawns."),
    204: ("limits", "Closed to fishing for blue swimmer crabs at times."),
    206: ("limits", "Limits on fishing for crabs."),
    207: ("limits", "Closed to fishing for crabs at times."),
    209: ("limits", "No taking Samson fish or yellowtail kingfish here."),
    213: ("limits", "No fishing for rock lobster here."),
    214: ("limits", "No fishing for rock lobster here."),
    253: ("limits", "Closed to fishing for demersal scalefish at times."),
    166: ("limits", "Limits on recreational netting."),
    190: ("limits", "No longlines or set lines here."),
    249: ("limits", "No wire trace here."),
    243: ("habitat", "Fish Habitat Protection Area: special rules protect the habitat."),
    244: ("habitat", "Fish Habitat Protection Area: special rules protect the habitat."),
    245: ("habitat", "Fish Habitat Protection Area: special rules protect the habitat."),
    246: ("habitat", "Fish Habitat Protection Area: special rules protect the habitat."),
    247: ("habitat", "Fish Habitat Protection Area: special rules protect the habitat."),
    248: ("habitat", "Fish Habitat Protection Area: special rules protect the habitat."),
}

FHA_WHAT = "Fish Habitat Area: protects fish habitat from development. Fishing is allowed under the usual rules."
NO_ANCHOR = "No anchoring: use a public mooring or anchor outside."


WA_NAMES = {198: "King Sound, Derby"}
WA_SUFFIX = {112: " reef observation area"}


def clean_wa_place(text):
    """The place in a schedule entry: "Schedule 1 - Reef Observation Areas (2. Wallabi Group)" is
    "Wallabi Group", "Item 1 - Area 3 - Harvey River" is "Harvey River", "15 . Hamelin Bay" is
    "Hamelin Bay"; "" when it names no place."""
    t = re.sub(r"\s+", " ", (text or "").replace(chr(0x2013), "-")).strip()
    t = re.sub(r"^Schedule\s*\d*\s*-?\s*", "", t).strip()
    if t.startswith("(") and t.endswith(")"):
        t = t[1:-1].strip()
    m = re.match(r"^[A-Za-z ]+\((\d.*)\)$", t)
    if m:
        t = m.group(1)
    t = re.sub(r"^Item \d+\s*-?\s*", "", t)
    t = re.sub(r"^Area \d+\s*-\s*", "", t)
    t = re.sub(r"^\(?\d+\s*[.)-]\s*", "", t)
    t = re.sub(r"\s*FD \d+/\d+$", "", t)
    t = re.split(r"\s+-\s+All ", t)[0].strip(" -")
    if not t or re.fullmatch(r"Area \d+", t) or t.lower().startswith(("a person", "all ", "western australian waters", "waters ", "clause")):
        return ""
    return t


def wa_name(lid, layer_name, p):
    if lid in WA_NAMES:
        return WA_NAMES[lid]
    name = clean_wa_place(p.get("descript")) or clean_wa_place(p.get("legal"))
    if not name:
        m = re.search(r"\(([^)]*)\)", layer_name)
        name = m.group(1) if m else layer_name
    return tidy_text(name, 60) + WA_SUFFIX.get(lid, "")


def tolerance_for(geometry):
    """How much an area's outline is simplified, by how far it spreads (a river is long but thin):
    a few metres for a wreck site, up to about 150 m for a whole coast. A guide's outline, not a
    surveyed one."""
    from shapely.geometry import shape
    w, s, e, n = shape(geometry).bounds
    spread = math.hypot((e - w) * math.cos(math.radians((s + n) / 2)), n - s) * 111.32
    for limit, tol in ((2, 0.00002), (10, 0.0001), (40, 0.0004), (150, 0.0008)):
        if spread < limit:
            return tol
    return 0.0015


def closure(geometry, name, kind, what, official, src):
    tol = tolerance_for(geometry)
    # Islands and holes smaller than a few times the simplifying step are left out.
    geo, shp = tidy_polygon(geometry, tol, min_area=max(2e-9, (3 * tol) ** 2))
    if not geo:
        return None
    props = {"n": name, "k": kind, "w": what, "src": src, "sz": round(km2(shp), 3)}
    if official:
        props["o"] = official
    return {"type": "Feature", "properties": props, "geometry": geo}


KIND_ORDER = {"closed": 0, "limits": 1, "anchoring": 2, "habitat": 3}


def merge_same_areas(feats):
    """One feature for each area that two orders both cover (a river with netting and prawn rules,
    say): its rules joined, so a tap shows both and the fill isn't drawn twice. The stricter kind
    wins: closed, then limits, then the rest."""
    out, by_geometry = [], {}
    for f in feats:
        key = json.dumps(f["geometry"], separators=(",", ":"))
        first = by_geometry.get(key)
        if first is None:
            by_geometry[key] = f
            out.append(f)
            continue
        p, q = first["properties"], f["properties"]
        if q["w"] not in p["w"]:
            p["w"] = p["w"] + " " + q["w"]
        if q.get("o") and q["o"] not in (p.get("o") or ""):
            p["o"] = "; ".join(x for x in (p.get("o"), q["o"]) if x)
        if KIND_ORDER[q["k"]] < KIND_ORDER[p["k"]]:
            p["k"] = q["k"]
    return out


def closures(raw):
    feats = []
    for f in arcgis(raw, "gbr_sma.json", f"{GBRMPA}/Great_Barrier_Reef_Marine_Park_Special_Management_Areas_20/FeatureServer/57"):
        p = f["properties"]
        kind_what = GBR_SMA.get(p.get("LOC_TYPE_L"))
        if not kind_what or not f.get("geometry"):
            continue
        official = ", ".join(x for x in (tidy_text(p.get("CLAUSE_NAME"), 80), tidy_text(p.get("LEG_NAME"), 60)) if x)
        feats.append(closure(f["geometry"], gbr_name(p.get("LOC_NAME_L")) or p.get("SUB_NAME"), kind_what[0], kind_what[1],
                             official, "GBRMPA"))
    for f in arcgis(raw, "wpom_na.json", f"{GBRMPA}/Whitsundays_Plan_of_Management_no_anchoring_areas/FeatureServer/0"):
        if f.get("geometry"):
            feats.append(closure(f["geometry"], tidy_text(f["properties"].get("LOC_NAME_S"), 60), "anchoring", NO_ANCHOR,
                                 "Whitsundays Plan of Management", "GBRMPA"))
    for f in arcgis(raw, "hpom_na.json", f"{GBRMPA}/Hinchinbrook_Plan_of_Management_20/FeatureServer/39"):
        if f.get("geometry"):
            name = tidy_text((f["properties"].get("AREA_DESCR") or "").replace(UNREADABLE, "-"), 60) or "No-anchoring area"
            feats.append(closure(f["geometry"], name, "anchoring", NO_ANCHOR,
                                 "Hinchinbrook Plan of Management", "GBRMPA"))
    for f in arcgis(raw, "qld_fha.json", f"{QLD_PARKS}/7"):
        p = f["properties"]
        if f.get("geometry"):
            name = tidy_text(p.get("res_name"), 60)
            feats.append(closure(f["geometry"], (name + " Fish Habitat Area") if name else "Fish Habitat Area", "habitat", FHA_WHAT,
                                 tidy_text(p.get("plan_num"), 20), "Qld"))
    for lid, (kind, what) in WA_LAYERS.items():
        info = fetch(raw, f"wa_{lid}_info.json", f"{WA_GUIDE}/{lid}?f=json")
        layer_name = tidy_text(info.get("name"), 120) or ""
        for f in arcgis(raw, f"wa_{lid}.json", f"{WA_GUIDE}/{lid}", offset_deg="0.00005"):
            if not f.get("geometry"):
                continue
            if (f["properties"].get("legal") or "").startswith("Schedule 2 - Defined Fishing Areas"):
                continue  # the Abrolhos notice's areas where fishing is allowed, not closed
            official = tidy_text(f["properties"].get("name"), 120) or layer_name
            feats.append(closure(f["geometry"], wa_name(lid, layer_name, f["properties"]), kind, what, official, "WA"))
    feats = merge_same_areas([f for f in feats if f])
    # Big areas first, so the small ones inside them are drawn on top.
    feats.sort(key=lambda f: -f["properties"]["sz"])
    return feats


# Output -----------------------------------------------------------------------------------------

def write(name, feats):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f, separators=(",", ":"), ensure_ascii=False)
    print(f"{name}: {len(feats)} features, {os.path.getsize(path) / 1e3:.0f} kB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default="fishing_raw", help="download cache folder (outside the repo)")
    args = ap.parse_args()
    os.makedirs(args.raw, exist_ok=True)

    national = auchd_records(fetch(args.raw, "auchd.json", AUCHD)["features"])
    wa = wa_records(arcgis(args.raw, "wa_wrecks.json", WA_WRECKS))
    sa_zip = zipfile.ZipFile(io.BytesIO(fetch(args.raw, "sa_wrecks.zip", SA_WRECKS, binary=True)))
    sa = sa_records(json.loads(sa_zip.read("TOPO_Shipwrecks_GDA2020.geojson"))["features"])
    wrecks = merge_wrecks(national, [wa, sa])
    print(f"wrecks: national {len(national)}, WA Museum {len(wa)}, SA {len(sa)} -> {len(wrecks)} "
          f"({sum(1 for r in wrecks if r.get('also'))} state records matched a national one)")
    write("wrecks.geojson", wreck_features(wrecks))

    qld = arcgis(args.raw, "qld_ar.json", f"{QLD_PARKS}/4")
    gold_coast = fetch(args.raw, "gold_coast_reef.json", GOLD_COAST)["features"]
    sheets = xlsx_sheets(fetch(args.raw, "shark_gear.xlsx", SHARK_XLSX, binary=True))
    gear = shark_gear(sheets)
    print(f"shark gear: {sum(1 for f in gear if f['properties']['k'] == 'drum')} drumlines, "
          f"{sum(1 for f in gear if f['properties']['k'] == 'net')} nets")
    write("sites.geojson", artificial_reefs(qld, gold_coast) + gear)

    write("closures.geojson", closures(args.raw))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
