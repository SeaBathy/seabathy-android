# Changelog

## 1.3.9 — 2026-11-21

More to see on the chart and in 3D, and a download about half the size.

- **Slope colours.** A depth layer can be coloured by how steep the seabed is instead of by
  depth: ledges, walls and drop-offs stand out whatever their depth. **Colours** → **Colour by**.
- **Contour lines.** Lines of equal depth on any depth layer, with the depth written on them.
  **Contours** on the layer's row: Auto picks a spacing to suit the depths on screen, or choose
  one.
- **Shallow water in red.** Pick a depth under **Boat & navigation** and water shallower than
  that is striped red, with a solid red edge so even a small bommie shows, on every depth
  layer, in 2D and in 3D. A guide only: it takes no account
  of the tide.
- **3D: a marker where you tap.** A tap on the seabed leaves a small ball there, and the depth
  and position stay up while you turn the view. **Clear** removes it.
- **3D: a clearer boat.** The boat's marker keeps one size on the screen, with a line down to
  the seabed under it.
- **Scale bar** on the chart, in your distance unit (it can be turned off under Map display).
- **Course arrow** on the boat's dot while the boat is moving.
- **Position format:** degrees and minutes, degrees minutes and seconds, or decimal degrees,
  wherever the app writes a position. **Settings** → **Map display**.
- **Waypoints:** change a waypoint's colour or symbol after it's made. A photo with no location
  in it can go where the phone is now, as well as at the crosshair.
- **Offline maps by a drawn box.** Draw the area on the chart and set the detail with − and +;
  the app says how many tiles that is before anything is downloaded.
- **Storage:** what the app keeps on the phone and how big each part is. **Settings** → **Waypoints
  and backup**.
- **A hidden layer gives its memory back,** and a layer left hidden isn't loaded at start.
- **Tap the credit line** at the bottom of the chart to read every map and data credit in full.
- **Map credits:** a map link you add shows the credit you type and nothing more. The built-in
  satellite picture carries its owner's current credit.
- **A smaller download:** the APK is about 49 MB, down from 89 MB. It no longer carries the
  parts only Intel devices and emulators use; phones and tablets (ARM) are unaffected.

## 1.3.8 — 2026-11-19

Sea conditions, all of it:

- **Wind near the coast reads true.** Within 20 km of shore it read 3 to 4 knots light: the
  forecast service answers a point near the coast from the land unless asked for the sea, and
  the wind was blended from points 44 km apart. It's now asked for over the water, on the same
  points as the waves.
- **New wind forecasts arrive on time.** The app watched another copy of the wind model for
  new runs, and got each one about six hours late.
- **Only what has changed is downloaded.** The weather centres bring out a new wind forecast
  every 6 hours, waves every 12 and currents once a day. The app asks first (free) whether
  anything newer is out; when only the wind is, only the wind is fetched. "Update now" with
  nothing newer out keeps what's saved and says so.
- **It can't use up the free forecast service by itself.** Every request is counted and paced
  against the service's limits, tides included. Fixed: a download that repeated every few
  minutes with a 1-day forecast, or with the phone's clock set back.
- **A backup forecast** (NOAA GFS wind and WaveWatch III waves) fills in when the main service
  is down or at its limit, and the box says it's the backup. When only the sea forecast is out,
  the usual wind, gusts and all, is kept.
- **A sharp shore at every zoom.** The colours, flow lines and arrows stop at OpenStreetMap's
  coastline. Harbours, bays and estuaries are coloured; lakes and dams aren't. A picture now
  takes about a tenth of a second to draw.
- **The box on land reads the nearest water by the coastline:** "At the boat" at a ramp or a
  jetty, and the right distance from a town on a river mouth (it could say 18 or 32 km).
- **Far inland,** with no sea within 260 km, nothing is downloaded and the box says so.
- **"How the forecast works":** a short plain explanation in the Sea conditions sheet, under
  "Update now".
- Satellite pictures: more sources are tried when a server is down or slow, and their values
  reach the shore.
- The forecast runs up to 12 hours beyond the days set.
- Fixed: the box's line could be cut off after the map jumped to a new GPS position.

## 1.3.7 — 2026-11-19

