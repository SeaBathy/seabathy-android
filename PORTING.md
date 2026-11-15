# Porting plan: SeaBathy for Android → iPhone

Each step is checked against the Android app (`seabathy-android`, Kotlin, MapLibre Android).
MapLibre Native for iOS is the same map engine, so styles, layers and MBTiles carry over closely.

| # | Feature | Android source | iOS approach | Status |
|---|---|---|---|---|
| 1 | Map screen: dark basemap, crosshair, remembered position | `MainActivity`, `MapStyleFactory`, `AppSettingsRepository` | SwiftUI + `MLNMapView` | Ported |
| 2 | Base map choices (dark, OSM, satellite, XYZ presets, custom URL) | `MapStyleFactory`, `XyzTilePresets`, `CustomBasemapDialog` | Same style JSON | Ported |
| 3 | Not-for-navigation notice on first launch, settings, units | `SettingsPanel`, `NavUnits` | SwiftUI sheet, `UserDefaults` | Ported |
| 4 | Import depth GeoTIFFs: colour ramp, depth at the crosshair | `geotiff/*`, `GeoTiffImporter`, `DepthRampDialog`, `DepthQueryController` | The app's own TIFF/BigTIFF reader (no GDAL); big files are streamed | Ported |
| 5 | Offline MBTiles and tile download | `LocalTileServer`, `OfflineTileDownloadService` | `mbtiles://` / local file sources, `MLNOfflineStorage` | Ported |
| 6 | Overlays: contours, buoys/beacons/pipelines | `OverlayLayerController`, `NavMarks` | Same GeoJSON files, MapLibre layers | Ported |
| 7 | GPS, tracks, routes, measuring | `GpsController`, `track/*`, `nav/*` | Core Location | Ported |
| 8 | Anchor watch | `AnchorWatchService` | Core Location background mode (iOS rules differ) | Ported |
| 9 | Tides | `tides/*` | Port the harmonics as-is; Swift Charts for the graph | Ported |
| 10 | Sea conditions | `conditions/*` | Port | Ported |
| 11 | 3D terrain view | `Terrain3DScreen`, `assets/terrain3d` | The same HTML/JS in `WKWebView` | Ported |
| 12 | App Store listing, TestFlight, signing | | Apple Developer Program, App Store Connect API key in GitHub secrets | Waiting on the Apple Developer account |

## Status (15 November 2026)

Every feature of SeaBathy for Android 1.2.0 has been ported. The app builds with Xcode 26 for iOS 16 and
later, the 268 unit tests ported from Android pass, and it runs in the iPhone simulator. It has not yet
been tried on a real iPhone (that needs the Apple Developer Program for signing).

Differences from Android:
- No GDAL on iOS. GeoTIFFs, including BigTIFF and very large files, are read by the app's own reader;
  projections other than geographic, Web Mercator and UTM/MGA (e.g. Albers), and ZSTD/LERC/JPEG
  compression, are refused with a plain-English message.
- Magnetic variation comes from the phone's compass (iOS has no public world magnetic model).
- Track recording and the anchor alarm use iOS background location (blue indicator) instead of Android's
  ongoing notification; the anchor siren follows the media volume.
- Offline downloads need SeaBathy kept open (iOS pauses downloads soon after leaving the app).
