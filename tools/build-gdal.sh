#!/usr/bin/env bash
# Builds the app's GDAL library from source: app/libs/gdal-3.7.0.aar
# (GDAL 3.7.0 with its Java bindings, PROJ 9.2.1, SQLite 3.42.0, Expat 2.5.0,
# GNU libiconv 1.17) for arm64-v8a and x86_64, plus PROJ's database,
# app/src/main/assets/proj/proj.db.
#
# The repository ships these prebuilt; F-Droid runs this script to build them
# from source instead. It needs Linux (x86_64) with: Android NDK r28
# (28.1.13356709, which aligns libraries to 16 KB), a JDK, cmake (3.16+),
# make, swig, ant, sqlite3, curl and unzip.
#
# Usage: tools/build-gdal.sh <path to the Android NDK>
# Run from anywhere; set JOBS to limit parallel jobs (default: all CPUs).
set -euo pipefail

NDK="${1:?usage: tools/build-gdal.sh <android-ndk-dir>}"
NDK="$(cd "$NDK" && pwd)"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$REPO/build/gdal-source"
API=21
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN)}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
export JAVA_HOME

for tool in cmake make swig ant sqlite3 curl javac jar; do
    command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done
[ -x "$TOOLCHAIN/bin/clang" ] || { echo "not an Android NDK: $NDK" >&2; exit 1; }

# name|url|sha256
SOURCES=(
    "libiconv-1.17|https://ftp.gnu.org/pub/gnu/libiconv/libiconv-1.17.tar.gz|8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313"
    "sqlite-autoconf-3420000|https://www.sqlite.org/2023/sqlite-autoconf-3420000.tar.gz|7abcfd161c6e2742ca5c6c0895d1f853c940f203304a0b49da4e1eca5d088ca6"
    "expat-2.5.0|https://github.com/libexpat/libexpat/releases/download/R_2_5_0/expat-2.5.0.tar.gz|6b902ab103843592be5e99504f846ec109c1abb692e85347587f237a4ffa1033"
    "proj-9.2.1|https://github.com/OSGeo/PROJ/releases/download/9.2.1/proj-9.2.1.tar.gz|15ebf4afa8744b9e6fccb5d571fc9f338dc3adcf99907d9e62d1af815d4971a1"
    "gdal-3.7.0|https://github.com/OSGeo/gdal/releases/download/v3.7.0/gdal-3.7.0.tar.gz|5a806d759f403a15bbbf8a14ecc6947071afc5ab91e5abaef0d11d1d2d16bf94"
)

mkdir -p "$WORK/download"
for entry in "${SOURCES[@]}"; do
    IFS='|' read -r name url sha <<<"$entry"
    tarball="$WORK/download/$name.tar.gz"
    if [ ! -f "$tarball" ] || ! echo "$sha  $tarball" | sha256sum -c --status; then
        curl -fL --retry 3 -o "$tarball" "$url"
    fi
    echo "$sha  $tarball" | sha256sum -c -
done

COMMON_FLAGS="-O2 -g0 -fPIC"