- **Google Play version: offline maps download again.** That version can't be given "All files
  access", so saving to `SeaBathy/OfflineDownloads` failed every time with "Download failed". It
  now saves to `Download/SeaBathy` (Android 11 and later) or the app's own folder (Android 8
  to 10); neither needs a permission, and a file manager can open both.
- The GitHub version is unchanged: it still saves to `SeaBathy/OfflineDownloads`.
- With notifications off, the "downloading" message names the folder the map is saved in.

## 1.3.6 — 2026-11-18

- **Sea conditions rebuilt under the hood** (the screens are the same): one engine now decides
  what to download and when, one download at a time, so it can't get stuck or forget:
  - It downloads when nothing is saved, the boat has moved 100 km, the forecast runs out within
    12 hours, a newer forecast is out, or it's 6 hours old. Hundreds of kilometres offshore it
    follows the boat the same way.
  - A failed download is tried again after 30 s, 1, 2, 5, 10, then every 15 minutes, and at once
    when the internet comes back or you tap update. Never more than 6 automatic downloads an hour.
  - Whatever is saved stays on show while anything downloads; the box says when it's waiting for
    the internet instead of showing nothing.
- **The box shows the boat's weather when the map is over land** (after loading a TIFF, say),
  instead of "No sea data here". On land, it's the nearest sea, told by OpenStreetMap's coastline
  rather than the satellite sea temperature (whose servers are often down).
- **The weather is there from the start:** with no remembered position, the app asks Android once
  where it is, without turning the GPS button on.
- **The first download shows on the map as soon as the forecast is in,** not after the satellite
  pictures.
- **Changing the depth of the temperature down below** no longer marks the whole forecast as new.

## 1.3.5 — 2026-11-18

- **Sea conditions show on the map straight away:** the first time they download, the map layer
  turns on by itself (wind), so it's clear it worked. After that it stays as you leave it.
- **On land, the nearest sea:** with the boat on land (on the trailer, at home), the values box
  shows the conditions at the nearest sea in the downloaded area, and how far away it is,
  instead of "No sea data here".

## 1.3.4 — 2026-11-18

- **The boat is where you are:** a rough position from phone masts (it can be kilometres out)
  no longer replaces a GPS fix, and is only shown while there's nothing better. When it is
  shown, the circle round the boat is as big as the position is uncertain, the chart zooms out
  to fit it, and the app says it's approximate until the GPS has a fix.
- **Sea conditions when the phone's DNS misbehaves:** if the phone can't look up one of the data
  servers' names (some DNS filters, VPNs and mobile networks answer for some servers and not
  others), the app looks it up with Cloudflare's public DNS over HTTPS instead, so the satellite
  pictures and tides still download.

## 1.3.3 — 2026-11-18

- **The boat shows straight away:** turning the GPS on puts the boat on the chart at once from the
  phone's newest recent position, and uses Android's fused location (GPS, Wi-Fi and phone masts) for
  a quick first fix indoors or under a roof. It says when Location is off in Android, or when it's
  still waiting for a position, instead of showing nothing.
- **No more long "downloading":** a connection that has gone quiet is given up on in seconds, and
  the sea conditions box says when it couldn't download (no internet) and will try again.

## 1.3.2 — 2026-11-18

- **Sea conditions stop at the shore:** wind, waves and the rest are cut along OpenStreetMap's
  coastline (the one the chart draws) instead of in blocks over the land, sharp even zoomed right
  in. The coastline for an area is saved with its weather, so it works offline, and is removed
  with it. Until it's saved a built-in world outline is used. Your other layers show on land as before.

## 1.3.1 — 2026-11-18

- **Faster sea conditions:** the forecast downloads several parts at once and shows as soon as it's
  in, usually within a few seconds; the satellite pictures follow. They only change once a day, so
  they're only downloaded once a day, and a satellite server that's down is skipped quickly instead
  of holding everything up for minutes.
- **2 days of forecast by default** (up to 8 if you choose), about a third of the download.
- **Inland:** the area round your spot goes over the nearest sea instead of being mostly land, and
  the box shows the conditions at the nearest sea and how far away it is.
- **Up to date at a glance:** a dot on the sea conditions box, green for the latest forecast, amber
  while it updates, red when it's out of date. Tap it to update.

## 1.3.0 — 2026-11-18

- **Polite offline downloads:** saving a map keeps a steady pace (about 20 tiles a second), pauses
  whenever the map's server asks it to slow down, and stops if the server keeps refusing, instead
  of retrying. It then suggests a map service that gives you your own key.
