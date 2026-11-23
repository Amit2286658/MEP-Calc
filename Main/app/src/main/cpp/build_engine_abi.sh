#!/bin/sh
# =============================================================================
# build_engine_abi.sh — build libmathengine.so for one or more Android ABIs
# =============================================================================
# The engine always boots blank; hosts load precompiled/stdlib.mathc separately.
# Output: app/src/main/jniLibs/<abi>/libmathengine.so so a single APK can ship
# a real engine for every ABI it advertises (no "installs then crashes on
# 32-bit / x86 devices" store-review failures).
#
# Usage:
#   sh build_engine_abi.sh [<ndk-toolchain-bin>] [abi ...]
#
#   ndk-toolchain-bin : .../ndk/<ver>/toolchains/llvm/prebuilt/<host>/bin
#                       (autodetected from ANDROID_NDK_* / ANDROID_HOME when omitted)
#   abi               : arm64-v8a (default) | armeabi-v7a | x86_64 | x86
#
# Examples:
#   sh build_engine_abi.sh "" arm64-v8a armeabi-v7a x86_64
#   sh build_engine_abi.sh /opt/android_sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin x86_64
# =============================================================================
set -eu

API=24

if [ $# -gt 0 ]; then
    NDK_BIN="$1"
    shift
fi
[ $# -gt 0 ] || set -- arm64-v8a

if [ -z "${NDK_BIN:-}" ]; then
    for cand in \
        "${ANDROID_NDK_HOME:-}/toolchains/llvm/prebuilt/"*/bin \
        "${ANDROID_NDK_ROOT:-}/toolchains/llvm/prebuilt/"*/bin \
        "${ANDROID_HOME:-}/ndk/"*/toolchains/llvm/prebuilt/*/bin \
        "${ANDROID_SDK_ROOT:-}/ndk/"*/toolchains/llvm/prebuilt/*/bin \
        /opt/android_sdk/ndk/*/toolchains/llvm/prebuilt/*/bin
    do
        if [ -n "$cand" ] && [ -x "$cand/aarch64-linux-android${API}-clang" ]; then
            NDK_BIN="$cand"
            break
        fi
    done
fi
[ -n "${NDK_BIN:-}" ] || {
    echo "error: cannot locate NDK toolchain bin (pass it as the first argument or set ANDROID_NDK_HOME)" >&2
    exit 1
}

HERE="$(cd "$(dirname "$0")" && pwd)"
ENGINE="$HERE/mathengine"
JNILIBS="$(cd "$HERE/.." && pwd)/jniLibs"
[ -d "$ENGINE/Core" ] || { echo "error: vendored engine not found at $ENGINE" >&2; exit 1; }

CFLAGS="--sysroot=$NDK_BIN/../sysroot -O2 -std=c11 -fPIC -fvisibility=hidden -fstack-protector-strong \
 -DANDROID -DMATH_ENGINE_SHARED=1 -D_FORTIFY_SOURCE=2 -ffunction-sections -fdata-sections"
INCS="-I$ENGINE/Core/Headers -I$ENGINE/Core/compile -I$ENGINE/Core/jni -I$ENGINE/Core/lib/delegate -I$ENGINE/Core/lib/hardcode"
LDFLAGS_COMMON="-shared -Wl,-soname,libmathengine.so -Wl,--gc-sections"

echo "using NDK toolchain: $NDK_BIN"

for ABI in "$@"; do
    case "$ABI" in
        arm64-v8a)   TRIPLE=aarch64-linux-android;      PAGE=16384 ;;
        armeabi-v7a) TRIPLE=armv7a-linux-androideabi;   PAGE=4096  ;;
        x86_64)      TRIPLE=x86_64-linux-android;       PAGE=16384 ;;
        x86)         TRIPLE=i686-linux-android;         PAGE=4096  ;;
        *) echo "error: unsupported ABI '$ABI'" >&2; exit 1 ;;
    esac

    CC="$NDK_BIN/${TRIPLE}${API}-clang"
    [ -x "$CC" ] || { echo "error: missing compiler $CC" >&2; exit 1; }

    OUT="$JNILIBS/$ABI"
    TMP="$ENGINE/.build-$ABI"
    rm -rf "$TMP"
    mkdir -p "$TMP" "$OUT"

    echo "== $ABI ($(basename "$CC"))"
    OBJS=""
    i=0
    for src in \
        "$ENGINE/Core/Code/"*.c \
        "$ENGINE/Core/compile/"*.c \
        "$ENGINE/Core/jni/"*.c \
        "$ENGINE/Core/lib/delegate/"*.c \
        "$ENGINE/Core/lib/hardcode/"*.c
    do
        i=$((i + 1))
        obj="$TMP/obj_$(printf '%02d' "$i")_$(basename "${src%.c}").o"
        # shellcheck disable=SC2086
        "$CC" $CFLAGS $INCS -c "$src" -o "$obj"
        OBJS="$OBJS $obj"
    done

    # shellcheck disable=SC2086
    "$CC" $LDFLAGS_COMMON -Wl,-z,max-page-size="$PAGE" \
        -o "$TMP/libmathengine.so" $OBJS -lm -ldl -lc
    "$NDK_BIN/llvm-strip" "$TMP/libmathengine.so"
    cp "$TMP/libmathengine.so" "$OUT/libmathengine.so"
    rm -rf "$TMP"
    echo "built: $OUT/libmathengine.so ($(ls -lh "$OUT/libmathengine.so" | awk '{print $5}'))"
done
