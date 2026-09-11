# AGENTS.md

> Notes for any coding agent (human or AI) reading this repository. Describes what the
> library is, the high-level structure, and the conventions any contributor or consumer
> should follow. Internal working notes are deliberately omitted — this file is part
> of the public artifact set.

## What this project is

A Kotlin Multiplatform library that wraps [GNU MPFR](https://www.mpfr.org/) — the
canonical arbitrary-precision floating-point library — and exposes its API to Kotlin
callers on JVM, Android, iOS, Linux/macOS/Windows native, JS, and Wasm targets.

The MPFR C source is pinned at a specific version and verified by checksum
(`third_party/mpfr/`); the Kotlin layer is a faithful wrapper, not a reimplementation.
Bit-for-bit correctness with the upstream MPFR C API is the primary correctness
target — see `docs/methodology/` (where published) for the rationale and the API
mapping tables.

## Repository layout (public surface)

```
README.md                        # consumer-facing overview
LICENSE, NOTICE,                 # license + notices
THIRD_PARTY_LICENSES.md          # third-party licenses (LGPL-3.0 for MPFR/GMP)
build.gradle.kts,                # Gradle multiplatform build
settings.gradle.kts,
libs.versions.toml, gradle/
src/                             # Kotlin sources (multiplatform)
  commonMain/, jvmMain/,         # shared + per-platform implementations
  androidMain/, nativeMain*/,
  jsMain/, wasmJsMain/, ...
third_party/mpfr/                # pinned MPFR + GMP C sources + build helpers
  fetch.sh, build.sh,            # reproducible source fetch + build
  docker-compose.yml, Dockerfile,
  src/                           # upstream MPFR C source
docs/                            # published architecture + methodology docs
```

Some dot-prefixed directories listed in `.gitignore` are private working spaces
for the project owner and coding agents. Leave them untouched in consumer contributions.

## Conventions for contributors

- **Language**: Kotlin Multiplatform; idiomatic Kotlin (no platform-specific shims
  unless the platform target genuinely requires them).
- **Bit precision is the contract**: any change that affects rounding, exception
  flags, precision semantics, or special-value handling must be paired with a
  matching unit/property test. The MPFR C test suite is the reference oracle
  (`third_party/mpfr/tests/`) — port the relevant cases to Kotest.
- **API naming**: follow the MPFR function names (`mpfr_add`, `mpfr_sin`, …) and
  add a thin Kotlin-idiomatic layer on top, not a rename.
- **Commits**: Conventional Commits (`feat:`, `fix:`, `docs:`, `build:`, `chore:`, …).
  Atomicity test: reverting the single commit must leave a coherent, compilable tree;
  a feature lands together with its behavior tests (`test:` is for test-only commits).
  `build:` — not `chore:` — for dependencies that ship inside the artifact (MPFR/GMP
  pin bumps, cinterop/prebuilt native assemblies, Gradle plugins); `chore:` only for
  non-shipping tooling. Breaking public-API changes take `!` after the type and must
  include the updated checked-in API baseline dump in the same commit. No internal
  project-management vocabulary in commit messages or branch names.
- **Licensing**: this library is distributed under the terms stated in `LICENSE`.
  MPFR (LGPL-3.0) and GMP (LGPL-3.0 or GPL-2.0+) are linked dynamically; see
  `THIRD_PARTY_LICENSES.md` for obligations.

## For consumers (downstream users)

Once the library is published, the typical integration is:

```kotlin
// commonMain of a downstream KMP project
val x = MpfrFloat(precisionBits = 256)            // 256-bit significand
x.assign("3.14159265358979323846264338327950288") // arbitrary-precision decimal
val y = MpfrFloat(precisionBits = 256)
y.assign("1.0e-1000")
x.add(y)                                         // x = x + y, MPFR-rounded
```

See the public API reference (generated from the MPFR header) for the full surface
— the library mirrors the upstream function set rather than inventing its own
abstractions.

## Build (for contributors)

The native MPFR/GMP libraries are produced reproducibly by `third_party/mpfr/build.sh`
(or `docker compose up` for a containerised build); the Kotlin build then consumes
those artifacts via `cinterop` on native targets and a JVM bridge on the JVM/Android
targets. The exact wiring is documented under `docs/`.

## When in doubt

- For library architecture, scope, and trade-offs: see `docs/`.
- For the MPFR C reference: <https://www.mpfr.org/mpfr-current/mpfr.html>.
- For Kotlin Multiplatform: <https://kotlinlang.org/docs/multiplatform.html>.
