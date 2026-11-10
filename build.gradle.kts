plugins {
    // 8.13 for compileSdk/targetSdk 36 (Google Play's Android 16 requirement).
    id("com.android.application") version "8.13.2" apply false
    // Bumped from 1.9.22 alongside the MapLibre SDK upgrade: MapLibre
    // 13.6.1 transitively pulls kotlin-stdlib 2.2.10, whose class metadata
    // the 1.9.x compiler cannot read ("compiled with an incompatible
    // version of Kotlin"). Matched to that stdlib version rather than left
    // at the previous minimum needed to just compile, so the toolchain
    // itself isn't older than a dependency it ships.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
