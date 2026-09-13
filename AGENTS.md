# AGENTS.md

> Notes for any coding agent (human or AI) reading this repository. Describes what the
> library is, the high-level structure, and the conventions any contributor or consumer
> should follow. Internal working notes are deliberately omitted — this file is part
> of the public artifact set.

## What this project is

A Kotlin Multiplatform library that wraps [GNU MPFR](https://www.mpfr.org/) — the
canonical arbitrary-precision floating-point library — and exposes its API to Kotlin
callers. The current shipped matrix is JVM (desktop) and Android; Kotlin/Native,
JS, and Wasm targets are planned and will land together with faithful bindings for
their platforms (the project does not ship an expect declaration without an honest
actual).

The MPFR C source is pinned at a specific version and verified by checksum
(`third_party/mpfr/`); the Kotlin layer is a faithful wrapper, not a reimplementation.
Bit-for-bit correctness with the upstream MPFR C API is the primary correctness
target.

## Repository layout (public surface)

```
README.md                        # consumer-facing overview
AGENTS.md                        # this file
LICENSE, NOTICE                  # Apache-2.0 wrapper license + attribution
THIRD_PARTY_LICENSES.md          # third-party licenses (LGPL-3.0 for MPFR/GMP)
build.gradle.kts                 # root build: plugin versions, API gate configuration
settings.gradle.kts
gradle.properties
gradle/                          # wrapper + version catalog (libs.versions.toml)
buildSrc/                        # Gradle build logic (incl. the shim-table generator)
library/                         # the Kotlin Multiplatform library module
  build.gradle.kts
  api/jvm/library.api            # public-API baseline (binary-compatibility-validator)
  src/commonMain/, commonTest/, jvmMain/, jvmCommon/, jvmTest/, androidMain/,
  androidHostTest/            # declared source sets (a directory appears with its first file)
  native/jni/                    # JNI shim: mpfr_kmp_jni.c, CMakeLists.txt,
                                 # build-shim.sh, generated mpfr_kmp_ops.h
third_party/mpfr/                # pinned MPFR + GMP sources, fetch/build helpers,
                                 # Dockerfile and docker-compose.yml
.github/workflows/               # CI workflows (under revision)
```

Some dot-prefixed directories listed in `.gitignore` are private working spaces
for the project owner and coding agents. Leave them untouched in consumer contributions.

## Conventions for contributors

- **Language**: Kotlin Multiplatform; idiomatic Kotlin (no platform-specific shims
  unless the platform target genuinely requires them).
- **Bit precision is the contract**: any change that affects rounding, exception
  flags, precision semantics, or special-value handling must be paired with a
  matching unit/property test. The MPFR C test suite (extracted by `fetch.sh` into
  `third_party/mpfr/src/tests/`, not checked in) is the reference oracle — port the
  relevant cases to the JVM test tier and property tests.
- **API naming**: follow the MPFR function names (`mpfr_add`, `mpfr_sin`, …) and
  add a thin Kotlin-idiomatic layer on top, not a rename.
- **Self-describing public artifacts**: do not reference unpublished internal design
  documents, project-management identifiers, or tracker vocabulary in commit
  messages, branch names, source comments, KDoc, or public docs. Citations to the
  public MPFR headers and manual (`mpfr.h`, mpfr.texi) are encouraged.
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

The artifact is **not published yet**; the integration below describes the intended
shape once it is:

```kotlin
// commonMain of a downstream KMP project
import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.core.Result

val pi = MpfrFloat.PI(MpfrPrecision(256))            // 256-bit significand
val v = MpfrFloat.parse("3.14159265358979323846264338327950288",
                        MpfrPrecision(256))          // typed Result on failure
require(v is Result.Success)

val x = v.value
val y = MpfrFloat.of(1L)
val z = x.add(y)                                     // immutable: z is a new value
```

Values are immutable; every arithmetic operation returns a new `MpfrFloat` and
never throws for arithmetic reasons. `equals`/`hashCode` are structural and
precision-aware (`NaN != NaN`, `+0 != -0`).

See the checked-in public-API baseline (`library/api/jvm/library.api`) for the
complete surface — the library mirrors the upstream function set rather than
inventing its own abstractions.

## Build (for contributors)

1. Native MPFR/GMP (Linux/WSL; MSYS2 on Windows):

   ```bash
   cd third_party/mpfr
   bash fetch.sh          # downloads + checksums the pinned tarballs
   bash build.sh          # or: docker compose up  (containerised)
   ```

   The result is an install tree (`third_party/mpfr/install/`) with shared
   `libmpfr`/`libgmp` built thread-safe (`--enable-thread-safe`).

2. JNI shim (the JVM/Android bridge): `JAVA_HOME=/path/to/jdk-21+ bash
   library/native/jni/build-shim.sh` (or CMake in `library/native/jni/`). The
   Kotlin runtime discovers the library via `-Dmpfrkmp.native.file`,
   `-Dmpfrkmp.native.dir` (env variants supported), or the standard
   `java.library.path` packaged path. On hosts without a built shim, the native
   smoke tests log a skip instead of failing.

3. Kotlin build: `gradlew :library:check` (API gate included). The shim's op
   tables (`native/jni/mpfr_kmp_ops.h`, `src/commonMain/.../internal/MpfrOps.kt`)
   are generated from `buildSrc/.../shim/ShimTables.kt` by `:library:generateShim`;
   regenerate and commit them together with the table.

## When in doubt

- For the MPFR C reference: <https://www.mpfr.org/mpfr-current/mpfr.html>.
- For Kotlin Multiplatform: <https://kotlinlang.org/docs/multiplatform.html>.
