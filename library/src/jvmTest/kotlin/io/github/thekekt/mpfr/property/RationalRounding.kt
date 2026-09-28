package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.RoundingMode
import io.github.thekekt.mpfr.core.Sign
import io.github.thekekt.mpfr.internal.Repr
import java.math.BigInteger

/**
 * An exact-rational oracle for the algebraic operations, independent of MPFR:
 * a rational value is rounded to a binary width in all five modes with plain
 * BigInteger arithmetic, and the result is rebuilt as an [MpfrFloat] through the
 * canonical [Repr] encoding. Used for values that are exactly representable as
 * `n / d`, so correctness is decided without any native help.
 */
internal data class Rational(val numerator: BigInteger, val denominator: BigInteger) {

    init {
        require(denominator.signum() > 0) { "denominator must be positive" }
    }

    companion object {
        fun of(value: Long): Rational = Rational(BigInteger.valueOf(value), BigInteger.ONE)
        fun of(numerator: BigInteger, denominator: BigInteger): Rational =
            if (denominator.signum() < 0) Rational(numerator.negate(), denominator.negate())
            else Rational(numerator, denominator)
    }
}

/** Rounds the exact [value] to [bits] significand bits in [mode]. */
internal fun roundToBinary(value: Rational, bits: Int, mode: RoundingMode): MpfrFloat {
    val negative = value.numerator.signum() < 0
    val num = value.numerator.abs()
    val den = value.denominator
    check(num.signum() != 0) { "zero results are outside this oracle" }

    // k with 2^k <= num/den < 2^(k+1)
    var k = num.bitLength() - den.bitLength()
    if (ratioVsPowerOfTwo(num, den, k) < 0) k -= 1

    val shift = bits - 1 - k
    val divisor = if (shift >= 0) den else den.shiftLeft(-shift)
    val (quotient, remainder) =
        if (shift >= 0) num.shiftLeft(shift).divideAndRemainder(den)
        else num.divideAndRemainder(divisor)
    var significand = quotient
    val exact = remainder.signum() == 0
    if (!exact) {
        val roundUp = when (mode) {
            RoundingMode.TOWARD_ZERO -> false
            RoundingMode.TOWARD_POSITIVE -> !negative
            RoundingMode.TOWARD_NEGATIVE -> negative
            RoundingMode.AWAY_FROM_ZERO -> true
            RoundingMode.NEAREST_EVEN -> {
                val comparison = remainder.shiftLeft(1).compareTo(divisor)
                comparison > 0 || (comparison == 0 && significand.testBit(0))
            }
        }
        if (roundUp) significand = significand.add(BigInteger.ONE)
    }
    if (significand.bitLength() > bits) {
        significand = significand.shiftRight(1)
        k += 1
    }

    val precision = MpfrPrecision(bits)
    // The canonical encoding left-aligns the significand inside its byte array:
    // the leading byte carries the MSB and the padding bits below the last
    // significant bit are zero.
    val length = (bits + 7) / 8
    val pad = length * 8 - bits
    val aligned = significand.shiftLeft(pad)
    val bytes = aligned.toByteArray().let { raw ->
        if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }
    check(bytes.size == length && (bytes[0].toInt() and 0x80) != 0) { "canonical significand encoding" }
    return MpfrFloat.ofRepr(
        Repr.regular(precision, negative, k.toLong() + 1 - bits - pad, bytes),
    )
}

private fun ratioVsPowerOfTwo(num: BigInteger, den: BigInteger, k: Int): Int =
    if (k >= 0) num.compareTo(den.shiftLeft(k)) else num.shiftLeft(-k).compareTo(den)