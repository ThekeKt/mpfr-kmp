# Third-Party Licenses

This project depends on third-party native libraries distributed under the
GNU Lesser General Public License v3.0 or later (LGPL-3.0-or-later). The
Kotlin/Java wrapper code in this project is licensed under the Apache License
2.0 (`LICENSE`); the LGPL libraries below are **dynamically linked** at
runtime and their licenses are reproduced in full here for compliance.

Distribution pattern: see `research/kmp-native-lib-distribution-patterns.md`
and `research/mpfr-licensing.md`.

---

## GNU MPFR — Multiple Precision Floating-Point Reliable

- Version pinned: **4.2.1**
- **SPDX-License-Identifier**: `LGPL-3.0-or-later`
- **Full license text**: <https://www.gnu.org/licenses/lgpl-3.0.txt>
  (also present verbatim in `third_party/mpfr/src/COPYING.LESSER`).
- Source: <https://www.mpfr.org/mpfr-4.2.1/>
- Upstream COPYING: `third_party/mpfr/src/COPYING` (GPL-2.0 fallback) and
  `third_party/mpfr/src/COPYING.LESSER`.
- Copyright: Free Software Foundation, Inc.; the MPFR team (Guillaume Hanrot,
  Vincent Lefèvre, Patrick Pélissier, Philippe Théveny, Paul Zimmermann, and
  contributors). See `third_party/mpfr/src/AUTHORS`.

---

## GNU GMP — GNU Multiple Precision Arithmetic Library

- Version pinned: **6.3.0**
- **SPDX-License-Identifier**: `LGPL-3.0-or-later OR GPL-2.0-or-later`
  (dual-licensed; we elect the LGPL-3.0 terms).
- **Full LGPL-3.0 text**: <https://www.gnu.org/licenses/lgpl-3.0.txt>
  (also present verbatim in `third_party/mpfr/gmp/COPYING.LESSER`).
- **Full GPL-2.0 text**: <https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt>
  (also present verbatim in `third_party/mpfr/gmp/COPYING`).
- Source: <https://gmplib.org/> (mirror: <https://ftp.gnu.org/gnu/gmp/gmp-6.3.0.tar.xz>)
- Copyright: Free Software Foundation, Inc.; the GMP project contributors.
  See `third_party/mpfr/gmp/AUTHORS`.

---

## Summary of compliance actions performed

1. **Source available**: full MPFR 4.2.1 and GMP 6.3.0 sources are bundled in
   `third_party/mpfr/src/` and `third_party/mpfr/gmp/` (gitignored; produced
   by `third_party/mpfr/fetch.sh`). Consumers can rebuild from these.
2. **Dynamic linking**: `libmpfr.{so,dylib,dll}` and `libgmp.{so,dylib,dll}`
   are dynamically loaded at runtime; this is the LGPL-3.0 § 4d1 mechanism.
3. **Notice**: this file + `NOTICE` provide LGPL attribution.
4. **LGPL-3.0 § 4e Installation Information**: N/A for apps distributed
   via standard app stores / package managers (which permit user-side library
   replacement); will be revisited if embedded-device distribution is added.

The full legal analysis is maintained privately by the project owners; the
summary above records the operative conclusions.