build_abi() {
    local abi="$1" triple="$2"
    local src="$WORK/src/$abi" out="$WORK/install/$abi"
    echo "=== $abi"
    rm -rf "$src" "$out"
    mkdir -p "$src" "$out"
    for entry in "${SOURCES[@]}"; do
        IFS='|' read -r name _ _ <<<"$entry"
        tar -xzf "$WORK/download/$name.tar.gz" -C "$src"
    done
    # GDAL 3.7 compiles its Java bindings for Java 7, which JDK 21 no longer
    # targets; Java 8 bytecode works the same on Android.
    sed -i 's/source="7" target="7"/source="8" target="8"/' "$src/gdal-3.7.0/swig/java/build.xml"

    export CC="$TOOLCHAIN/bin/$triple$API-clang"
    export CXX="$TOOLCHAIN/bin/$triple$API-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" STRIP="$TOOLCHAIN/bin/llvm-strip"
    local cross=(
        -DCMAKE_SYSTEM_NAME=Android -DCMAKE_SYSTEM_VERSION=$API
        -DCMAKE_ANDROID_NDK="$NDK" -DCMAKE_ANDROID_ARCH_ABI="$abi"
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$out"
        "-DCMAKE_PREFIX_PATH=$out"
        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=NEVER -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=NEVER
        -DCMAKE_FIND_USE_CMAKE_SYSTEM_PATH=NO
    )

    # Autotools libraries: iconv, SQLite, Expat.
    local lib
    for lib in libiconv-1.17 sqlite-autoconf-3420000 expat-2.5.0; do
        mkdir -p "$src/$lib/_build"
        (cd "$src/$lib/_build" &&
            ../configure --host="$triple" --prefix="$out" --disable-static \
                CFLAGS="$COMMON_FLAGS -Wno-int-conversion" CXXFLAGS="$COMMON_FLAGS" >/dev/null &&
            make -j"$JOBS" >/dev/null && make install >/dev/null)
    done

    cmake -S "$src/proj-9.2.1" -B "$src/proj-9.2.1/_build" "${cross[@]}" \
        -DENABLE_TIFF=OFF -DENABLE_CURL=OFF -DBUILD_APPS=OFF -DBUILD_TESTING=OFF \
        -DEXE_SQLITE3="$(command -v sqlite3)" >/dev/null
    cmake --build "$src/proj-9.2.1/_build" --parallel "$JOBS" --target install >/dev/null

    # The JNI headers come from the build JDK. The bindings never link
    # against the JVM, so its library paths only satisfy CMake's FindJNI.
    cmake -S "$src/gdal-3.7.0" -B "$src/gdal-3.7.0/_build" "${cross[@]}" \
        -DGDAL_USE_EXTERNAL_LIBS=OFF -DGDAL_BUILD_OPTIONAL_DRIVERS=OFF -DOGR_BUILD_OPTIONAL_DRIVERS=OFF \
        -DGDAL_USE_SQLITE3=ON -DGDAL_USE_EXPAT=ON -DGDAL_USE_ICONV=ON \
        -DBUILD_JAVA_BINDINGS=ON -DBUILD_PYTHON_BINDINGS=OFF -DBUILD_CSHARP_BINDINGS=OFF \
        -DGDAL_JAVA_GENERATE_JAVADOC=OFF -DBUILD_TESTING=OFF -DBUILD_APPS=OFF \
        -DSFCGAL_CONFIG=disabled -DHDF5_C_COMPILER_EXECUTABLE=disabled -DHDF5_CXX_COMPILER_EXECUTABLE=disabled \
        -DJAVA_HOME="$JAVA_HOME" \
        -DJAVA_INCLUDE_PATH="$JAVA_HOME/include" -DJAVA_INCLUDE_PATH2="$JAVA_HOME/include/linux" \
        -DJAVA_AWT_INCLUDE_PATH="$JAVA_HOME/include" \
        -DJAVA_AWT_LIBRARY="$JAVA_HOME/lib/libjawt.so" -DJAVA_JVM_LIBRARY="$JAVA_HOME/lib/server/libjvm.so" \
        >/dev/null
    cmake --build "$src/gdal-3.7.0/_build" --parallel "$JOBS" --target install >/dev/null

    mkdir -p "$WORK/aar/jni/$abi"
    cp -L "$out"/lib/libiconv.so "$out"/lib/libcharset.so "$out"/lib/libsqlite3.so \
        "$out"/lib/libexpat.so "$out"/lib/libproj.so "$out"/lib/libgdal.so \
        "$out"/share/java/libgdalalljni.so "$WORK/aar/jni/$abi/"
    "$STRIP" --strip-unneeded "$WORK/aar/jni/$abi/"*.so
}

rm -rf "$WORK/aar"
build_abi arm64-v8a aarch64-linux-android
build_abi x86_64 x86_64-linux-android

# Package the AAR the app depends on (app/build.gradle.kts).
AAR="$WORK/aar"
mkdir -p "$AAR/libs" "$WORK/empty"
cp "$WORK/install/arm64-v8a/share/java/gdal-3.7.0.jar" "$AAR/libs/"
cat >"$AAR/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.gdal.android">
    <uses-sdk android:minSdkVersion="21" />
</manifest>
EOF
: >"$AAR/R.txt"
: >"$AAR/proguard.txt"
(cd "$WORK/empty" && jar cfM "$AAR/classes.jar" .)
rm -f "$REPO/app/libs/gdal-3.7.0.aar"
mkdir -p "$REPO/app/libs"
(cd "$AAR" && jar cfM "$REPO/app/libs/gdal-3.7.0.aar" AndroidManifest.xml R.txt proguard.txt classes.jar libs jni)

# PROJ's database, generated from PROJ's SQL sources by the build above.
mkdir -p "$REPO/app/src/main/assets/proj"
cp "$WORK/install/arm64-v8a/share/proj/proj.db" "$REPO/app/src/main/assets/proj/proj.db"

echo "built app/libs/gdal-3.7.0.aar and app/src/main/assets/proj/proj.db"
