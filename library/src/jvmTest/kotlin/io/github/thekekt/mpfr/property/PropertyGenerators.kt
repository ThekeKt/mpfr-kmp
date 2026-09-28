package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.core.Sign
import io.github.thekekt.mpfr.internal.Repr
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import kotlin.random.Random

/**
 * Value generators for the property tier.
 *
 * Regular values are built through the canonical [Repr] encoding (the same bytes
 * the JNI seam exchanges), so any significand width and both signs are reachable.
 * The sampled exponents stay inside the central bands (near zero and up to about
 * 2^14): this keeps value rendering cheap — decimal expansion grows with the
 * exponent, and near-range exponents would make failure messages astronomically
 * long. Range saturation is covered explicitly by the shift-based tests instead.
 * Specials are mixed into the generic generators and also available alone.
 */
internal object MpfrArbs {

    /** Significand widths exercised by the tier: boundaries and common widths. */
    val PRECISIONS: List<Int> = listOf(1, 2, 3, 7, 8, 16, 24, 53, 64, 100, 113, 256, 1024)

    /** Widths used by the integer oracle (small enough to reason about exactly). */
    val ORACLE_PRECISIONS: List<Int> = listOf(8, 24, 53)

    // A raw sample: indices/entropy, mapped to a value below. Keeping the mapping
    // pure makes every property replayable from the fixed seed.
    private data class Raw(
        val precIndex: Int,
        val expKey: Int,
        val kindRoll: Int,
        val signRoll: Boolean,
        val seed: Long,
    )

    private val raw: Arb<Raw> = Arb.bind(
        Arb.int(0, PRECISIONS.lastIndex),
        Arb.int(0, 1023),
        Arb.int(0, 99),
        Arb.boolean(),
        Arb.long(),
    ) { p, e, kind, sign, seed -> Raw(p, e, kind, sign, seed) }

    /** Any value: regulars dominate; specials appear in every run. */
    val any: Arb<MpfrFloat> = raw.map(::fromRaw)

    /** Regular (finite, non-zero) values only. */
    val regular: Arb<MpfrFloat> = raw.map { fromRaw(it.copy(kindRoll = it.kindRoll % 85)) }

    /** Specials only: NaN, ±Infinity, ±0. */
    val special: Arb<MpfrFloat> = raw.map { fromRaw(it.copy(kindRoll = 85 + it.kindRoll % 15)) }

    /** NaN on the left, anything on the right. */
    val nanFirstPair: Arb<Pair<MpfrFloat, MpfrFloat>> =
        Arb.bind(raw, raw) { left, right -> fromRaw(left.copy(kindRoll = 85)) to fromRaw(right) }

    val regularPair: Arb<Pair<MpfrFloat, MpfrFloat>> = pairOf(regular)
    val anyPair: Arb<Pair<MpfrFloat, MpfrFloat>> = pairOf(any)
    val anyTriple: Arb<Triple<MpfrFloat, MpfrFloat, MpfrFloat>> = tripleOf(any)

    fun pairOf(values: Arb<MpfrFloat>): Arb<Pair<MpfrFloat, MpfrFloat>> =
        Arb.bind(values, values) { a, b -> a to b }

    fun tripleOf(values: Arb<MpfrFloat>): Arb<Triple<MpfrFloat, MpfrFloat, MpfrFloat>> =
        Arb.bind(values, values, values) { a, b, c -> Triple(a, b, c) }

    private fun fromRaw(raw: Raw): MpfrFloat {
        val precision = MpfrPrecision(PRECISIONS[raw.precIndex])
        return when {
            raw.kindRoll >= 98 -> MpfrFloat.Zero(Sign.NEGATIVE, precision)
            raw.kindRoll >= 95 -> MpfrFloat.Zero(Sign.POSITIVE, precision)
            raw.kindRoll >= 90 -> MpfrFloat.Infinity(if (raw.signRoll) Sign.NEGATIVE else Sign.POSITIVE, precision)
            raw.kindRoll >= 85 -> MpfrFloat.NaN(precision)
            else -> MpfrFloat.ofRepr(
                Repr.regular(
                    precision,
                    raw.signRoll,
                    wireExponent(centralKey(raw.expKey), precision.bits),
                    mantissa(precision.bits, raw.seed),
                ),
            )
        }
    }

    /** Keeps the sampled exponent inside the two central bands (0..1). */
    private fun centralKey(expKey: Int): Int = expKey - expKey % 4 + expKey % 2

    /**
     * Wire exponent (the LSB exponent of the significand): band 0 stays near zero,
     * band 1 reaches about 2^14; the sign is applied by the caller.
     */
    private fun wireExponent(expKey: Int, precision: Int): Long {
        val step = (expKey / 4) * 8L
        return if (expKey % 4 == 0) step else 4_096L + step
    }

    /**
     * Canonical significand, exactly as the native encoder emits it: left-aligned
     * inside the byte array (MSB of the leading byte set) with the `8*length - precision`
     * padding bits below the last significant bit cleared.
     */
    private fun mantissa(precision: Int, seed: Long): ByteArray {
        val length = (precision + 7) / 8
        val rnd = Random(seed)
        val bytes = ByteArray(length) { rnd.nextInt(256).toByte() }
        bytes[0] = (bytes[0].toInt() or 0x80).toByte()
        val pad = length * 8 - precision
        if (pad > 0) {
            bytes[length - 1] = (bytes[length - 1].toInt() and (0xFF shl pad)).toByte()
        }
        return bytes
    }
}