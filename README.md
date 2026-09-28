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

## Parse/format locale independence

`MpfrFloat.parse`, `toString` and `format` always use `.` as the decimal
separator, regardless of the host process's locale settings. The native bridge
pins a per-thread C numeric locale around each conversion (`uselocale`,
POSIX.1-2008) and restores the thread's previous locale afterwards; the host
application's global locale state is never modified.
