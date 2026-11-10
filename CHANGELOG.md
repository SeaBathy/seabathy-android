# Changelog

## 1.1.0 — 2026-11-10

Ready for Google Play. Everything you use works as before.

- **Android 16:** the app now targets Android 16 (API 36), as Google Play requires. Back now
  closes the 3D view, the waypoint sheet and the side panel first, with Android's predictive
  back animation.
- **No storage permission:** SeaBathy no longer asks for "All files access". Pick a survey
  with the file picker as before. Big surveys (over 300 MB) and BigTIFFs, of any size, are
  read straight from where they are, never copied. Smaller ones are copied once into the app,
  where they also work offline; removing the layer removes the copy.
- **Built-in maps:** the Esri satellite, ocean and topographic maps are no longer built in,
  because Esri's terms need an ArcGIS licence for use in apps. The built-in satellite map is
  now Sentinel-2 cloudless (10 m, can be saved offline). You can still add any map you're
  allowed to use, Esri included, with "Add a map link"; the app adds Esri's credit for you.
- **Privacy policy** in Settings, also at https://seabathy.com/privacy.
- **Licences:** the third-party components inside the MapLibre map engine are now listed in
  Licences and credits.
- **Downloads:** a background download that reaches Android 15's daily time limit now stops
  cleanly with a notice instead of being killed.
- **Google Play build:** a separate `play` build (`com.seabathy.app`); the GitHub APK keeps
  its id so it updates over earlier installs.

## 1.0.1 — 2026-11-09

Maintenance update. Everything works as before.

- **3D view:** rebuilt with the latest esbuild (0.28); `npm audit` reports no known vulnerabilities in the 3D viewer's build.
- **3D view:** a new **Sentinel-2 satellite** map that can be saved offline with "Save this area offline". Esri satellite stays in the list to look at, but can no longer be saved offline, because Esri's terms don't allow offline copies.

## 1.0.0 — 2026-11-09

First public release.

- **Your own surveys:** GeoTIFF depth rasters, including multi-gigabyte BigTIFFs,
  coloured with adjustable palettes and relief shading, with the depth under the
  crosshair. MBTiles charts, raster and vector.
- **Free seabed depths** for the area on screen, anywhere in the world (GMRT).
- **3D seabed view** of any depth layer, with satellite or offline maps draped on it,
  contours, profiles, shadows and a Detail setting that adapts to the phone's memory.
- **Offline maps:** download the area on screen for use with no signal.
- **Tides and sea conditions:** tide charts, wind, waves, swell, current, sea
  temperature, chlorophyll, sea level and salinity, saved for offline use, in the
  units you choose.
- **Waypoints, routes and tracks,** with GPX import and export, route depth checks
  against your draft, anchor and depth alarms.
- **No account, ads or analytics.**

Not for navigation.
