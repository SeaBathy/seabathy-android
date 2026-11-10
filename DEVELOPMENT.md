# Developing SeaBathy

How the app is put together and the technical details behind its features. For using the
app, see the [README](README.md). For building, signing and the device test, see
[BUILD_AND_TEST.md](BUILD_AND_TEST.md).

## Project layout

    app/src/main/kotlin/au/opendepth/app/
      MainActivity.kt   screen coordination and lifecycle
      conditions/       sea conditions download, cache and map layers
      data/             waypoint, layer and data-source persistence
      diagnostics/      shareable debug log and resource tracking
      download/         offline tile downloads (foreground service, retries)
      gdal/             native GDAL path for large surveys and BigTIFFs
      geotiff/          GeoTIFF decoding, projections, depth grids, palettes,
                        relief shading
      location/         location provider
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
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` | Offline downloads in the background, with a progress notification |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Locate, follow, tracks and navigation |
| `VIBRATE` | Navigation and anchor alarms |
| `FOREGROUND_SERVICE_LOCATION` | Track recording and the anchor alarm keep running with the screen off |
| `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK` | The anchor alarm can wake the screen when the anchor drags |

There are no storage permissions (Google Play restricts them). Surveys and charts are picked
with the system file picker. GDAL needs a real file path (it reopens files itself and can't
use the picker's `content://` descriptor), so when Android allows the app to open the picked
file by its path it is read in place; otherwise it is copied once into the app's own storage
(`files/surveys` for GeoTIFFs, `files/mbtiles` for MBTiles) and opened from there, which also
keeps it working offline. See
[`GeoTiffImporter`](app/src/main/kotlin/au/opendepth/app/storage/GeoTiffImporter.kt) and
[`RealPathResolver`](app/src/main/kotlin/au/opendepth/app/storage/RealPathResolver.kt).

## Tests

`./gradlew testDebugUnitTest` covers GPX import and export, coordinate parsing and
formatting, great-circle maths, the depth ramp, relief shading, route depth checks, live
navigation (cross-track, arrival, VMG, ETA, fuel), tide curves, units, 3D grid sampling,
TIFF format routing, download retries and file naming, and the Kotlin GeoTIFF reader. The
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
