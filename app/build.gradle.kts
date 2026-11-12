import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is optional at build time: `./gradlew assembleDebug` and
// `test` work with no keystore.properties present at all. Only
// `assembleRelease` needs it, and fails clearly (missing signingConfig) if
// it's absent rather than silently falling back to the debug key -- a
// release APK signed with the debug key would be indistinguishable from a
// throwaway test build. See BUILD_AND_TEST.md for how to generate one.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "au.opendepth.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "au.opendepth.app"
        minSdk = 26
        // Google Play requires apps and updates to target Android 16 (API 36)
        // from 31 August 2026.
        targetSdk = 36
        versionCode = 50
        versionName = "1.1.2"
    }
    // Two builds of the same app. "github" keeps the original application id,
    // so the APKs already installed from GitHub keep updating. "play" is the
    // Google Play listing, under SeaBathy's own id (it can never change once
    // published there).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
        }
        create("play") {
            dimension = "store"
            applicationId = "com.seabathy.app"
        }
    }
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // The build to put on your own phone: as fast as release (not
        // debuggable, no leak checker, no StrictMode or resource logging),
        // but signed with this computer's debug key, so it installs over the
        // debug builds already on the phone and keeps their layers, waypoints
        // and settings. It is not for publishing: that is `release`, with its
        // own key.
        create("phone") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = false
        // For BuildConfig.DEBUG, gating StrictMode setup (see MainActivity) to debug builds only.
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    // The small native GDAL plugin that reads picked files in place
    // (src/main/cpp). NDK r28 builds 16 KB-aligned libraries by default.
    ndkVersion = "28.1.13356709"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // Native libraries stay uncompressed and 16 KB-aligned in the APK and
    // bundle, as Google Play requires for Android 15+ devices.
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
    // The release deliverable is a plain SeaBathy.apk (versioning lives in
    // versionName/versionCode, not the filename).
    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "SeaBathy.apk"
            }
        }
    }
}
dependencies {
    // 13.1.0 or newer: earlier versions overflow the stack in
    // TileSet.setBounds(), which attachGdalOnDemand calls. Uses the "-opengl" artifact because
    // the plain "android-sdk" artifact defaults to Vulkan from v13.0.0;
    // this keeps rendering on OpenGL ES.
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.10.1")
    // Pure-Java TIFF reader: no native GDAL dependency, and it exposes the
    // GeoTIFF tags needed to georeference an imported raster.
    implementation("mil.nga:tiff:3.0.0")
    // Native GDAL, for BigTIFF / files too large for the Kotlin decode path.
    // No Maven/JitPack coordinates are published for this project (see
    // https://github.com/kikitte/GDAL4Android) -- the AAR is downloaded by
    // hand and vendored at app/libs/gdal-3.7.0.aar: built from GDAL4Android
    // commit efebe20 ("Enable 16kb page size support") with its
    // docker/Dockerfile_16kb (Android NDK r28), so every library is 16 KB-aligned
    // as Google Play requires.
    // It ships native libs for arm64-v8a and x86_64 only (not armeabi-v7a or
    // x86, both of which MapLibre above still covers), so the GDAL import
    // path is only reachable on those two architectures; see GdalSupport.kt.
    implementation(files("libs/gdal-3.7.0.aar"))
    // Serves both MBTiles and on-demand GDAL-rendered tiles to MapLibre over
    // loopback HTTP -- see LocalTileServer's doc for why a local server is
    // needed (MapLibre's native "mbtiles://" scheme has no callback into app
    // code for a cache-miss, so it cannot render GDAL tiles on demand).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // HTTP client for XYZ tile downloads (download/TileFetchClient.kt).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Debug-build-only leak detector; installs itself via its own
    // ContentProvider. Never present in a release build.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}