- **Better good-weather alerts:** add up to three places on the map, each with a radius; the worst
  wind and waves anywhere in it count. Limits for gusts and the hours of the day as well as wind and
  waves. A notification when a day starts or stops looking good, with a line for each day (times,
  wind and direction, gusts, waves), never between 9 pm and 7 am. When a day looks good the place's
  forecast is saved too (any connection, Wi-Fi only, or off), so tapping the alert shows it straight
  away. Checks every 4 hours, only with a connection and not on low battery.
- **Draw the depths area:** getting seabed depths (High detail or Standard) now starts with an
  empty chart: drag a finger to draw the box, then drag it to move it or a corner to resize it.
  Before, the box filled the screen, so it wasn't clear you could draw one.
- **Clearer wording:** the save screens say plainly that you're responsible for having the right
  to save a map offline.

## 1.2.9 — 2026-11-17

- **Tides anywhere, even inland:** following you more than about 25 km from the sea used to give
  "No tides here". It now finds the nearest coast with tides (up to about 260 km away) and shows
  how far away that tide point is.
- **Sea conditions keep up with the boat:** leave the downloaded area and the new one downloads
  straight away when you're online, instead of waiting up to 15 minutes.
- **Smoother sea maps:** the moving wind and current lines work out the flow once per time and
  layer instead of every frame, and hold still while a panel is open, so the settings no longer
  stutter.

## 1.2.8 — 2026-11-17

- **Settings in categories:** a short menu (Map display, Depths, Online and offline maps, Boat and
  navigation, Tides and sea conditions, Australia, Waypoints and backup, About) instead of one long
  list. Each opens its own page.
- **Australia page:** marine park zones, the no-take warning, reef and island names, boat ramps and
  buoys now live together under Settings → Australia, since they cover Australian waters only and
  the rest of the app works worldwide. The zone, name and ramp buttons in the layers panel are gone.

## 1.2.7 — 2026-11-17

- **Offline saving follows each map's rules:** OpenStreetMap's own tile server and OpenTopoMap
  don't allow saving areas for offline use, and block apps that do, so SeaBathy no longer offers
  it for those two. Every other map can still be saved.

## 1.2.6 — 2026-11-17

- **Rename layers:** a RENAME button on every layer in the layers panel, so a download like
  "High detail 27.30S 153.30E (0.97°)" can be called "Moreton Bay banks". Only the name in the
  app changes; the file itself is left as it is.
- **Tidier layers panel:** the BOTTOM button is gone; the arrows move a layer up or down, and
  dim when it is already at the top or bottom (and are hidden with only one layer).

## 1.2.5 — 2026-11-16

- **Draw the box for high-detail depths:** drag on the chart to draw the area you want (two
  fingers still move the map), and see its size and the download before anything starts.
- **Download size follows the phone:** Auto picks a size this phone can build from its free
  memory, or choose 100 MB to 1 GB under Settings → High-detail download size. A bigger box
  gets slightly coarser detail instead of failing.
- **Smoother after a build:** high-detail files now open piece by piece, so they don't fill the
  phone's memory, and the map stays where you drew the box instead of jumping.

## 1.2.4 — 2026-11-16

- **One agreement instead of notices everywhere:** on first launch you agree once that SeaBathy is
  not for navigation (also under Settings → Safety and terms, and at seabathy.com/terms). "Not for
  navigation" now always leads the credits line on the chart, and the repeated notices and extra
  tick boxes on the buoys layer and zones are gone. Route guidance shows a one-line reminder when it starts, and man overboard says to call for help (VHF 16, 000, EPIRB).
- **High-detail depths for Australian waters:** "Get seabed depths for this area" now offers
  High detail as well as Standard. It builds the area on the phone from the IMAS composite with
  AusSeabed and state multibeam surveys on top, down to a metre or two where surveyed, and opens
  it as a layer, up to about 110 km across. Cancel stops it cleanly, and the list of surveys used
  is kept with the layer. This is BathyPicker's engine, now inside SeaBathy, so one app does both.
- **No-take warning** now names the zone as well as the park (for example Ningaloo Marine Park ·
  Bundegi Sanctuary Area).
