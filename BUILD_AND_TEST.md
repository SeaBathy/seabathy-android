# Build and test

## Requirements

- Java 21
- Android SDK with platform 34 and build-tools 34.0.0
- `local.properties` pointing at the SDK, for example `sdk.dir=/home/you/android-sdk`

## Build and verify

```bash
chmod +x gradlew tools/*.sh
./tools/build_and_verify.sh
```

That runs the unit tests, builds the debug APK and runs Android lint. The APK
lands at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Release build and signing

`assembleRelease` needs a keystore. It's deliberately not bundled or
generated automatically — create `keystore.properties` at the repo root
(gitignored, never commit it):

```properties
storeFile=keystore/seabathy-release.jks
storePassword=...
keyAlias=seabathy
keyPassword=...
```

Generate the keystore it points to, if you don't have one:

```bash
mkdir -p keystore
keytool -genkeypair -v -keystore keystore/seabathy-release.jks \
  -alias seabathy -keyalg RSA -keysize 2048 -validity 10000
```

Then:

```bash
./gradlew assembleRelease
```

produces `app/build/outputs/apk/release/app-release.apk`, signed with that
key. Without `keystore.properties` present, `assembleRelease` still builds
but produces an **unsigned** APK (no `signingConfig` is attached) — install
will fail until it's signed.

**Keep the keystore.** A release APK is only installable over a previous
one, without the user uninstalling first, if both are signed with the same
key. Losing the keystore means every future release needs a fresh key,
which forces every existing install to be manually uninstalled and
reinstalled. Back it up somewhere outside this repo; `keystore.properties`
and `*.jks` are both gitignored so neither the file nor its passwords ever
reach version control.

**A release build's signature differs from every debug build.** All test
APKs built during development (`assembleDebug`, or via
`tools/build_and_verify.sh`) are signed with the Android SDK's shared debug
key (`~/.android/debug.keystore`). A release build signed with a real
project key is a *different* signer, so Android refuses to install it over
an existing debug-signed install with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
— **uninstall any debug build already on the test device first.**

Individual tasks:

```bash
./gradlew testDebugUnitTest   # JVM unit tests
./gradlew assembleDebug       # debug APK
./gradlew assembleRelease     # release APK (needs keystore.properties, see above)
./gradlew lintDebug           # Android lint
```

## Unit tests

The JVM suite covers the parts that do not need a device: GPX generation and
XML escaping, coordinate parsing and formatting, great-circle maths, the depth
ramp model, and the GeoTIFF reader. The GeoTIFF tests synthesise small
single-band float TIFFs at build time (see `SyntheticGeoTiff`), so they exercise
real georeferencing, reprojection, no-data handling and downsampling without
any sample data checked in.

Not covered by JVM tests, because they need Android graphics or a live map:
bitmap rendering of depth grids, the RGB GeoTIFF path, and all view binding.
Verify those on device with the smoke test below.

## Device smoke test

1. Open the app and confirm the previous camera position and waypoints load.
2. Switch all basemaps, including custom XYZ if configured.
3. Import raster and vector MBTiles; toggle visibility, opacity, order and
   base-layer position.
4. Import the same filename twice and confirm both layers remain available.
5. Import a GeoTIFF depth raster. Confirm it lands in the right place, then
   open COLOURS and change the palette and the min/max range; the layer should
   recolour immediately without re-importing.
6. Import an RGB GeoTIFF and confirm it appears as an imagery overlay with no
   depth-colour control.
7. Add a satellite overlay, then drop its opacity and confirm bathymetry
   underneath blends through.
8. Open Settings, then Online map layers. Confirm the URL and attribution fields
   have a visible dark fill and a border, and that the border highlights while
   editing. Quick-add several presets.
9. Delete imported layers and confirm the app stays stable and the files are
   removed.
10. Create, edit, jump to and delete waypoints. Confirm the description field
    is saved with a new waypoint.
11. Test DD, DDM, DMS and Google-style coordinate input.
12. Create route and measurement points; confirm distance, bearing and ETA.
13. Export GPX and confirm the file opens in another app with waypoint names
    intact, including names containing `&`, `<` and `"`.
14. Test GPS off, locate and follow modes; background and resume the app.
15. Rotate or recreate the activity and verify layers and waypoints restore.
