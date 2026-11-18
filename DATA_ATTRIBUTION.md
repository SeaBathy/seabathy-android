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
| Sentinel-2 cloudless 2024 (EOX), the built-in offline source (also in 3D) | Sentinel-2 cloudless - https://s2maps.eu by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2024) | Allowed under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/): non-commercial use, keep the credit, share packs under the same licence |

The apps' built-in offline download source is Sentinel-2 cloudless, whose
licence allows offline copies. "Save this area offline" (also in the 3D view)
works with any online map, including ones whose terms don't allow offline
copies: whether to save one is the user's choice.

Esri's basemaps are not built in, because Esri's terms need an ArcGIS licence
for use in apps. You can still add one yourself with "Add a map link" if your
own Esri terms allow it; the app then shows "Powered by Esri" with it. For a map or link you add yourself, the
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
| Place search | Open-Meteo Geocoding API (GeoNames data) | CC BY 4.0 |
| Place name at a point | OpenStreetMap Nominatim | ODbL; © OpenStreetMap contributors, credited beside the suggested name; only when you name a spot, one request at a time, per the [usage policy](https://operations.osmfoundation.org/policies/nominatim/) |

Tides are modelled, not an official tide table. The apps say so where they show them.

## Sea conditions

Sea conditions are downloaded from these free services and saved on the
phone or PC only. SeaBathy shows their credit on the map while they're shown.

| Data | Source | Terms |
| --- | --- | --- |
| Currents, waves, swell, sea temperature forecast | Open-Meteo Marine API, with E.U. Copernicus Marine Service Information (Météo-France SMOC and MFWAM), ECMWF, DWD and NOAA models | CC BY 4.0; free for non-commercial use. Credited on the map while shown |
| Wind and gusts | Open-Meteo Forecast API (ECMWF, NOAA GFS and other national models) | CC BY 4.0; free for non-commercial use |
| Satellite sea temperature | NASA JPL MUR SST v4.1, via NOAA CoastWatch ERDDAP; backup NOAA Geo-polar Blended (GHRSST) | Free and open; credit the source |
| Satellite chlorophyll | NOAA-20 VIIRS daily, via NOAA CoastWatch; backup Copernicus Sentinel-3A OLCI | Free to use and redistribute. Sentinel data must carry "Contains modified Copernicus Sentinel data" |
| Sea level anomaly | NOAA CoastWatch blended altimetry (Sentinel-3, CryoSat-2, Jason, SARAL), generated using AVISO+ products | Free to use and redistribute with this credit: "Data courtesy of NOAA; Sentinel data courtesy of Copernicus Program; Generated using AVISO+ products" |
| Salinity | NOAA CoastWatch SMAP, then SMOS, daily | Free to use and redistribute |
| Temperature at depth | HYCOM Consortium (ESPC-D-V02 ocean model) | Public release; demonstration product with no guarantee of availability |
| Latest true colour | NASA Global Imagery Browse Services (GIBS), VIIRS | NASA data, no restriction on use; credit requested |
| Seabed depths | GMRT, Global Multi-Resolution Topography (Ryan et al. 2009, doi:10.1029/2008GC002332) | CC BY 4.0; not to be used for navigation |
| High-detail depths (Australia) | Flukes, E. (2026), Multi-resolution bathymetry composite surface for Australian waters (EEZ), IMAS, doi:10.25959/Y9VF-V723; AusSeabed surveys, © Commonwealth of Australia (Geoscience Australia), and state surveys, each credited in the _sources.txt saved beside the file | CC BY 4.0; not to be used for navigation |

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
