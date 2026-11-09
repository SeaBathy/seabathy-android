# Third-party software

SeaBathy's own code is under the MIT licence in [`LICENSE`](LICENSE). The app
is built with, and ships, the open-source software below under its own
licences. Their full licence texts ship inside the app (Settings > Licences and
credits; in the source, `app/src/main/assets/licenses.txt`). Map and data
services are listed separately in [`DATA_ATTRIBUTION.md`](DATA_ATTRIBUTION.md).

## Libraries

| Component | Version | Licence | Source |
| --- | --- | --- | --- |
| MapLibre Native for Android | 13.6.1 | BSD 2-Clause | https://github.com/maplibre/maplibre-native |
| MapLibre Java (GeoJSON, Turf) | 6.0.1 | MIT | https://github.com/maplibre/maplibre-java |
| MapLibre Gestures | 0.0.4 | BSD 2-Clause | https://github.com/maplibre/maplibre-gestures-android |
| Timber | 5.0.1 | Apache 2.0 | https://github.com/JakeWharton/timber |
| AndroidX (core, appcompat, constraintlayout, lifecycle) | see `app/build.gradle.kts` | Apache 2.0 | https://developer.android.com/jetpack/androidx |
| Material Components for Android | 1.11.0 | Apache 2.0 | https://github.com/material-components/material-components-android |
| Kotlin standard library, kotlinx.coroutines | 2.2.10, 1.10.2 | Apache 2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Gson | 2.10.1 | Apache 2.0 | https://github.com/google/gson |
| OkHttp, Okio | 4.12.0, 3.6.0 | Apache 2.0 | https://github.com/square/okhttp |
| NanoHTTPD | 2.3.1 | BSD 3-Clause | https://github.com/NanoHttpd/nanohttpd |
| NGA TIFF (mil.nga:tiff) | 3.0.0 | MIT | https://github.com/ngageoint/tiff-java |
| three.js (3D view, in `terrain3d-web`) | 0.170.0 | MIT | https://github.com/mrdoob/three.js |
| Open Sans glyphs (`assets/glyphs`) | — | Apache 2.0 (older releases), SIL OFL 1.1 (current) | https://github.com/googlefonts/opensans |
| PROJ database (`assets/proj/proj.db`) | — | MIT | https://proj.org |
| LeakCanary (debug builds only, not in release APKs) | 2.14 | Apache 2.0 | https://github.com/square/leakcanary |

### Native GDAL (`app/libs/gdal-3.7.0.aar`)

Built by [GDAL4Android](https://github.com/kikitte/GDAL4Android). It holds these
shared libraries, each loaded as a separate `.so`:

| Library | Licence | Source |
| --- | --- | --- |
| GDAL 3.7.0 (`libgdal`, `libgdalalljni`) | MIT/X | https://github.com/OSGeo/gdal |
| PROJ (`libproj`) | MIT | https://github.com/OSGeo/PROJ |
| SQLite (`libsqlite3`) | Public domain | https://sqlite.org |
| Expat (`libexpat`) | MIT | https://github.com/libexpat/libexpat |
| GNU libiconv (`libiconv`, `libcharset`) | LGPL 2.1 or later | https://www.gnu.org/software/libiconv/ |

GNU libiconv is under the GNU Lesser General Public License 2.1. SeaBathy uses
it unmodified, as a separate shared library that can be replaced with another
build. Its full source is at the link above, and the build scripts that produced
these binaries are in the GDAL4Android repository.
