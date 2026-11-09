# Build and test

## Requirements

- JDK 21
- Android SDK with platform 34 and build-tools 34.0.0
- `local.properties` pointing at the SDK, for example `sdk.dir=/home/you/android-sdk`

Gradle 8.4, the Android Gradle Plugin and Kotlin come with the wrapper.

## Build and verify

```bash
chmod +x gradlew tools/*.sh
./tools/build_and_verify.sh
```

This runs the unit tests, builds the debug APK and runs Android lint. The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

The same steps one at a time:

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # debug APK
./gradlew lintDebug           # Android lint
./gradlew assembleRelease     # release APK, see below
```

## Release builds and signing

A signed release build needs a keystore, which is never part of the repository. Create
`keystore.properties` at the repository root (it is gitignored):

```properties
storeFile=keystore/seabathy-release.jks
storePassword=...
keyAlias=seabathy
keyPassword=...
```

If you don't have a keystore yet, create one:

```bash
mkdir -p keystore
keytool -genkeypair -v -keystore keystore/seabathy-release.jks \
  -alias seabathy -keyalg RSA -keysize 2048 -validity 10000
```

`./gradlew assembleRelease` then writes a signed
`app/build/outputs/apk/release/app-release.apk`. Without `keystore.properties` the release
APK is unsigned and won't install.

- **Back up the keystore.** Android only installs an update over an existing install when
  both are signed with the same key. A lost key means every user has to uninstall before
  installing the next release.
- **Debug and release builds don't mix.** Debug builds are signed with the SDK's shared
  debug key, so installing a release build over one fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the debug build first.

## Device smoke test

The unit tests cover the logic that runs on the JVM (see
[DEVELOPMENT.md](DEVELOPMENT.md#tests)). Map rendering, the native GDAL path and the
screens need a device or emulator (`arm64-v8a` or `x86_64`):

1. Open the app and check the last map position and waypoints are restored.
2. Switch through the basemaps, including a custom XYZ map if one is set up.
3. Import raster and vector MBTiles. Change visibility, opacity and order.
4. Import the same file name twice and check both layers stay available.
5. Import a GeoTIFF depth survey. Check it is in the right place, then open **Colours** and
   change the palette and depth range: the layer should recolour without reimporting.
6. Import a GeoTIFF over 300 MB, or a BigTIFF, and check it draws and sharpens as you zoom
   in (the GDAL path).
7. Import an RGB GeoTIFF and check it appears as imagery, with no depth colours.
8. Add a satellite layer, lower its opacity and check the depths show through.
9. In **Settings → Online map layers**, add a few presets.
10. Delete the imported layers and check the app stays stable.
11. Create, edit, jump to and delete waypoints, including a note.
12. Enter coordinates as DD, DDM and DMS.
13. Plan a route and check distance, bearing and ETA.
14. Export GPX and open it in another app. Names containing `&`, `<` and `"` should come
    through intact.
15. Turn GPS off and on, try locate and follow, and send the app to the background and back.
16. Rotate the screen and check the layers and waypoints are restored.
17. Open a depth layer in 3D and check it renders and rotates smoothly.
