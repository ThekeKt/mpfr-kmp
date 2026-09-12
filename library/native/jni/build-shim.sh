#!/usr/bin/env bash
# Build the `mpfr_kmp` JNI shim against the vendored MPFR/GMP install tree
# (third_party/mpfr/install/ — produced by third_party/mpfr/build.sh / docker compose).
#
# Usage (Linux/WSL):  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./build-shim.sh [<output-dir>]
# Output:  <output-dir>/libmpfr_kmp.so  (default library/native/build)
#
# The Windows host path (MSYS2 UCRT64) follows the same shape with gcc against
# the install tree rebuilt as DLLs (tracked as a separate packaging task);
# CMakeLists.txt in this directory covers IDE/CI consumers.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
OUT="${1:-$HERE/../build}"
PREFIX="$REPO/third_party/mpfr/install"

: "${JAVA_HOME:?set JAVA_HOME to a JDK home (for jni.h / jni_md.h)}"
[ -f "$PREFIX/include/mpfr.h" ] || { echo "missing $PREFIX/include/mpfr.h — run the vendored MPFR build first"; exit 1; }

mkdir -p "$OUT"
case "$(uname -s)" in
    Linux)       MDINC="$JAVA_HOME/include/linux";  LIBFILE="libmpfr_kmp.so";  LIBFLAG="-shared";;
    MINGW*|MSYS*) MDINC="$JAVA_HOME/include/win32"; LIBFILE="mpfr_kmp.dll";    LIBFLAG="-shared";;
    Darwin)      MDINC="$JAVA_HOME/include/darwin"; LIBFILE="libmpfr_kmp.dylib"; LIBFLAG="-dynamiclib";;
    *) echo "unsupported host $(uname -s)"; exit 1;;
esac

gcc -std=c11 -O2 -fPIC $LIBFLAG \
    -I"$HERE" -I"$PREFIX/include" -I"$JAVA_HOME/include" -I"$MDINC" \
    -o "$OUT/$LIBFILE" "$HERE/mpfr_kmp_jni.c" \
    -L"$PREFIX/lib" -lmpfr -lgmp -lpthread

echo "built: $OUT/$LIBFILE"
