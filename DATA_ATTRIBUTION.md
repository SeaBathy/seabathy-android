# SeaBathy data attribution

SeaBathy does not own or claim ownership of bathymetry, contour, chart, or map datasets.

Any MBTiles or GeoTIFF files uploaded or distributed with SeaBathy must come from sources that allow redistribution.

## Australian bathymetry and contour data

Australian bathymetry and contour MBTiles should only be uploaded if they are derived from open datasets such as Geoscience Australia / AusBathyTopo or another dataset with redistribution rights.

Suggested attribution:

- Source: Geoscience Australia / AusBathyTopo / relevant source dataset
- Licence: Creative Commons Attribution 4.0 International (CC BY 4.0), if applicable
- Processing: converted or derived into MBTiles format for use with SeaBathy
- Changes: tiled, simplified, styled, converted, or processed for offline app use

## Online maps

The maps below are fetched straight from each provider while you look at
them; this project doesn't host or redistribute their tiles. Both apps
(Android and Windows) show each map's credit on screen while it is shown,
on the chart and in the 3D view.

| Map | Credit shown | Provider's terms on offline copies |
| --- | --- | --- |
| OpenFreeMap Dark / Liberty (basemaps; free, no account or key) | OpenFreeMap © OpenMapTiles, © OpenStreetMap contributors | Basemap only: not offered for download |
| Coastline for the sea conditions (OpenFreeMap map tiles round each saved area; built-in world outline) | OpenFreeMap © OpenMapTiles, © OpenStreetMap contributors (ODbL); Natural Earth (public domain) | Kept on the device with the sea conditions it belongs to, removed with them |
| OpenStreetMap | © OpenStreetMap contributors | The [tile usage policy](https://operations.osmfoundation.org/policies/tiles/) bans bulk downloads |
| OpenTopoMap | © OpenStreetMap contributors, SRTM, OpenTopoMap (CC-BY-SA) | Asks for no bulk downloads |
| USGS Imagery | USGS The National Map | Allowed (US public domain) |
| OpenSeaMap seamarks | © OpenSeaMap contributors | Shown online only |
| Sentinel-2 cloudless 2024 (EOX), the built-in offline source (also in 3D) | EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2024) | Allowed under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/): non-commercial use, keep the credit, share packs under the same licence |
| Coast at low tide / high tide (DEA Tidal Composites, 2024, Geoscience Australia's WMS) | DEA Tidal Composites © Commonwealth of Australia (Geoscience Australia), CC BY 4.0, doi:10.26186/150381; contains modified Copernicus Sentinel data | Allowed: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), and the service lists no fees or access constraints. Cite: Newey, V., Bishop-Taylor, R., Phillips, C., Sagar, S. (2025). Digital Earth Australia Tidal Composites. Geoscience Australia, Canberra. https://doi.org/10.26186/150381. Offshore Great Barrier Reef reefs are not in it |
| Satellite-estimated shallow depths (Copernicus Marine, BATHYMETRY_GLO_PHY_COASTAL_L4_MY_016_001, released November 2025; map tiles from its WMTS, not the raw data) | Generated using E.U. Copernicus Marine Service Information; https://doi.org/10.48670/mds-00364 | Shown online only: the app doesn't save it offline. An estimate from satellite pictures, about 100 m, 0 to 40 m: not for navigation |

The apps' built-in offline download source is Sentinel-2 cloudless, whose
licence allows offline copies. "Save this area offline" (also in the 3D view)
works with the online maps you add, except the ones whose providers ask for no
offline or bulk copies (OpenStreetMap, OpenTopoMap) and the online-only
Copernicus depths. For a link you add yourself, whether its terms allow
saving it is your choice.

Some map services need an account or licence for use in apps, so they are not
built in. You can still add a map link of your own with "Add a map link" if its
terms allow it; the app shows the credit you type with it, exactly as typed.
For a map or link you add yourself, the
offline downloader will download from whatever you choose:
**the choice is yours, and so is checking whether a provider's terms allow it**.
SeaBathy is a tool: what you download with it, and from where, is up to you. The
table above says what the listed providers' terms say. The phone's 3D view keeps
online tiles only for as long as each server's own cache headers allow.

You are responsible for following each provider's terms, including limits
on request volume, offline copies and commercial use. The same applies to
any custom tile link or download source you add yourself.

## Tides and place names

| Data | Source | Terms |
| --- | --- | --- |
| Tide heights | Open-Meteo Marine API (`sea_level_height_msl`), with E.U. Copernicus Marine Service Information. The apps fit tide harmonics to this data to work offline | CC BY 4.0; free for non-commercial use |
| Official tide predictions, Queensland (Android app) | Maritime Safety Queensland predicted high and low tides for 77 stations, from [data.qld.gov.au](https://www.data.qld.gov.au/) (one dataset a station, e.g. `townsville-tide-gauge-predicted-high-low-data`). The app keeps a list of the stations, with each one's mean sea level (the mean of a year of its 10-minute predictions, same source), and downloads each year's CSV straight from data.qld.gov.au when a tide spot is near one. At the 60 stations where a curve drawn through the highs and lows is a poor fit, it also downloads that year's 10-minute predictions (about 1.8 MB) and draws the tide from them | CC BY 4.0, © State of Queensland (Department of Transport and Main Roads); credited on the tide screen while shown |
| Place search | Open-Meteo Geocoding API (GeoNames data) | CC BY 4.0 |
| Place name at a point | OpenStreetMap Nominatim | ODbL; © OpenStreetMap contributors, credited beside the suggested name; only when you name a spot, one request at a time, per the [usage policy](https://operations.osmfoundation.org/policies/nominatim/) |

Tides are modelled, not an official tide table, except near a Maritime Safety
Queensland station in the Android app, which uses its official predictions. The
apps say which they show.

## Sea conditions

Sea conditions are downloaded from these free services and saved on the
phone or PC only. SeaBathy shows their credit on the map while they're shown.

| Data | Source | Terms |
| --- | --- | --- |
| Currents, waves, swell, sea temperature forecast | Open-Meteo Marine API, with E.U. Copernicus Marine Service Information (Météo-France SMOC and MFWAM), ECMWF, DWD and NOAA models | CC BY 4.0; free for non-commercial use. Credited on the map while shown |
| Wind and gusts | Open-Meteo Forecast API (ECMWF, NOAA GFS and other national models) | CC BY 4.0; free for non-commercial use |
| Backup forecast (wind, waves and swell), shown only when Open-Meteo can't be reached or is at its limit | NOAA GFS and NOAA WaveWatch III, via PacIOOS ERDDAP (University of Hawaii) | U.S. Government models, free and open; credited on the map while shown |
| Satellite sea temperature | NASA JPL MUR SST v4.1, via NOAA CoastWatch ERDDAP; backups NOAA Coral Reef Watch CoralTemp, then NOAA Geo-polar Blended (GHRSST) | Free and open; credit the source |
| Satellite chlorophyll | NOAA-20 VIIRS daily, via NOAA CoastWatch; backup Copernicus Sentinel-3A OLCI | Free to use and redistribute. Sentinel data must carry "Contains modified Copernicus Sentinel data" |
| Sea level anomaly | NOAA CoastWatch blended altimetry (Sentinel-3, CryoSat-2, Jason, SARAL), generated using AVISO+ products | Free to use and redistribute with this credit: "Data courtesy of NOAA; Sentinel data courtesy of Copernicus Program; Generated using AVISO+ products" |
| Salinity | NOAA CoastWatch SMAP, then SMOS, daily | Free to use and redistribute |
| Water clarity (shown as a rough visibility, about 1.7 / Kd490 metres: an estimate) | NOAA CoastWatch VIIRS S-NPP Kd490, near-real-time, daily, 4 km (ERDDAP dataset noaacwNPPVIIRSkd490Daily, also listed as nesdisVHNkd490Daily), the last 5 days blended | U.S. Government data, public domain: free to use and redistribute. Credited "NOAA CoastWatch" on the map while shown |
| Temperature at depth | HYCOM Consortium (ESPC-D-V02 ocean model) | Public release; demonstration product with no guarantee of availability |
| Latest true colour | NASA Global Imagery Browse Services (GIBS), VIIRS | NASA data, no restriction on use; credit requested |
| Seabed depths | GMRT, Global Multi-Resolution Topography (Ryan et al. 2009, doi:10.1029/2008GC002332); also fills the gaps in the high-detail depths outside Australia | CC BY 4.0; not to be used for navigation |
| High-detail depths (Australia) | Flukes, E. (2026), Multi-resolution bathymetry composite surface for Australian waters (EEZ), IMAS, doi:10.25959/Y9VF-V723; AusSeabed surveys, © Commonwealth of Australia (Geoscience Australia), and state surveys, each credited in the _sources.txt saved beside the file | CC BY 4.0; not to be used for navigation |
| High-detail depths (Australian Marine Parks) | Flukes, E. & Monk, J. (2026), Bathymetry of the Australian Marine Parks, Institute for Marine and Antarctic Studies (IMAS), University of Tasmania (Seamap Australia, with Our Marine Parks grant funding), https://metadata.imas.utas.edu.au/geonetwork/srv/eng/catalog.search#/metadata/fb451be4-9de1-4bc2-8fd6-0f285f90916f; one mosaic per park, read in place from IMAS's public files for the parks finer than the composite | CC BY 4.0; not to be used for navigation. Credited "IMAS composite (Flukes 2026) and marine park bathymetry (Flukes & Monk 2026)" on the chart when a build uses one |
| High-detail depths (AusSeabed warehouse) | Surveys AusSeabed serves only from its data warehouse (warehouse.ausseabed.gov.au), cut for the drawn box: Sydney Harbour 2004 (Coastal CRC); Crowdy Head Offshore 2021; Port Phillip Bay tie lines 2021, drift algae 2018 and Portsea Hole 2018 (Deakin University Marine Mapping); Southern Gulf of Carpentaria (GA0276); all © Commonwealth of Australia (Geoscience Australia) | CC BY 4.0 (each checked in GA eCat); not to be used for navigation |
| High-detail depths (USA) | NOAA NCEI coastal DEMs, from NOAA's DEM global mosaic (gis.ngdc.noaa.gov), made for the drawn box; the DEMs used are named in the sources file | US public domain. Credited "Depths: NOAA NCEI coastal DEMs (public domain)" |
| High-detail depths (Canada) | Canadian Hydrographic Service NONNA-10 non-navigational bathymetry, from CHS's coverage service (nonna-geoserver.data.chs-shc.ca) | Open Government Licence - Canada; not for navigation. Credited "Contains information licensed under the Open Government Licence - Canada (CHS NONNA, not for navigation)" |
| High-detail depths (Europe) | EMODnet Bathymetry Consortium (2024), EMODnet Digital Bathymetry (DTM 2024), from EMODnet's coverage service (ows.emodnet-bathymetry.eu) | CC BY 4.0 |
| High-detail depths (New Zealand) | Toitū Te Whenua Land Information New Zealand, New Zealand Coastal LiDAR 1m DEM, read in place from LINZ's public files (nz-coastal on AWS) | CC BY 4.0 |
| High-detail depths (Pacific islands) | Digital Earth Pacific, Pacific Community (SPC), satellite-derived bathymetry from Sentinel-2 (product version 0.0.1), read in place from its public files; an estimate, shallow water only | CC BY 4.0. Credited "Depths (estimate): ..." |

Outside Australia each high-detail build lists, in the sources file saved beside it, every source
that has depths in that file (and GMRT when it filled gaps), with the credit line above. Nothing is
hosted by SeaBathy: each phone asks these services for the area drawn.

The forecasts come from models about 8 km apart. Near the shore, round
headlands and in narrow channels the real current can be very different.

## Buoys, beacons, pipelines and cables (beta layer)

Off by default. Shown only after the user ticks that it is not for navigation. Built from
these free datasets (positions rounded to about 1 m, lines simplified to about 15 m, mark
types mapped to IALA region A symbols):

- Navigation aids and public moorings: Transport for NSW (CC BY); WA Department of Transport,
  Navigation Aids DOT-004 and Abrolhos Islands public moorings (CC BY 3.0 AU); Vicmap Hydro
  navigation points (CC BY 4.0); Queensland lighthouses (State of Queensland, CC BY 4.0); AMSA
  aids to navigation (CC BY 4.0).
- Offshore petroleum pipelines: NOPTA, and Geoscience Australia Oil and Gas Pipelines (CC BY 4.0).
- Submarine cable protection zones: ACMA (via the CSIRO/NESP data service).
- Mooring areas, anchorages, marinas and some mooring buoys: © OpenStreetMap contributors (ODbL).

Many marks are missing (most Queensland marks, SA, NT and Tasmania; Victorian marks have no port or starboard type), some are out of date, and some
types are guessed from the data. Not for navigation.

## Marine park zones, reef and island names, boat ramps

Each is off until turned on (on the phone: Settings → Australia; on the desktop: the switches at the top of the side panel). Built from
these free datasets:

- Marine park zones, from each authority's own zoning (all Creative Commons Attribution, CC BY):
  Australian Marine Parks zones, Department of Climate Change, Energy, the Environment and Water
  (DCCEEW); Great Barrier Reef Marine Park Zoning, Great Barrier Reef Marine Park Authority;
  Great Barrier Reef Coast, Moreton Bay and Great Sandy marine park zones, Queensland Government;
  NSW Marine Protected Areas, NSW Department of Primary Industries (Fisheries); Marine Park
  Zoning, South Australian Department for Environment and Water; Fisheries Guide fishing
  prohibition orders (closed waters and marine park sanctuary areas), WA Department of Primary
  Industries and Regional Development; and for Victoria, Tasmania, the NT and WA park outlines,
  the Collaborative Australian Protected Areas Database (CAPAD) 2024, DCCEEW. Each zone is
  grouped by its official zone name: sanctuary, marine national park, preservation, scientific
  research and restricted access zones as no-take; habitat protection, conservation park,
  buffer, recreational use and special purpose zones as limited fishing; the rest as general
  use. Island land is left out. No-take and limited zones are simplified to about 30 m,
  general use to about 150 m.
- Reef, island, shoal, bank, rock and seamount names: Composite Gazetteer of Australia,
  Geoscience Australia and the states and territories (CC BY 4.0); Great Barrier Reef Features,
  Great Barrier Reef Marine Park Authority (CC BY 4.0), including reef numbers for unnamed reefs.
  Islands and rocks are kept only in the sea or within about 3 km of the coast, tested against
  Natural Earth 1:10m land and coastline (public domain).
- Boat ramps: © OpenStreetMap contributors (ODbL), slipways not marked private.

A guide only: zones change, and the official zoning map and rules for each park are the law.
Not for navigation.

## Fishing tools

The structure finder, drift planner and chartplotter export download nothing of their own:

- Find structure works on the phone from the depth layers already loaded. Its method follows NOAA's
  Benthic Terrain Modeler (bathymetric position index at two scales, and slope; Walbridge, S., Slocum, N.,
  Pobuda, M. and Wright, D. J. (2018), Unified Geomorphological Analysis Workflows with Benthic Terrain
  Modeler, Geosciences 8(3), 94). Its marks are an automatic guess from those depths, and say so.
- The drift planner uses the phone's GPS, and for its forecast estimate the sea conditions already saved
  (credited above).
- The chartplotter export writes what is on screen (waypoints, structure marks, contours traced from the
  depth layer, the sea conditions' lines, no-take zone edges) to a GPX file shared from the phone. Each
  source keeps its own terms above; the file says it is not for navigation.

## Shipwrecks, artificial reefs, shark gear, fishing closures, NSW seabed reefs

Each is off until turned on (Settings → Australia). The first four are built into the app by
tools/fishing_layers/build_fishing_layers.py (licences checked at each primary source on
2026-11-21); the NSW reefs are fetched from the NSW Government's own map service for the area on
the chart and kept on the phone. This project doesn't host or proxy any of them.

- Shipwrecks (about 5,700): Australian National Shipwrecks, from the Australasian Underwater
  Cultural Heritage Database, © Commonwealth of Australia (Department of Climate Change, Energy,
  the Environment and Water), CC BY 3.0 AU (data.gov.au), fetched through IMAS Seamap Australia's
  WFS; Shipwrecks (WAM-002), © Western Australian Museum, CC BY 4.0 (data.wa.gov.au); Shipwrecks,
  © Government of South Australia (Department for Environment and Water), CC BY 4.0
  (data.sa.gov.au). The WA and SA records replace the national record of the same wreck (same
  name, close enough for how rough the national position is, years agreeing); wrecks in New
  Zealand and national positions given only to the whole degree are left out. Positions are often
  approximate; the tap card says how each was found.
- Artificial reefs: Artificial reef sites - Queensland, © State of Queensland (Department of the
  Environment, Tourism, Science and Innovation), CC BY 4.0; Artificial Reefs (Narrowneck), City of
  Gold Coast, CC BY 3.0 AU (data.gov.au). Port Phillip Bay's reefs (DataVic) are not included yet:
  the DataVic record didn't resolve and Victoria's open-data service timed out when this was built.
- Shark control gear: Queensland Shark Control Program equipment locations (April 2024), © State
  of Queensland, CC BY 4.0 (publications.qld.gov.au). Gear is moved and changed.
- Fishing closures and management areas: Great Barrier Reef Marine Park Special Management Areas
  (seasonal closure, restricted access, public appreciation, maritime cultural heritage and
  natural resources conservation areas; the dugong, netting and dory areas, which bind commercial
  fishers, are left out), Whitsundays and Hinchinbrook Plan of Management no-anchoring areas,
  © Great Barrier Reef Marine Park Authority, CC BY 4.0; Fish habitat areas - Queensland, © State
  of Queensland, CC BY 4.0; Fisheries Guide - Consolidated Notices and Orders (DPIRD-061), WA
  Department of Primary Industries and Regional Development, CC BY 4.0: the closed waters, wreck
  sites, species and gear rules that bind recreational fishers and that the marine park zones
  don't already show (commercial-only orders and whole-coast orders are left out). Outlines are
  simplified to between about 2 m and 150 m by size. A guide only, not the law.
- NSW seabed reefs: NSW seabed reef extent 2023 ("Reef" outlines only), © State of NSW (NSW
  Department of Climate Change, Energy, the Environment and Water), CC BY 4.0 (SEED), fetched
  from mapprod3.environment.nsw.gov.au by map tile when the layer is on and the chart is zoomed
  in over the NSW coast.

Not for navigation.

## Not for navigation

SeaBathy and any supplied map data are for research, planning, educational, and situational-awareness use only.

They are not official nautical charts.

Do not use SeaBathy or supplied map data as the sole source for navigation, vessel operation, anchoring, route planning, or safety-of-life decisions.

Use official hydrographic charts, local notices to mariners, and appropriate navigation equipment.

## Upload rule

Do not upload MBTiles or GeoTIFF files derived from:

- Navionics
- C-MAP
- Garmin charts
- paid chart apps
- scraped commercial map tiles
- restricted hydrographic products
- any source where redistribution is not clearly allowed

## Bundled label font

`app/src/main/assets/glyphs/Open Sans Regular/` holds MapLibre glyph ranges for
Open Sans (Apache License 2.0), used for waypoint names on the app's built-in
None and Custom basemaps so labels also render offline.

## Looking up server names

The app uses the phone's own DNS. Only when that can't find one of the data servers above, it asks Cloudflare's public DNS over HTTPS (`cloudflare-dns.com`) for that server's address. Only the server's name is sent. See Cloudflare's [1.1.1.1 privacy policy](https://developers.cloudflare.com/1.1.1.1/privacy/public-dns-resolver/).
