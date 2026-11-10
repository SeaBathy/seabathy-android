# Contributing

Bug reports, fixes and improvements are welcome.

## Before opening a pull request

- Build and run the tests as described in [BUILD_AND_TEST.md](BUILD_AND_TEST.md):

  ```bash
  ./gradlew testGithubDebugUnitTest
  ./gradlew assembleGithubDebug
  ./gradlew lintGithubDebug
  ```

- If the change touches GDAL, large-file import or tiling, also run the device smoke test
  in [BUILD_AND_TEST.md](BUILD_AND_TEST.md#device-smoke-test). That code can't run in the
  JVM tests.
- If the change touches the 3D viewer, rebuild it from `terrain3d-web/` (see
  [DEVELOPMENT.md](DEVELOPMENT.md#quick-start)) and commit the built files in
  `app/src/main/assets/terrain3d/`.

## Code style

Match the file you're editing: Kotlin with 4-space indents and trailing lambdas, and
comments that explain *why* rather than repeat the code. New behaviour in the GeoTIFF
reader should come with a unit test; `SyntheticGeoTiff` builds test rasters without
checking in sample data.

## Pull requests

- One change per pull request.
- Describe what changed and why. For georeferencing, the large-file threshold or
  permissions, the reasoning matters as much as the code.

## Reporting a problem

Open an issue with:

- your phone and Android version
- the file type, if it's an import problem
- a debug log if you can: reproduce the problem, then use **Settings → Export debug log**.
  Keys and passwords in web addresses are removed, but the log can include file names and
  map positions, so read it before attaching it.
