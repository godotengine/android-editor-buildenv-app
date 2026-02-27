#!/bin/bash

ANDROID_API="${ANDROID_API:-32}"
HOST_TAG="${HOST_TAG:-linux-x86_64}"
TARGET_ARCH="${TARGET_ARCH:-arm64-v8a}"
TARGET_PREFIX="${TARGET_PREFIX:-aarch64}"

TALLOC_VERSION="${TALLOC_VERSION:-2.4.3}"
TALLOC_SHA256="${TALLOC_SHA256:-dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd}"

if [ -z "$ANDROID_NDK_ROOT" ]; then
	echo "ERROR: The ANDROID_NDK_ROOT environment variable must be defined" > /dev/stderr
	exit 1
fi

ANDROID_TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"

if ! [ -e "$ANDROID_TOOLCHAIN" ]; then
	echo "ERROR: Unable to find Android toolchain at ${ANDROID_TOOLCHAIN}" > /dev/stderr
	echo "       Maybe set the HOST_TAG variable to match your system? Current value is '${HOST_TAG}'" > /dev/stderr
	exit 1
fi

ANDROID_CC="$ANDROID_TOOLCHAIN/${TARGET_PREFIX}-linux-android${ANDROID_API}-clang"

if ! [ -e "$ANDROID_CC" ]; then
	echo "ERROR: Unable to find Android C compiler at ${ANDROID_CC}" > /dev/stderr
	exit 1
fi

ANDROID_AR="$ANDROID_TOOLCHAIN/llvm-ar"

if ! [ -e "$ANDROID_AR" ]; then
	echo "ERROR: Unable to find Android AR at ${ANDROID_AR}" > /dev/stderr
	exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROOT_DIR="$SCRIPT_DIR/proot-source"
BUILD_DIR="$SCRIPT_DIR/build"

function die() {
    echo "$@" > /dev/stderr
    exit 1
}

##
## TALLOC
##

TALLOC_DIR="$BUILD_DIR/talloc"
TALLOC_SOURCE_DIR="$BUILD_DIR/talloc/source"
mkdir -p "$TALLOC_DIR"

if ! [ -e $TALLOC_SOURCE_DIR ]; then
    TALLOC_ARCHIVE="$TALLOC_DIR/talloc.tar.gz"

    wget -O "$TALLOC_ARCHIVE" "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" \
        || die "Unable to download talloc source"
    TALLOC_SHA256_TEST=$(sha256sum "$TALLOC_ARCHIVE" | cut -d' ' -f1)

    if [ "$TALLOC_SHA256" != "$TALLOC_SHA256_TEST" ]; then
        die "ERROR: Hash of talloc.tar.gz doesn't match the expected value. Check the TALLOC_SHA256 variable."
    fi

    mkdir -p "$TALLOC_SOURCE_DIR" && tar -xvf "$TALLOC_ARCHIVE" --strip-components=1 --directory="$TALLOC_SOURCE_DIR" \
        || (rm -rf "$TALLOC_SOURCE_DIR" && die "Unable to extract talloc source")
fi

pushd "$TALLOC_SOURCE_DIR" > /dev/null
make distclean || true
CC="$ANDROID_CC" ./configure build --disable-rpath --disable-python --cross-compile --cross-answers="$SCRIPT_DIR/talloc-answers.txt" \
    || die "Unable to build talloc"
mkdir -p "$TALLOC_DIR/lib"
"$ANDROID_AR" rcs "$TALLOC_DIR/lib/libtalloc.a" bin/default/talloc*.o \
    || die "Unable to create libtalloc.a"
mkdir -p "$TALLOC_DIR/include"
cp talloc.h "$TALLOC_DIR/include/" \
    || die "Unable to copy talloc.h"
popd > /dev/null

##
## PROOT
##

(cd "$PROOT_DIR" && git checkout -- . && patch -p1 < "$SCRIPT_DIR/patches/string-header.patch") \
    || die "Unable to patch proot"

CPPFLAGS="-I$TALLOC_DIR/include" LDFLAGS="-L$TALLOC_DIR/lib" make -C "$PROOT_DIR/src" CC="$ANDROID_CC" CROSS_COMPILE="$ANDROID_TOOLCHAIN/llvm-" PROOT_UNBUNDLE_LOADER=1 HAS_LOADER_32BIT=1 V=1 proot \
    || die "Unable to build proot"

mkdir -p "$BUILD_DIR/bin/$TARGET_ARCH"

cp "$PROOT_DIR/src/loader/loader" "$BUILD_DIR/bin/$TARGET_ARCH/libproot-loader.so" \
    || die "Unable to copy loader"
cp "$PROOT_DIR/src/loader/loader-m32" "$BUILD_DIR/bin/$TARGET_ARCH/libproot-loader32.so" \
    || die "Unable to copy loader32"
cp "$PROOT_DIR/src/proot" "$BUILD_DIR/bin/$TARGET_ARCH/libproot.so" \
    || die "Unable to copy proot"
