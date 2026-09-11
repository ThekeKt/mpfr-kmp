# third_party/mpfr — pinned upstream sources

Pinned versions of GNU MPFR and GMP, fetched by `fetch.sh`. The actual extracted
source trees (`src/`, `gmp/`, `build/`, `install/`) are gitignored — they are
build inputs, not artifacts of this repository.

## Pinned versions

| Library | Version | License   | URL                                                                |
|---------|---------|-----------|--------------------------------------------------------------------|
| MPFR    | 4.2.1   | LGPL 3.0+ | https://www.mpfr.org/mpfr-4.2.1/                                   |
| GMP     | 6.3.0   | LGPL 3.0+ | https://gmplib.org/#DOWNLOAD (mirror: https://ftp.gnu.org/gnu/gmp/) |

To change the version, edit `fetch.sh` (the `MPFR_VERSION` / `GMP_VERSION` variables)
and re-run it. Update `THIRD_PARTY_LICENSES.md` accordingly.

## Consumer install (developer / CI)

```bash
cd third_party/mpfr
bash fetch.sh                              # downloads + extracts into src/ and gmp/
cd src && autoreconf -i && ./configure --prefix="$PWD/../install" --with-gmp="$PWD/../gmp"
make -j"$(nproc)" && make check && make install
```

On Windows, prefer an MSYS2 (MinGW-w64 / UCRT64) shell; the sequence above
applies unchanged — point `--prefix` at `third_party/mpfr/install`.

The Kotlin native target links against the installed library under `install/`,
which exposes `mpfr.h` and `libmpfr.{so,dylib,lib,a}` for `cinterop`.
