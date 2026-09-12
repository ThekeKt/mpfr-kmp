package io.github.thekekt.mpfr

/**
 * Rounding direction for every MPFR result. The five v1 values map 1:1
 * onto `mpfr_rnd_t` — enum order **must** match the C values 0..4 (mpfr.h
 * L102–L116: RNDN=0, RNDZ=1, RNDU=2, RNDD=3, RNDA=4); verified in
 * `RoundingModeTest`.
 *
 * There is no process-global rounding default: every operation carries the
 * literal `NEAREST_EVEN` default in its signature.
 */
public enum class RoundingMode {
    /** `MPFR_RNDN` — round to nearest, ties to even (IEEE 754 default). */
    NEAREST_EVEN,

    /** `MPFR_RNDZ` — round toward zero (truncation). */
    TOWARD_ZERO,

    /** `MPFR_RNDU` — round toward +∞. */
    TOWARD_POSITIVE,

    /** `MPFR_RNDD` — round toward −∞. */
    TOWARD_NEGATIVE,

    /** `MPFR_RNDA` — round away from zero. */
    AWAY_FROM_ZERO,
    ;

    /**
     * The `mpfr_rnd_t` encoding used to cross the FFI boundary
     * (`mpfrValue`); ordinal == C value by contract.
     */
    @InternalMpfrApi
    internal val mpfrValue: Int get() = ordinal

    /** MPFR's short label (`"N"`, `"Z"`, `"U"`, `"D"`, `"A"` — `mpfr_print_rnd_mode`). */
    override fun toString(): String = when (this) {
        NEAREST_EVEN -> "N"
        TOWARD_ZERO -> "Z"
        TOWARD_POSITIVE -> "U"
        TOWARD_NEGATIVE -> "D"
        AWAY_FROM_ZERO -> "A"
    }

    public companion object {
        /** The default rounding mode literal. */
        public val DEFAULT: RoundingMode get() = NEAREST_EVEN
    }
}

/**
 * Per-call scoping helper: it carries **no** global state —
 * it simply injects [mode] into the block as the rounding mode that call sites in
 * the block pass explicitly ("per-call semantics only").
 */
public inline fun <R> withRoundingMode(mode: RoundingMode, action: (RoundingMode) -> R): R =
    action(mode)
