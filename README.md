# ThekeKt — MPFR for Kotlin Multiplatform

A Kotlin Multiplatform wrapper around [GNU MPFR](https://www.mpfr.org/) —
arbitrary-precision, correctly-rounded floating-point arithmetic — for JVM and
Android today, with Kotlin/Native, JS, and Wasm targets planned.

**Status: under development — not published to any package registry yet.**
The v0.0.1 API subset (`MpfrFloat`, `MpfrPrecision`, rounding modes, exception
flags, decimal/hex parsing, BigInteger crossings, a generated JNI shim) lives in
`library/` with its tests.

- Contributor and coding-agent guidelines: [`AGENTS.md`](AGENTS.md)
- Licensing (Apache-2.0 wrapper; LGPL-3.0 MPFR/GMP, dynamically linked):
  [`LICENSE`](LICENSE), [`NOTICE`](NOTICE), [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)
- MPFR reference manual: <https://www.mpfr.org/mpfr-current/mpfr.html>