- **Marine park zones rebuilt** from each authority's own zoning (Australian Marine Parks,
  GBRMPA, Queensland, NSW, SA and WA fishing orders) and grouped by the official zone name, not a
  rough category. Habitat protection and conservation park zones now show as limited fishing,
  WA's general use and recreation areas are no longer marked no-take, island land no longer
  shows as zones, and tapping a zone shows its official name and who publishes it.
- **Reef and island names:** names now read properly (McDonald, Le Geographe's), repeats close
  together are gone, and Antarctic and garbled names are left out.
- **Pipelines, cables and mooring areas** in the buoys layer: 20 tiny areas had been simplified to
  fewer than 4 corners, which could stop the map reading the whole file. They're repaired.
- **Sturdier saved settings:** a good-weather alert or photo record missing a field (say, from an
  older or hand-edited backup) now falls back to defaults instead of failing.
- **Screen readers:** the values box lines, the basemap picker and the drawer's backdrop now say
  what they are.

## 1.2.3 — 2026-11-16

- **Cleaner cards:** zone, ramp, name and mark details, the no-take warning and the other notices now use the app's own dark cards, with a coloured badge and clear rows, instead of Android's grey pop-ups.

## 1.2.2 — 2026-11-16

- **Waypoint from a photo:** pick a photo from the gallery and a waypoint is made where it was
  taken (from its GPS tags), with the photo and the tide, sun and moon then, and the wind, waves
  and swell that hour from Open-Meteo's archived forecasts. For when you took a photo but forgot
  to drop a mark. A photo with no location can go at the crosshair instead.
- **Save any online map offline:** "Save this area offline" now works with every online map;
  what you save, and keeping to each provider's terms, is up to you.
- **Licence:** SeaBathy is now under the GNU GPL-3.0: free to use, change and share, and any
  shared copy or changed version must stay free and open source.
- **Marine park zones** for every Australian marine park, Commonwealth and state: no-take zones in
  green, limited fishing in yellow, general use in blue. Show all zones or only the no-take ones,
  tap a zone for its park and what's allowed, and get a warning (with a buzz) when the boat goes
  into a no-take zone, even with the zones hidden. A guide only: the official zoning map is the law.
- **Reef and island names**: named reefs, islands, shoals, banks and rocks around Australia, and
  Great Barrier Reef reef numbers close in.
- **Boat ramps** from OpenStreetMap.
- Quick switches for zones, names and ramps at the top of the layers panel.
- **Man overboard button** at the top of the chart: one tap marks the boat's position (or the
  first GPS fix, if the GPS was off) as a waypoint and starts steering back to it, with a buzz.
- **Backup and restore** (Settings → Backup and restore): one file with your waypoints and their
  photos, routes, tracks and settings. A daily backup can go to any folder you pick, including a
  cloud drive such as Google Drive or OneDrive, keeping the newest five. A SeaBathy Desktop backup
  adds its waypoints and routes to the phone's.
- **Waypoint photos:** open a waypoint and tap Take a photo. Each photo is saved with the time,
  tide, wind, waves, swell, current, sea temperature, sun and moon at that mark.
- **Good-weather alerts** (Sea conditions → Alerts): set the most wind and waves you'll go out in,
  how many hours in a row, how many days ahead and which days (any, weekends or chosen days). A
  few times a day the app checks the forecast at your spot and tells you about each good day once.
- **Import KML and KMZ** (Google Earth) as well as GPX: points become waypoints, lines routes.

## 1.2.1 — 2026-11-16

- **Temperature lines:** smooth, labelled lines over the sea temperature maps (satellite, breaks
  and at depth), every 0.25, 0.5, 1 or 2 degrees or spaced automatically, with whole degrees drawn
  heavier. Tiny rings from satellite noise are left out. Turn them off in Sea conditions.
- **Target temperature band:** set a range, say 24 to 26 °C, and water in it shows bright with bold
  edges while the rest is dimmed, on the map and in the colour key.
- **Eddy outlines:** the sea level map now draws lines at 10, 20 and 30 cm above (solid) and below
  (dashed) average, and marks warm (W) and cold (C) eddy middles with their height. Shown on every
  sea map, they are drawn in white over the other colours, as IMOS OceanCurrent does.
- **Sun, moon and solunar times** in the values box: sunrise, sunset, first light, moon phase, and
  solunar major and minor times (a traditional fishing guide, not a promise). Worked out on the
  phone, nothing downloaded.
