plugins {
    // 8.13 for compileSdk/targetSdk 36 (Google Play's Android 16 requirement).
    id("com.android.application") version "8.13.2" apply false
    // Matches the kotlin-stdlib 2.2.10 that MapLibre 13.6.1 brings in: an
    // older compiler can't read its class metadata.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
