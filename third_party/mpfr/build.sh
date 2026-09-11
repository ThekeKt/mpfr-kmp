#!/usr/bin/env bash
#
# Build MPFR + GMP from source into $HERE/install.
# Linux-only (Docker / WSL2 / native Linux). macOS users: replace `nproc`
# with `sysctl -n hw.ncpu`.
#
# Idempotent: re-running skips already-fetched tarballs (fetch.sh handles).

set -euo pipefail

readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly INSTALL_DIR="$HERE/install"

# Number of parallel make jobs. Linux-only.
if command -v nproc >/dev/null 2>&1; then
  JOBS="$(nproc)"
else
  JOBS=1
fi

echo "== Step 1: fetch source tarballs (idempotent) =="
cd "$HERE"
bash fetch.sh

echo "== Step 2: configure GMP =="
cd "$HERE/gmp"
./configure \
  --prefix="$INSTALL_DIR" \
  --enable-shared \
  --disable-static \
  --with-pic

echo "== Step 3: build + check + install GMP =="
make -j"$JOBS"
make check
make install

echo "== Step 4: configure MPFR =="
cd "$HERE/src"
./configure \
  --prefix="$INSTALL_DIR" \
  --with-gmp="$INSTALL_DIR" \
  --enable-shared \
  --disable-static \
  --enable-thread-safe

echo "== Step 5: build + check + install MPFR =="
make -j"$JOBS"
make check
make install

echo
echo "== Done =="
echo "MPFR library: $INSTALL_DIR/lib/libmpfr.so (or .dylib/.a)"
echo "MPFR header:  $INSTALL_DIR/include/mpfr.h"
echo
ls -la "$INSTALL_DIR/lib/" 2>/dev/null || true
