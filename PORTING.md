# Porting plan: SeaBathy for Android → iPhone

Each step is checked against the Android app (`seabathy-android`, Kotlin, MapLibre Android).
MapLibre Native for iOS is the same map engine, so styles, layers and MBTiles carry over closely.

| # | Feature | Android source | iOS approach | Status |
|---|---|---|---|---|
| 1 | Map screen: dark basemap, crosshair, remembered position | `MainActivity`, `MapStyleFactory`, `AppSettingsRepository` | SwiftUI + `MLNMapView` | **Done** |
| 2 | Base map choices (dark, OSM, satellite, XYZ presets, custom URL) | `MapStyleFactory`, `XyzTilePresets`, `CustomBasemapDialog` | Same style JSON | |
| 3 | Not-for-navigation notice on first launch, settings, units | `SettingsPanel`, `NavUnits` | SwiftUI sheet, `UserDefaults` | |
| 4 | Import depth GeoTIFFs: colour ramp, depth at the crosshair | `geotiff/*`, `GeoTiffImporter`, `DepthRampDialog`, `DepthQueryController` | Swift port of the Kotlin TIFF reader; no GDAL in version 1, so very large files and BigTIFFs are refused (as on Android phones without GDAL) | |
| 5 | Offline MBTiles and tile download | `LocalTileServer`, `OfflineTileDownloadService` | `mbtiles://` / local file sources, `MLNOfflineStorage` | |
| 6 | Overlays: contours, buoys/beacons/pipelines | `OverlayLayerController`, `NavMarks` | Same GeoJSON files, MapLibre layers | |
| 7 | GPS, tracks, routes, measuring | `GpsController`, `track/*`, `nav/*` | Core Location | |
| 8 | Anchor watch | `AnchorWatchService` | Core Location background mode (iOS rules differ) | |
| 9 | Tides | `tides/*` | Port the harmonics as-is; Swift Charts for the graph | |
| 10 | Sea conditions | `conditions/*` | Port | |
| 11 | 3D terrain view | `Terrain3DScreen`, `assets/terrain3d` | The same HTML/JS in `WKWebView` | |
| 12 | App Store listing, TestFlight, signing | | Apple Developer Program, App Store Connect API key in GitHub secrets | |
