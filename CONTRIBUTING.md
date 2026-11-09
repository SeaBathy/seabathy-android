# Contributing

SeaBathy is a small, single-maintainer app, so this is intentionally short.

## Building and testing

See [`BUILD_AND_TEST.md`](BUILD_AND_TEST.md) and the
[Build](README.md#build) section of the README. In short:

    ./gradlew testDebugUnitTest   # JVM unit tests
    ./gradlew assembleDebug       # debug APK
    ./gradlew lintDebug           # Android lint

Run the unit tests before opening a PR. If your change touches the
GDAL/BigTIFF import or tiling path, also run the device smoke test in
`BUILD_AND_TEST.md` — that code can't be exercised on the JVM (see
[Test coverage](README.md#test-coverage) in the README).

## Code style

There's no ktlint/detekt configured yet. Match the style already in the file
you're editing (4-space indent, trailing-lambda style, doc comments only
where the *why* isn't obvious from the code). Adding ktlint/detekt would be
reasonable for a project this size, but hasn't been done — happy to review a
PR that adds one with a sensible default ruleset.

## Pull requests

- Keep PRs focused: one change, one PR.
- Explain *why*, not just what — especially for anything touching
  georeferencing, the GDAL routing threshold, or permission handling, where
  the reasoning is usually more valuable than the diff itself.
- New behavior in the pure-Kotlin GeoTIFF path should come with a unit test
  (see `SyntheticGeoTiff` for building test rasters without checking in
  sample data).

## Reporting issues

Open a GitHub issue. Include the device/Android version, the file format if
it's an import problem, and the diagnostic log if you can get one (Settings
-> Diagnostic logging -> On, reproduce, then Settings -> Share debug log).
