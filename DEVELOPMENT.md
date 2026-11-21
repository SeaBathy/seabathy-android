# Developing SeaBathy

How the app is put together and the technical details behind its features. For using the
app, see the [README](README.md). For building, signing and the device test, see
[BUILD_AND_TEST.md](BUILD_AND_TEST.md).

## Project layout

    app/src/main/kotlin/au/opendepth/app/
      MainActivity.kt   screen coordination and lifecycle
      backup/           backup and restore of waypoints, routes, tracks and settings
      conditions/       sea conditions: WeatherEngine (what to download and when, one at
                        a time, retries; SpotRules, WeatherReadings), the download client,
                        CallBudget (the free forecast service's limits), BackupForecast,
                        cache, map layers, the shore cut (coastline), good-weather alerts
      data/             waypoint, layer and data-source persistence
      diagnostics/      shareable debug log and resource tracking
      download/         offline tile downloads (foreground service, retries)
      gdal/             native GDAL path for large surveys and BigTIFFs
      geotiff/          GeoTIFF decoding, projections, depth grids, palettes,
                        relief shading
      location/         location providers (GPS, network, fused) and the first position
      map/              map style, overlay stack, tools and depth queries
      model/            plain data types
      nav/              routes, live navigation, boat profile, alarms
      settings/         preferences, quality levels and XYZ tile presets
      storage/          imports and GPX export
      terrain/          3D view host (WebView, bridge, depth-grid sampling)
      tides/            tide download, cache, curve and screen
      tiles/            loopback tile server for GDAL and MBTiles layers
      track/            track recording
      ui/               panels, dialogs and view binding
      util/             small shared helpers
    app/src/main/java/com/seabathy/bathypicker/core/
                        the high-detail depths engine (from BathyPicker); World.java
                        adds the sources outside Australia, Lerc2.java reads LERC tiles
    app/src/main/assets/
                        marine park zones, buoys and beacons, the built-in world
                        coastline, map fonts, the PROJ database, licences.txt
    app/src/github/     what only the GitHub build has (its storage permissions)
    terrain3d-web/      source of the bundled 3D viewer (three.js, esbuild)

## Quick start

Needs JDK 21 and the Android SDK (platform 36), with `sdk.dir` set in `local.properties`.
Gradle, the Android Gradle Plugin and Kotlin come with the wrapper. The app targets Android 16
(API 36), as Google Play requires.

    ./gradlew testGithubDebugUnitTest   # unit tests
    ./gradlew assembleGithubDebug       # app/build/outputs/apk/github/debug/app-github-debug.apk

The 3D viewer is prebuilt into `app/src/main/assets/terrain3d/`. After changing
`terrain3d-web/src/`, rebuild it with Node 18 or newer:

    cd terrain3d-web
    npm install
    npm run build

## GeoTIFF support

- One band becomes a depth layer that can be recoloured; three or four bands, or a palette
  image, become an imagery overlay.
- Coordinate systems: geographic WGS84, NAD83, GDA94 and GDA2020 (EPSG:4326, 4269, 4283,
  4979, 7844), Web Mercator (EPSG:3857), WGS84 UTM (EPSG:326xx and 327xx), and the GDA94 and
  GDA2020 MGA zones (EPSG:28348–28358 and 7846–7859). Others are rejected at import with a
  message asking for the file to be reprojected.
- Compression: none, LZW, Deflate and PackBits, striped or tiled.
- Rasters rotated relative to north are not supported.
- Depths can be negative-down or positive-down; an all-positive raster is treated as
  soundings and flipped.
- `GDAL_NODATA` is honoured, as are NaN and large float sentinels.

### Large files

Each import is routed by size and format:

- **Classic TIFFs under about 300 MB uncompressed** are read in Kotlin
  (`GeoTiffReader`, `TiffBlockReader`). The reader parses the header and tag tables from
  disk and decodes one strip or tile at a time, so the file is never loaded whole. The
  result is resampled to the GeoTIFF quality setting (1024 to 4096 px on the long edge)
  and kept in memory as one depth layer.
- **BigTIFFs, larger classic TIFFs and files with tiles or strips over 16 MB** go to
  native GDAL (`GdalDepthImporter`). GDAL
  opens the file by its real path (see `RealPathResolver`), and `GdalTileSource` and
  `GdalTileCache` render Web Mercator tiles on demand. `LocalTileServer`, a loopback HTTP
  server, hands them to MapLibre like any other raster source, so a survey of many
  gigabytes is never decoded in full.

### Native GDAL

`app/libs/gdal-3.7.0.aar` is the GDAL 3.7.0 release build from
[GDAL4Android](https://github.com/kikitte/GDAL4Android), committed to the repository so a
fresh clone builds with no NDK step. It has no Maven coordinates, so it is a local file
dependency in `app/build.gradle.kts`. It includes native libraries for `arm64-v8a` and
`x86_64` only; on other ABIs the GDAL path is unavailable and the Kotlin reader handles
what it can.

To update it, replace the AAR with a newer release and change the file name in
`app/build.gradle.kts` to match.

## Permissions

| Permission | Used for |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Online maps and data; pausing downloads while offline |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` | Offline downloads in the background, with a progress notification; good-weather alerts |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Locate, follow, tracks and navigation |
| `ACCESS_MEDIA_LOCATION` | Reading where a gallery photo was taken, for a waypoint from a photo |
| `VIBRATE` | Navigation and anchor alarms |
| `FOREGROUND_SERVICE_LOCATION` | Track recording and the anchor alarm keep running with the screen off |
| `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK` | The anchor alarm can wake the screen when the anchor drags |

The good-weather alert's check runs with Android's WorkManager every 4 hours (only with a
connection, not on low battery). It needs no location permission: it checks the places the
user picked, or the last position the app saw.

The Google Play build has no storage permissions (Google Play restricts them). The GitHub
build adds them (`app/src/github/AndroidManifest.xml`, "All files access") so offline maps
can be saved to `SeaBathy/OfflineDownloads`. The Play build saves them to `Download/SeaBathy`
instead (Android 11 and later let an app write its own files there), or to its own folder
under `Android/data` before Android 11: see `offlineDownloadsDir` in `TileDownloadRunner.kt`.
Check offline downloads on both builds; only the Play one takes that path. Surveys and charts are picked with the system
file picker, and Android won't let the app reopen a picked file by its path. So:

- **Big GeoTIFFs (over 300 MB) and BigTIFFs** are read **in place**, at any size, never copied:
  the app keeps the picked document open and GDAL reads it through a small native virtual file
  system, `/vsiseabathyfd/` ([`seabathy_fd_vsi.c`](app/src/main/cpp/seabathy_fd_vsi.c),
  [`DescriptorPaths`](app/src/main/kotlin/au/opendepth/app/storage/DescriptorPaths.kt)).
  After a restart the layer reopens the same way from its saved URI grant.
- **Smaller GeoTIFFs** are copied once into `files/surveys` (quick, and the in-memory Kotlin
  reader needs a normal file); **MBTiles** into `files/mbtiles`. Removing the layer removes
  the copy.

When the app can open the picked file by its real path (older Android versions), it reads it
there instead. See
[`GeoTiffImporter`](app/src/main/kotlin/au/opendepth/app/storage/GeoTiffImporter.kt) and
[`RealPathResolver`](app/src/main/kotlin/au/opendepth/app/storage/RealPathResolver.kt).

## The forecast service's limits

The forecast comes from Open-Meteo's free service, which allows each internet address 600 calls
a minute, 5,000 an hour and 10,000 a day, and counts every point of a request as a call. The
sea forecast and the wind are two services there, each counted on its own (the minute's limit
too): one area is up to 256 calls of each. The app is built never to use that up by itself:

- Whether a newer forecast is out is asked first (`ConditionsClient.latestRunMillis`: a few
  small status files that cost no calls). Nothing is downloaded on the off-chance; a newer run
  is fetched once the saved copy is four hours old, and "Update now" with nothing newer out
  keeps what's saved. A normal day is about five downloads.
- The status files watched are those of the models in use. The waves and currents come from the
  same models everywhere; the wind's "best match" differs by region (the European model round
  Australia, the American one round the Americas, the German one round Europe), so the app asks
  once which it is at the spot (`windModelAt`) and watches that one. Watching another model's
  file fetches each new wind run one run late.
- Only what has a newer run is downloaded. The wind models bring out a run every 6 hours, the
  wave model every 12 and the currents every 24. When only the wind is new, only the wind is
  asked for and laid over the sea values saved (`ConditionsClient.windOver`, decided by
  `WeatherEngine.seaIsLatest`): two sea downloads a day in place of six for an app left open
  all day. Every download asks 12 hours beyond the days wanted (`KEEP_HOURS`, no extra calls),
  so the sea values kept still reach the days wanted when the next wave run is due. A boat
  somewhere new, a backup forecast on show, or anything not known about the sea models' runs
  downloads everything; so does a wind-only request that fails.
- Every request goes through `CallBudget`, which paces them inside the minute, refuses to start
  a download that doesn't fit in the hour or the day (automatic ones stop at about 60% of the
  day, leaving the rest for the user's own taps), and remembers what was spent across restarts.
- A "too many requests" answer names its limit; nothing more is sent until it has passed.
- When Open-Meteo can't be had and what's saved is old or running out, `BackupForecast` takes
  over: NOAA GFS wind and WaveWatch III waves from PacIOOS, a whole area in one request each.
  It is coarser, with no gusts, currents or sea temperature, and is shown as the backup. When
  only the sea forecast is out (its limit reached, say) the main service's wind is kept, gusts
  and all, and just the waves are the backup's.

The wind is asked for over the water (`cell_selection=sea`) on the same points as the sea
forecast. Left to itself the service answers a point near the shore from a cell on land, up to
17 km away, where the wind is much lighter; that and a coarser wind grid had the app reading
3 to 4 knots light within 20 km of the coast. Measured against the model's own sea cells at 48
places off New South Wales, the blend is now within 1.3 knots on average.

Far inland, with no sea within 260 km (as far as the look for the nearest sea reaches), nothing
is downloaded: the area would be all land. The place is remembered until the boat is 30 km from
it, and the box says so.

Testing spends the same allowance, and the emulator shares this computer's address: every fresh
install downloads an area. A day of repeated `pm clear` runs used the whole day's sea forecast
calls on 19 November 2026. Keep the app's data between runs where the test allows.

## The shore

The colours, the flow lines, the arrows and the values box all stop at one shore: the coastline
from the map tiles (`Coastline`), with the built-in outline in any tile that isn't saved yet.

- The map draws the open sea as "ocean" and ends it at a straight line across each harbour, bay
  and river mouth. `SeaWaters` adds the water that joins the ocean (followed one tile inland),
  so harbours and estuaries are sea and lakes and dams are not.
- The values are drawn small and stretched; the shore is cut separately, at the size of the
  screen, for the part of the map on show (`ConditionsFeature.sharpen`).
- On land the box reads the nearest water by that coastline (`SeaMask.nearestSea`), not the
  nearest forecast point.

When changing anything that downloads a forecast, keep it going through `ConditionsClient` (so
it is counted), and run `WeatherEngineTest`: it sails the engine for hours against a fake
service and fails on any download loop.

## Tests

`./gradlew testGithubDebugUnitTest` covers GPX import and export, coordinate parsing and
formatting, great-circle maths, the depth ramp, relief shading, route depth checks, live
navigation (cross-track, arrival, VMG, ETA, fuel), tide curves, units, 3D grid sampling,
TIFF format routing, download retries and file naming, polite tile downloading (against a
local test server), and the Kotlin GeoTIFF reader. For the sea conditions it covers the
forecast and satellite readers, the call budget, the backup forecast, the update rules run
for hours against a fake service, which saved areas are kept or removed, the freshness dot,
where an inland spot's area goes, reading the coastline from map tiles, and the
good-weather alert's rules (limits, hours, what's new and what's gone). The
GeoTIFF tests build small synthetic rasters at test time (`SyntheticGeoTiff`), so no
sample data is checked in.

The native GDAL code (`GdalSupport`, `GdalTileSource`, `GdalTileCache` and most of
`GdalDepthImporter`) can't be loaded by the JVM test runner, so it is checked on a device
with the smoke test in [BUILD_AND_TEST.md](BUILD_AND_TEST.md). Its WKT-to-EPSG parsing is
plain Kotlin and has its own test (`GdalDepthImporterWktTest`).

## F-Droid

F-Droid builds every app from source, including native code, and doesn't normally accept
prebuilt binaries such as the GDAL AAR. Publishing there would need GDAL built from source
as part of the Gradle build, or an exception from the F-Droid maintainers. The APK on the
Releases page is the supported way to install.
