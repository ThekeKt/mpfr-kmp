package io.github.thekekt.mpfr.core

/**
 * Sign of a regular, zero or infinite value.
 * NaN never exposes a sign — [io.github.thekekt.mpfr.MpfrFloat.sign] reports
 * [POSITIVE] for NaN and the sign predicate returns `false`.
 */
public enum class Sign {
    POSITIVE,
    NEGATIVE,
    ;

    /** The opposite sign. */
    public fun negate(): Sign = if (this == POSITIVE) NEGATIVE else POSITIVE
}

/**
 * Classification of an [io.github.thekekt.mpfr.MpfrFloat]. Enum order mirrors the
 * canonical `Repr` kind byte (0=Zero, 1=NaN, 2=Inf, 3=Regular); computed from the
 * representation bytes without FFI.
 */
public enum class MpfrKind {
    ZERO,
    NAN,
    INF,
    REGULAR,
}

/** Style selector for [io.github.thekekt.mpfr.format]. */
public enum class NumberStyle {
    /** `%.digitsRe` — one leading digit plus exponent. */
    SCIENTIFIC,

    /** `%.digitsRf` — fixed-point notation. */
    FIXED,

    /** `%.digitsRg` — shortest-style general notation. */
    GENERAL,
}

/** Return of `MpfrFloat.split()` (mpfr_modf: integer part first). */
public data class IntegerAndFraction(
    public val integer: io.github.thekekt.mpfr.MpfrFloat,
    public val fraction: io.github.thekekt.mpfr.MpfrFloat,
)

/** Return of `MpfrFloat.sinCos()` (mpfr_sin_cos). */
public data class SinCosResult(
    public val sin: io.github.thekekt.mpfr.MpfrFloat,
    public val cos: io.github.thekekt.mpfr.MpfrFloat,
)
