package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.MpfrRangeException
import io.github.thekekt.mpfr.RoundingMode
import io.github.thekekt.mpfr.core.Sign
import io.github.thekekt.mpfr.of
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigInteger

/**
 * Precision- and range-related properties: the result-width defaults, exponent
 * saturation at the default range edges, neighbour steps, and the exact
 * crossings between MpfrFloat and the JVM scalar types.
 */
class PrecisionRangePropertiesTest : FunSpec({

    test("result widths follow the documented defaults and honor explicit widths") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, Arb.int(0, MpfrArbs.PRECISIONS.lastIndex), Arb.int(0, MpfrArbs.PRECISIONS.lastIndex), Arb.int(0, MpfrArbs.PRECISIONS.lastIndex)) { i, j, k ->
            val p1 = MpfrPrecision(MpfrArbs.PRECISIONS[i])
            val p2 = MpfrPrecision(MpfrArbs.PRECISIONS[j])
            val p3 = MpfrPrecision(MpfrArbs.PRECISIONS[k])
            val a = MpfrFloat.of(3L, p1)
            val b = MpfrFloat.of(5L, p2)
            val widest = maxOf(p1, p2)

            check(a.add(b).precision == widest) { "add default width" }
            check(a.sub(b).precision == widest) { "sub default width" }
            check(a.mul(b).precision == widest) { "mul default width" }
            check(a.div(b).precision == widest) { "div default width" }
            check(a.fma(b, b).precision == widest) { "fma default width" }
            check(a.sqrt().precision == p1) { "unary keeps the operand width" }
            check(a.sqr().precision == p1) { "sqr keeps the operand width" }
            check(a.shl(3).precision == p1) { "shift keeps the operand width" }
            check(a.add(1L).precision == p1) { "scalar operations keep the left width" }
            check(a.pow(b).precision == widest) { "pow default width" }

            check(a.add(b, RoundingMode.NEAREST_EVEN, p3).precision == p3) { "explicit width on add" }
            check(a.fma(b, b, RoundingMode.NEAREST_EVEN, p3).precision == p3) { "explicit width on fma" }
            check(MpfrFloat.sum(listOf(a, b), p3).precision == p3) { "explicit width on sum" }
        }
    }

    test("exponent saturation at the default range edges") {
        if (skipIfNativeMissing()) return@test
        val precision = MpfrPrecision(53)
        val one = MpfrFloat.of(1L, precision)
        val emax = 1_073_741_823 // default MPFR emax, 2^30 - 1

        val largestPower = one.shl(emax - 1) // 2^(emax - 1): exponent exactly emax
        check(largestPower.isFinite && !largestPower.isZero) { "2^(emax-1) is representable" }

        val overflow = one.shl(emax) // exponent emax + 1: overflows to +Inf
        check(overflow.isInfinite && !overflow.isNegative) { "2^emax overflows to +Inf" }
        val overflowTowardZero = one.shl(emax, RoundingMode.TOWARD_ZERO)
        check(overflowTowardZero.isFinite) { "toward-zero overflow stays finite" }
        check(overflowTowardZero.nextUp().isInfinite) { "toward-zero overflow lands on the largest finite value" }

        val smallest = one.shr(emax + 1) // 2^(-2^30): exponent emin, the smallest positive
        check(smallest.isFinite && !smallest.isZero) { "2^(emin-1) is representable" }
        val underflow = one.shr(emax + 2) // below the range: underflows to +0
        check(underflow.isZero && !underflow.isNegative) { "underflow to +0" }
        val underflowTowardPositive = one.shr(emax + 2, RoundingMode.TOWARD_POSITIVE)
        assertSameValue(smallest, underflowTowardPositive) { "toward-positive underflow reaches the smallest value" }
        val underflowTowardNegative = (-one).shr(emax + 2, RoundingMode.TOWARD_NEGATIVE)
        assertSameValue(-smallest, underflowTowardNegative) { "toward-negative underflow on the negative side" }
    }

    test("nextUp and nextDown are inverse neighbours") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.regular) { x ->
            val above = x.nextUp()
            val below = x.nextDown()
            check(above.isFinite && above.isRegularForProperty()) { "nextUp stays in range for $x" }
            check(below.isFinite && below.isRegularForProperty()) { "nextDown stays in range for $x" }
            check(above.gt(x)) { "nextUp is numerically above" }
            check(below.lt(x)) { "nextDown is numerically below" }
            assertSameValue(x, above.nextDown()) { "nextDown(nextUp(x))" }
            assertSameValue(x, below.nextUp()) { "nextUp(nextDown(x))" }
        }

        val precision = MpfrPrecision(53)
        val posInf = MpfrFloat.Infinity(Sign.POSITIVE, precision)
        val negInf = MpfrFloat.Infinity(Sign.NEGATIVE, precision)
        assertSameValue(posInf, posInf.nextUp()) { "nextUp(+Inf)" }
        assertSameValue(negInf, negInf.nextDown()) { "nextDown(-Inf)" }
        val maxFinite = posInf.nextDown()
        check(maxFinite.isFinite) { "nextDown(+Inf) is the largest finite value" }
        assertSameValue(posInf, maxFinite.nextUp()) { "nextUp(largest finite)" }
        val minFinite = negInf.nextUp()
        check(minFinite.isFinite) { "nextUp(-Inf) is the smallest finite value" }
        assertSameValue(negInf, minFinite.nextDown()) { "nextDown(smallest finite)" }
    }

    test("Long factories round-trip exactly at full width") {
        if (skipIfNativeMissing()) return@test
        val precision = MpfrPrecision(64)
        checkAll(PROPS_LONG, Arb.long()) { k ->
            val value = MpfrFloat.of(k, precision)
            check(value.fitsLong()) { "$k fits Long" }
            check(value.toLong() == k) { "round trip of $k" }
        }
        for (edge in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L)) {
            val value = MpfrFloat.of(edge, precision)
            check(value.toLong() == edge) { "edge round trip of $edge" }
        }
        val tooLarge = MpfrFloat.of(BigInteger.ONE.shiftLeft(70), precision)
        check(!tooLarge.fitsLong()) { "2^70 does not fit Long" }
        check(runCatching { tooLarge.toLong() }.exceptionOrNull() is MpfrRangeException) { "toLong rejects 2^70" }
    }

    test("double crossings round-trip and saturate without throwing") {
        if (skipIfNativeMissing()) return@test
        val precision = MpfrPrecision(53)
        checkAll(PROPS, Arb.double()) { d ->
            val value = MpfrFloat.of(d, precision)
            val back = value.toDouble()
            if (d.isNaN()) {
                check(back.isNaN()) { "NaN round trip" }
            } else {
                check(back == d) { "round trip of $d produced $back" }
            }
        }
        val huge = MpfrFloat.of(BigInteger.ONE.shiftLeft(2_000), MpfrPrecision(64))
        check(huge.toDouble().isInfinite() && huge.toDouble() > 0.0) { "overflow saturates to +Inf" }
        val tiny = MpfrFloat.of(BigInteger.ONE, MpfrPrecision(64)).shr(4_000)
        check(tiny.toDouble() == 0.0) { "underflow saturates to 0" }
        check(MpfrFloat.of(BigInteger.ONE, MpfrPrecision(64)).negateForProperty().shr(4_000).toDouble() == 0.0) {
            "negative underflow saturates to -0.0"
        }
    }
})

private fun MpfrFloat.isRegularForProperty(): Boolean = !isNaN && !isInfinite && !isZero

private fun MpfrFloat.negateForProperty(): MpfrFloat = -this