- **Clearer labels:** "Surface temp", "Sea temp 10 m down", and sea level as "3 cm below average"
  (satellite height against its long-term average: higher often means a warm eddy, lower a cold one).
  "Saved areas" are now "Downloaded areas", "Round my spot" is "My spot", and the satellite switch
  is "Satellite sea maps" and lists what it downloads.

## 1.2.0 — 2026-11-15

- **Buoys, beacons, pipelines (beta):** a new map layer in Settings → Map display, off by default.
  It shows channel markers, cardinals, special marks, lights, moorings, offshore pipelines,
  submarine cables and mooring areas from free government data and OpenStreetMap, with chart-style
  icons; tap a mark for its type and source. It is not for navigation: every time it is turned on,
  the app shows a warning that must be ticked first, and it starts off each time the app opens.
- **Metric by default:** sea temperature in °C and tides and waves in metres until you choose
  otherwise, wherever the phone is set to. Temperature at depth now starts at 10 m instead of 50 m.

## 1.1.2 — 2026-11-12

Small improvements to sea conditions. Everything else works as before.

- **Temperature at depth updates straight away:** choosing a new "How far down" now downloads just that
  layer for the area on the map, instead of waiting for the next full update.
- **Clearer values box:** "Surface 24.1 °C · chlorophyll 0.20 mg/m³", then "50 m down 22.0 °C", then
  salinity and sea level, so surface and deeper readings can't be mixed up.
- **Clearer names:** "Temperature at depth (model)" and "Sea level and eddies (satellite)", and a one-line
  explanation of temperature breaks (where warm and cold water meet, often where fish gather).

## 1.1.0 — 2026-11-10

Ready for Google Play. Everything you use works as before.

- **Android 16:** the app now targets Android 16 (API 36), as Google Play requires. Back now
  closes the 3D view, the waypoint sheet and the side panel first, with Android's predictive
  back animation.
- **No storage permission:** SeaBathy no longer asks for "All files access". Pick a survey
  with the file picker as before. Big surveys (over 300 MB) and BigTIFFs, of any size, are
  read straight from where they are, never copied. Smaller ones are copied once into the app,
  where they also work offline; removing the layer removes the copy.
- **Built-in maps:** the Esri satellite, ocean and topographic maps are no longer built in,
  because Esri's terms need an ArcGIS licence for use in apps. The built-in satellite map is
  now Sentinel-2 cloudless (10 m, can be saved offline). You can still add any map you're
  allowed to use, Esri included, with "Add a map link"; the app adds Esri's credit for you.
- **Privacy policy** in Settings, also at https://seabathy.com/privacy.
- **Licences:** the third-party components inside the MapLibre map engine are now listed in
  Licences and credits.
- **Downloads:** a background download that reaches Android 15's daily time limit now stops
  cleanly with a notice instead of being killed.
- **Google Play build:** a separate `play` build (`com.seabathy.app`); the GitHub APK keeps
  its id so it updates over earlier installs.

## 1.0.1 — 2026-11-09

Maintenance update. Everything works as before.

- **3D view:** rebuilt with the latest esbuild (0.28); `npm audit` reports no known vulnerabilities in the 3D viewer's build.
- **3D view:** a new **Sentinel-2 satellite** map that can be saved offline with "Save this area offline". Esri satellite stays in the list to look at, but can no longer be saved offline, because Esri's terms don't allow offline copies.

## 1.0.0 — 2026-11-09

First public release.

- **Your own surveys:** GeoTIFF depth rasters, including multi-gigabyte BigTIFFs,
  coloured with adjustable palettes and relief shading, with the depth under the
  crosshair. MBTiles charts, raster and vector.
- **Free seabed depths** for the area on screen, anywhere in the world (GMRT).
- **3D seabed view** of any depth layer, with satellite or offline maps draped on it,
  contours, profiles, shadows and a Detail setting that adapts to the phone's memory.
- **Offline maps:** download the area on screen for use with no signal.
- **Tides and sea conditions:** tide charts, wind, waves, swell, current, sea
  temperature, chlorophyll, sea level and salinity, saved for offline use, in the
  units you choose.
- **Waypoints, routes and tracks,** with GPX import and export, route depth checks
  against your draft, anchor and depth alarms.
- **No account, ads or analytics.**

Not for navigation.
