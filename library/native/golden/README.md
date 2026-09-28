# Reference-vector capture harness

`golden_capture.c` generates the TSV reference-vector fixtures consumed by the
JVM test tier (`library/src/jvmTest/resources/golden/`). It links against the
pinned MPFR build in `third_party/mpfr/install/` and calls MPFR directly with
the same call sequences the JNI shim families use, so every emitted row is an
exact oracle for the wrapper call under test.

## Build and run (Linux/WSL, from the repository root)

```bash
gcc -O2 -Wall -I third_party/mpfr/install/include \
    library/native/golden/golden_capture.c \
    -o /tmp/golden_capture -L third_party/mpfr/install/lib -lmpfr -lgmp

LD_LIBRARY_PATH=third_party/mpfr/install/lib /tmp/golden_capture <outdir>
```

`<outdir>` must exist. The harness writes one TSV per family plus
`manifest.tsv`, and prints per-file row counts (the current total is the
generated row count recorded in the manifest).

## Output contract

- Values are emitted as C99-style hex floats (`[-]0x1.<hex>p<exp>`, exact at
  the row's precision) or as special tags (`@nan@`, `@inf+`, `@inf-`,
  `@zero+`, `@zero-`). Every emitted literal is re-parsed and compared bit for
  bit at the same precision before it is written out; a mismatch aborts.
- Rounding modes are the single letters `N/Z/U/D/A` (the wrapper's
  `RoundingMode.toString()` labels, matching the MPFR numeric order).
- `flags` is the decimal bitmask of the exception-flag delta of the operation
  (1 underflow, 2 overflow, 4 NaN, 8 inexact, 16 ERANGE, 32 DIVBY0). The
  wrapper discards this delta by design; the column is provenance.
- `manifest.tsv` carries `schema_version`, the runtime `mpfr_version`, and per
  file the row count and SHA-256 of that file's bytes (self-contained SHA-256;
  no external crypto dependency). The fixture loader verifies all of it.
- Row ids `<FAMILY>-<six-digit>` are stable in generation order and appear in
  test failure messages.
- The capture is deterministic: fixed matrix, C locale, no timestamps, no
  randomness. Running the harness twice must produce byte-identical output
  (`diff -r out1 out2`).

## Domain notes

- `mpfr_dot` rows whose operand exponent sums would leave the MPFR exponent
  range are emitted as expected-`IllegalArgumentException` rows and never call
  `mpfr_dot` (upstream does not handle intermediate over/underflow).
  Boundary rows just inside and just outside both edges are included.
- Huge-magnitude special inputs (`@maxfin@`) are excluded for sine/cosine/
  tangent and their pi-scaled variants, for the gamma family, and for the zeta
  function: argument reduction for values with astronomically large exponents
  is prohibitively expensive. Moderate large arguments are covered by the
  `GRID` rows instead.
- Unary rows apply the same pre-copy (`mpfr_set(rop, x)`) the shim performs
  before dispatching, so `nextabove`/`nextbelow` rows are meaningful.

## Regenerating after a pin bump

1. Update the pin and rebuild the install tree
   (`third_party/mpfr/fetch.sh`, `third_party/mpfr/build.sh`).
2. Rebuild and rerun the harness over
   `library/src/jvmTest/resources/golden/` (or a temp directory first).
3. Commit the regenerated fixtures in the same commit as the pin bump.
   The verification workflow re-runs the capture on CI and byte-compares it
   with the committed fixtures, so a stale fixture set fails the build.
4. If the pin's `mpfr_version()` differs, the loader test fails until the
   manifest (regenerated) matches again.

## License

The harness is part of the wrapper artifact set and is distributed under the
project `LICENSE` (Apache-2.0). The fixtures are machine-generated facts about
the pinned LGPL build; see `THIRD_PARTY_LICENSES.md`.