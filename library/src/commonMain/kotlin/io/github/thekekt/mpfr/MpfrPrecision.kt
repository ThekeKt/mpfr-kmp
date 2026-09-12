package io.github.thekekt.mpfr

/**
 * Significand precision in bits — part of [MpfrFloat]'s value identity.
 *
 * Regular class on purpose: `value class` would box in generic positions and the
 * API wants validation, `compareTo` and a readable `toString`.
 */
public class MpfrPrecision public constructor(public val bits: Int) : Comparable<MpfrPrecision> {

    /** Checked narrowing entry point for call sites working in `Long` (range-validated). */
    public constructor(bits: Long) : this(
        requireNotNull(
            bits.takeIf { it in MIN_BITS.toLong()..MAX_BITS.toLong() }
        ) { "precision bits out of range: $bits" }.toInt(),
    )

    init {
        require(bits in MIN_BITS..MAX_BITS) {
            "precision bits out of range: $bits (allowed: $MIN_BITS..$MAX_BITS)"
        }
    }

    public override fun compareTo(other: MpfrPrecision): Int = bits.compareTo(other.bits)

    public override fun equals(other: Any?): Boolean =
        other is MpfrPrecision && other.bits == bits

    public override fun hashCode(): Int = bits

    /** e.g. `"53 bits"`. */
    public override fun toString(): String = "$bits bits"

    public companion object {
        /** `MPFR_PREC_MIN` (mpfr.h L177). */
        public const val MIN_BITS: Int = 1

        /** v1 cap: `min(MPFR_PREC_MAX, Int.MAX_VALUE - 1)` (keeps the `Int` domain safe). */
        public const val MAX_BITS: Int = Int.MAX_VALUE - 1

        /** Double-equivalent width: 53. */
        public const val DOUBLE_BITS: Int = 53

        /** Float-equivalent width: 24. */
        public const val SINGLE_BITS: Int = 24
    }
}
