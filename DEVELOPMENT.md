# Developing SeaBathy

How the app is put together, how to build and test it, and the technical details behind its features. For using the app, see the [README](README.md).

## Project layout

    app/src/main/kotlin/au/opendepth/app/
      MainActivity.kt   screen coordination and lifecycle
      data/             waypoint, layer and data-source persistence
      diagnostics/      shareable debug log and resource tracking
      download/         offline tile downloads (foreground service, retries)
      gdal/             native-GDAL path for large/BigTIFF surveys
      geotiff/          GeoTIFF decoding, projections, depth grids, palettes,
                        relief shading
      location/         location provider plumbing
      map/              map style, overlay stack, tools and depth queries
      model/            plain data types
      nav/              route plans, live navigation, boat profile, alarms
      settings/         preferences, quality levels and XYZ tile presets
      storage/          imports and GPX export
      terrain/          3D view host (WebView, bridge, depth-grid sampling)
      tides/            tide forecast download, cache, curve and screen
      tiles/            loopback tile server for GDAL and MBTiles layers
      ui/               panels, dialogs and view binding
      util/             small shared helpers
    terrain3d-web/      source of the bundled 3D viewer (three.js, esbuild)

## Build

Requirements:

- JDK 21
- Android SDK, platform 34 and build-tools 34.0.0 (no separate Android NDK
  install needed — see below)
- `local.properties` pointing at the SDK (`sdk.dir=/path/to/android-sdk`)
- Included Gradle wrapper (Gradle 8.4, Android Gradle Plugin 8.2.2, Kotlin
  2.2.10 — no local install of any of these needed)

Build a debug APK:

    ./gradlew assembleDebug

APK output:

    app/build/outputs/apk/debug/app-debug.apk

Run the unit tests:

    ./gradlew testDebugUnitTest

The 3D viewer is prebuilt into `app/src/main/assets/terrain3d/`. After
changing anything in `terrain3d-web/src/`, rebuild it (Node 18+):

    cd terrain3d-web
    npm install
    npm run build

See [`BUILD_AND_TEST.md`](BUILD_AND_TEST.md) for the full verification script
and the on-device smoke test.

### Native GDAL dependency

The BigTIFF/on-demand-tiling path (see above) depends on a native GDAL
build, vendored at `app/libs/gdal-3.7.0.aar` and **committed to this
repository** — a fresh clone needs no separate download or NDK build step.

That AAR is [GDAL4Android](https://github.com/kikitte/GDAL4Android)'s GDAL
3.7.0 release build. It has no Maven/JitPack coordinates; it was downloaded
by hand from that project's GitHub Releases page and added as a local file
dependency (`implementation(files("libs/gdal-3.7.0.aar"))` in
`app/build.gradle.kts`). It ships native `.so` libraries for `arm64-v8a` and
`x86_64` only.

If you need to update or replace it, download the new AAR from that
project's releases, overwrite `app/libs/gdal-3.7.0.aar`, and update the
version in the filename and `app/build.gradle.kts` to match.

## Map data

SeaBathy does not include map data. MBTiles and GeoTIFF files are loaded
separately from device storage, and online tile layers are fetched from their
provider.

Depth colouring and the exact depth readout come from single-band GeoTIFF
rasters. Depth display from MBTiles depends on vector contour tiles exposing
supported contour/depth attributes, and reports a bound rather than an exact
sounding.

### GeoTIFF support

- Single band becomes a recolourable depth layer; three or four bands, or a
  palette image, becomes an imagery overlay.
- Coordinate systems: EPSG:4326 (and equivalent geographic codes),
  EPSG:3857 Web Mercator, and WGS84 UTM (EPSG:326xx / 327xx). Anything else is
  rejected at import with a message asking you to reproject first.
- Compression: uncompressed, LZW, Deflate and PackBits, striped or tiled.
- Rasters rotated relative to north are not supported.
- Depth values may be negative-down or positive-down; an all-positive raster is
  treated as soundings and converted.
- `GDAL_NODATA` is honoured, as are NaN and large float sentinels.

#### Large-file / BigTIFF architecture

Every import is routed by size and format, not decoded the same way
regardless:

- A classic (32-bit offset) TIFF under ~300MB estimated uncompressed size
  goes through the pure-Kotlin path (`GeoTiffReader`/`TiffBlockReader`). It
  never loads the source file into memory in full: it parses the header and
  tag tables directly off disk, then decodes one strip or tile at a time
  straight from the file, using the TIFF's own offset table (the layout a
  Cloud Optimized GeoTIFF's internal tiling is built around). The decoded
  output grid is resampled to the GeoTIFF quality setting's size (1024 to
  4096 px on the long edge) and held in memory as a single depth layer.
