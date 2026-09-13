#!/usr/bin/env bash
#
# Fetch pinned GNU MPFR + GMP into src/ and gmp/ (gitignored).
# Pure download + extract — no build, no install. Run from anywhere; resolves paths relative to itself.
#
# To upgrade: bump MPFR_VERSION / GMP_VERSION below, then rerun.

set -euo pipefail

# ---- Pinned versions ----
readonly MPFR_VERSION="${MPFR_VERSION:-4.2.1}"
readonly GMP_VERSION="${GMP_VERSION:-6.3.0}"

readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SRC_DIR="$HERE/src"
readonly GMP_DIR="$HERE/gmp"
readonly BUILD_DIR="$HERE/build"
readonly INSTALL_DIR="$HERE/install"

# ---- Tarball URLs (HTTPS, pinned checksums below) ----
MPFR_TARBALL="mpfr-${MPFR_VERSION}.tar.xz"
GMP_TARBALL="gmp-${GMP_VERSION}.tar.xz"

MPFR_URL="https://www.mpfr.org/mpfr-${MPFR_VERSION}/${MPFR_TARBALL}"
GMP_URL="https://ftp.gnu.org/gnu/gmp/${GMP_TARBALL}"

# SHA256 from upstream release pages (verified at version-bump time).
readonly MPFR_SHA256="277807353a6726978996945af13e52829e3abd7a9a5b7fb2793894e18f1fcbb2"
readonly GMP_SHA256="a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898"

mkdir -p "$SRC_DIR" "$GMP_DIR" "$BUILD_DIR" "$INSTALL_DIR"

fetch_and_extract() {
    local url="$1"
    local tarball="$2"
    local target_dir="$3"
    local expected_sha="$4"
    local sentinel="${tarball}.sha256-verified"

    # The sentinel marks a previous verification; it NEVER authorizes skipping
    # the hash step. Tarballs are committed in this repo (git checkout is not a
    # download cache) and any on-disk copy can be corrupted or swapped, so the
    # bytes present are re-hashed on every run and mismatch is fatal.
    if [[ ! -f "$tarball" || ! -s "$tarball" ]]; then
        echo ">> Downloading $url"
        curl -fL --retry 3 -o "$tarball" "$url"
    fi

    echo ">> Verifying SHA256"
    local actual_sha
    actual_sha="$(sha256sum "$tarball" | awk '{print $1}')"
    if [[ "$actual_sha" != "$expected_sha" ]]; then
        echo "ERROR: SHA256 mismatch for $tarball" >&2
        echo "  expected: $expected_sha" >&2
        echo "  actual:   $actual_sha" >&2
        echo "  the local copy was deleted; re-check or bump version in fetch.sh " >&2
        echo "  only after confirming upstream re-released" >&2
        rm -f "$tarball" "$sentinel"
        exit 1
    fi

    echo "$expected_sha" > "$sentinel"

    echo ">> Extracting $tarball -> $target_dir"
    mkdir -p "$target_dir"
    tar -xJf "$tarball" -C "$target_dir" --strip-components=1
}

fetch_and_extract "$MPFR_URL" "$MPFR_TARBALL" "$SRC_DIR" "$MPFR_SHA256"
fetch_and_extract "$GMP_URL"  "$GMP_TARBALL"  "$GMP_DIR" "$GMP_SHA256"

echo
echo "Done."
echo "  MPFR source: $SRC_DIR (version $MPFR_VERSION)"
echo "  GMP  source: $GMP_DIR (version $GMP_VERSION)"
echo
echo "Next: build + install into $INSTALL_DIR, see README.md."
