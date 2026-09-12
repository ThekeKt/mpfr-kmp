package io.github.thekekt.mpfr

/**
 * Thrown when a narrowing conversion (`toLong`, `toInt`, …) is called on a value
 * outside the destination range — programmer error: the call site controls the
 * input; `MpfrFloat` arithmetic itself never throws this. Pre-check with the
 * `fits*` predicates.
 *
 * Deliberately *not* an `MpfrError`: it never appears in a `Result`.
 */
public class MpfrRangeException internal constructor(message: String) :
    RuntimeException(message)

/**
 * Thrown when the platform MPFR bridge cannot be initialised: the native shim or
 * its MPFR/GMP dependencies failed to load. Programmer/deployment error,
 * sanctioned `…Exception` sibling of [MpfrRangeException].
 */
public class MpfrLoadException internal constructor(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