- A BigTIFF (64-bit offset), or a classic TIFF over that size threshold, is
  routed instead to a native-GDAL-backed path (`GdalDepthImporter`). GDAL
  opens the file directly by real filesystem path (see `RealPathResolver`
  and the `MANAGE_EXTERNAL_STORAGE` permission below) and the app serves map
  tiles from it on demand rather than decoding the whole raster up front:
  `GdalTileSource`/`GdalTileCache` render and cache individual Web Mercator
  tiles from the open dataset, and `LocalTileServer` (an embedded loopback
  HTTP server) hands them to MapLibre the same way it would fetch any other
  raster tile source. This is what makes a many-gigabyte GeoTIFF viewable
  without ever holding it, or a full-resolution decode of it, in memory.
- The GDAL native library ships as a prebuilt AAR (see
  [Build](#build)/[Native GDAL dependency](#native-gdal-dependency) below)
  with binaries for `arm64-v8a` and `x86_64` only, so the GDAL/BigTIFF import
  path is unavailable on `armeabi-v7a`/`x86` devices; MapLibre and the
  pure-Kotlin GeoTIFF path are unaffected.

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE` — fetching online basemap/overlay tiles,
  and pausing offline downloads while there is no connection.
- `VIBRATE` — navigation alarms.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` —
  running offline downloads in the background with a progress notification.
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — GPS locate/follow.
- `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` (API ≤29) and
  `MANAGE_EXTERNAL_STORAGE` (API 30+) — importing MBTiles/GeoTIFF files by
  real filesystem path rather than through the document-picker's
  `content://` indirection. This is specifically because native GDAL's
  `open()` needs to reopen a real path from scratch for the BigTIFF import
  path above; it cannot be handed a `content://` file descriptor the way the
  pure-Kotlin decode path can. See
  [`RealPathResolver`](app/src/main/kotlin/au/opendepth/app/storage/RealPathResolver.kt)'s
  doc comment for the full explanation, including the on-device permission
  error this works around.

## Test coverage

`./gradlew test` covers GPX generation, coordinate
parsing/formatting, great-circle maths, the depth ramp model, relief shading,
route depth checks, route plans and live navigation (cross-track, arrival, VMG, ETA, fuel), tide curves, GPX import, units, 3D grid sampling, TIFF format routing, download retry and
file naming, and the pure-Kotlin GeoTIFF reader (including
predictor/compression handling) with synthetic rasters built at test time.

Known gap: the native-GDAL code path (`GdalSupport`, most of
`GdalDepthImporter`, `GdalTileSource`, `GdalTileCache`) has little to no JVM
test coverage, because it calls into a native library through JNI that the
plain JVM test runner cannot load — it can only be exercised on a real
arm64-v8a/x86_64 device. The one exception is the EPSG-from-WKT parsing
heuristic in `GdalDepthImporter`, which is pure string/regex logic and is
unit-tested directly (`GdalDepthImporterWktTest`). This is an accepted,
documented limitation of testing native code from a JVM-only CI/build
environment, not an oversight.

## Distribution / F-Droid

SeaBathy bundles a prebuilt native GDAL library (see
[Native GDAL dependency](#native-gdal-dependency) above). That's
straightforward for direct APK distribution or sideloading — it's exactly
what happens either way. It is **not** straightforward for the official
F-Droid repository: F-Droid's build system compiles every app, including
native code, from source in its own build environment, and is generally
unfriendly to prebuilt native binaries bundled into a repo (they're normally
rejected or require an explicit, justified exception). Submitting SeaBathy to
F-Droid as-is would very likely need either a from-source GDAL build
integrated into the Gradle build, or an accepted exception from F-Droid's
maintainers — this hasn't been attempted, and isn't a small undertaking.
