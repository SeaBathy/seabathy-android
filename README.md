# SeaBathy for iPhone (early work)

[![Build](https://github.com/SeaBathy/seabathy-android/actions/workflows/build.yml/badge.svg?branch=ios)](https://github.com/SeaBathy/seabathy-android/actions/workflows/build.yml?query=branch%3Aios)

The iPhone and iPad version of [SeaBathy](https://seabathy.com), the free, open-source seabed
depth map app for boaters. The finished app is [SeaBathy for Android](https://github.com/SeaBathy/seabathy-android);
this version is being ported from it feature by feature (see [PORTING.md](PORTING.md)).

> **Not for navigation.** SeaBathy shows survey data for interest and trip planning only.
> Always use official charts and your own judgement on the water.

## Status

So far: the map screen. A MapLibre map with the OpenFreeMap dark basemap, the centre crosshair,
and the map position remembered between launches. It is not on the App Store or TestFlight yet.

## Building

The project is described in `project.yml` and turned into an Xcode project by
[XcodeGen](https://github.com/yonaskolb/XcodeGen):

```
brew install xcodegen
xcodegen generate
open SeaBathy.xcodeproj
```

Every push is built by GitHub Actions on a Mac (`.github/workflows/build.yml`). It makes an
unsigned `.ipa` and runs the app in the iPhone simulator to take screenshots; both are attached to
each run as the `seabathy-ios-build` artifact.

Requirements: Xcode 16 or later, iOS 16 or later.

## Built with

- [MapLibre Native for iOS](https://github.com/maplibre/maplibre-native) (BSD 2-Clause)
- [OpenFreeMap](https://openfreemap.org) base map, © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors

## Licence

MIT, see [LICENSE](LICENSE).
