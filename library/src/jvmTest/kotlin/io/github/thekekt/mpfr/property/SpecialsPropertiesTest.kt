package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.RoundingMode
import io.github.thekekt.mpfr.core.MpfrKind
import io.github.thekekt.mpfr.core.Sign
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Special-value properties: NaN propagation per operation class (with the
 * documented exceptions), signed-zero and infinity arithmetic, the IEEE
 * `minNum`/`maxNum` behavior of min/max, and the total-order chain over the
 * canonical special sequence.
 */
class SpecialsPropertiesTest : FunSpec({

    test("NaN propagates through the operations that document it") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.nanFirstPair) { (nan, other) ->
            val expectedPrecision = maxOf(nan.precision, other.precision)
            val results: List<Pair<String, MpfrFloat>> = listOf(
                "add" to nan.add(other),
                "sub" to nan.sub(other),
                "mul" to nan.mul(other),
                "div" to nan.div(other),
                "dim" to nan.dim(other),
                "agm" to nan.agm(other),
                "fmod" to nan.fmod(other),
                "remainder" to nan.remainder(other),
                "atan2" to nan.atan2(other),
                "relativeDifference" to nan.relativeDifference(other),
                "fma" to nan.fma(other, other),
                "fms" to nan.fms(other, other),
                "sqrt" to nan.sqrt(),
                "log" to nan.log(),
                "exp" to nan.exp(),
                "sin" to nan.sin(),
                "cos" to nan.cos(),
                "abs" to nan.abs(),
                "negate" to -nan,
                "truncate" to nan.truncate(),
                "floor" to nan.floor(),
                "ceil" to nan.ceil(),
                "round" to nan.round(),
                "fractional" to nan.fractional(),
                "nextUp" to nan.nextUp(),
                "nextDown" to nan.nextDown(),
            )
            for ((name, result) in results) {
                check(result.isNaN) { "$name with a NaN operand returned $result" }
            }
            check(nan.add(other).precision == expectedPrecision) { "binary NaN result width" }
        }
    }

    test("pow keeps its documented NaN exceptions") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.any) { other ->
            val precision = other.precision
            val one = MpfrFloat.of(1L, precision)
            val zero = MpfrFloat.of(0L, precision)
            val nan = MpfrFloat.NaN(precision)
            assertSameValue(one, one.pow(nan, RoundingMode.NEAREST_EVEN, precision)) {
                "pow(1, NaN) is 1"
            }
            assertSameValue(one, nan.pow(zero, RoundingMode.NEAREST_EVEN, precision)) {
                "pow(NaN, 0) is 1"
            }
            check(nan.pow(one, RoundingMode.NEAREST_EVEN, precision).isNaN) { "pow(NaN, 1)" }
        }
    }

    test("exact zero sums and differences take the +0 sign under nearest rounding") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.regular) { a ->
            val precision = a.precision
            val positiveZero = MpfrFloat.Zero(Sign.POSITIVE, precision)
            assertSameValue(positiveZero, a.add(-a, RoundingMode.NEAREST_EVEN, precision)) { "x + (-x)" }
            assertSameValue(positiveZero, a.sub(a, RoundingMode.NEAREST_EVEN, precision)) { "x - x" }
        }
    }

    test("signed zero arithmetic follows the IEEE sign rules") {
        if (skipIfNativeMissing()) return@test
        val precision = MpfrPrecision(53)
        val posZero = MpfrFloat.Zero(Sign.POSITIVE, precision)
        val negZero = MpfrFloat.Zero(Sign.NEGATIVE, precision)
        val rnd = RoundingMode.NEAREST_EVEN

        assertSameValue(posZero, posZero.add(negZero, rnd, precision)) { "+0 + -0" }
        assertSameValue(negZero, negZero.add(negZero, rnd, precision)) { "-0 + -0" }
        assertSameValue(posZero, posZero.sub(posZero, rnd, precision)) { "+0 - +0" }
        assertSameValue(negZero, negZero.sub(posZero, rnd, precision)) { "-0 - +0" }
        assertSameValue(posZero, negZero.sub(negZero, rnd, precision)) { "-0 - -0" }
        check(posZero.mul(negZero, rnd, precision).let { it.isZero && it.isNegative }) { "+0 * -0" }
        val five = MpfrFloat.of(5L, precision)
        check(posZero.mul(five, rnd, precision).let { it.isZero && !it.isNegative }) { "+0 * +5" }
        check(posZero.sub(five, rnd, precision).isNegative) { "+0 - 5" }
        check(posZero.div(five, rnd, precision).let { it.isZero && !it.isNegative }) { "+0 / +5" }
        check(posZero.div(-five, rnd, precision).let { it.isZero && it.isNegative }) { "+0 / -5" }
        check(negZero.abs() == posZero) { "abs(-0)" }
        assertSameValue(posZero, -negZero) { "-(-0)" }
        assertSameValue(negZero, posZero.min(negZero, rnd, precision)) { "min(+0, -0)" }
        assertSameValue(posZero, posZero.max(negZero, rnd, precision)) { "max(+0, -0)" }
    }

    test("infinity arithmetic follows the documented table") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.regular) { finite ->
            val precision = maxOf(finite.precision, MpfrPrecision(53))
            val value = finite.add(MpfrFloat.Zero(Sign.POSITIVE, finite.precision), RoundingMode.NEAREST_EVEN, precision)
            val posInf = MpfrFloat.Infinity(Sign.POSITIVE, precision)
            val negInf = MpfrFloat.Infinity(Sign.NEGATIVE, precision)
            val rnd = RoundingMode.NEAREST_EVEN

            check(posInf.add(negInf, rnd, precision).isNaN) { "+Inf + -Inf" }
            check(posInf.sub(posInf, rnd, precision).isNaN) { "+Inf - +Inf" }
            check(posInf.mul(MpfrFloat.of(0L, precision), rnd, precision).isNaN) { "+Inf * 0" }
            check(posInf.mul(value, rnd, precision).let { it.isInfinite && it.isNegative == value.isNegative }) {
                "+Inf * finite"
            }
            check(negInf.mul(value, rnd, precision).let { it.isInfinite && it.isNegative != value.isNegative }) {
                "-Inf * finite"
            }
            check(MpfrFloat.NaN(precision).hypot(posInf, rnd, precision).let { it.isInfinite && !it.isNegative }) {
                "hypot(NaN, +Inf) is +Inf"
            }
            check(MpfrFloat.NaN(precision).hypot(negInf, rnd, precision).let { it.isInfinite && !it.isNegative }) {
                "hypot(NaN, -Inf) is +Inf"
            }
            check(MpfrFloat.NaN(precision).hypot(value, rnd, precision).isNaN) { "hypot(NaN, finite)" }
            check(value.div(posInf, rnd, precision).let { it.isZero && it.isNegative == value.isNegative }) {
                "finite / +Inf"
            }
            check(posInf.div(posInf, rnd, precision).isNaN) { "+Inf / +Inf" }
            check(posInf.div(MpfrFloat.of(0L, precision), rnd, precision).let { it.isInfinite && !it.isNegative }) {
                "+Inf / 0"
            }
            check(value.div(MpfrFloat.of(0L, precision), rnd, precision).isInfinite) { "finite / 0" }
            check(MpfrFloat.of(0L, precision).div(MpfrFloat.of(0L, precision), rnd, precision).isNaN) { "0 / 0" }
            check(posInf.sqrt(rnd).isInfinite) { "sqrt(+Inf)" }
            check(negInf.sqrt(rnd).isNaN) { "sqrt(-Inf)" }
            val negative = if (value.isNegative) value else -value
            check(negative.sqrt(rnd).isNaN) { "sqrt(negative)" }
            check(MpfrFloat.Zero(Sign.POSITIVE, precision).log(rnd).let { it.isInfinite && it.isNegative }) { "log(+0)" }
            check(negative.log(rnd).isNaN) { "log(-x)" }
            check(negInf.exp(rnd).let { it.isZero && !it.isNegative }) { "exp(-Inf)" }
            check(posInf.exp(rnd).isInfinite) { "exp(+Inf)" }
            assertSameValue(negInf, -posInf) { "-Inf abs sign flip" }
            assertSameValue(posInf, negInf.abs()) { "abs(-Inf)" }
            check(negInf.dim(MpfrFloat.of(0L, precision), rnd, precision).let { it.isZero && !it.isNegative }) { "dim(-Inf, 0)" }
            check(posInf.dim(posInf, rnd, precision).let { it.isZero && !it.isNegative }) { "dim(+Inf, +Inf)" }
        }
    }

    test("min and max follow the IEEE minNum/maxNum rules") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.anyPair) { (a, b) ->
            val precision = maxOf(a.precision, b.precision)
            val rnd = RoundingMode.NEAREST_EVEN
            val min = a.min(b, rnd, precision)
            val max = a.max(b, rnd, precision)

            when {
                a.isNaN && b.isNaN -> {
                    check(min.isNaN && max.isNaN) { "min/max of two NaNs" }
                }
                a.isNaN -> {
                    assertNumericSame(b, min) { "min(NaN, b) returns b" }
                    assertNumericSame(b, max) { "max(NaN, b) returns b" }
                }
                b.isNaN -> {
                    assertNumericSame(a, min) { "min(a, NaN) returns a" }
                    assertNumericSame(a, max) { "max(a, NaN) returns a" }
                }
                a.isZero && b.isZero -> {
                    check(min.isNegative == (a.isNegative || b.isNegative)) { "min of signed zeros keeps -0" }
                    check(max.isNegative == (a.isNegative && b.isNegative)) { "max of signed zeros keeps +0 for mixed signs" }
                }
                else -> {
                    val lo = if (a.lt(b)) a else b
                    val hi = if (a.gt(b)) a else b
                    assertNumericSame(lo, min) { "min(a, b)" }
                    assertNumericSame(hi, max) { "max(a, b)" }
                }
            }
            check(min.precision == precision && max.precision == precision) { "min/max result width" }
        }
    }

    test("the total order over the canonical special sequence is a strict chain") {
        if (skipIfNativeMissing()) return@test
        val precision = MpfrPrecision(32)
        fun value(token: String): MpfrFloat = when (token) {
            "-Inf" -> MpfrFloat.Infinity(Sign.NEGATIVE, precision)
            "+Inf" -> MpfrFloat.Infinity(Sign.POSITIVE, precision)
            "-0" -> MpfrFloat.Zero(Sign.NEGATIVE, precision)
            "+0" -> MpfrFloat.Zero(Sign.POSITIVE, precision)
            "NaN" -> MpfrFloat.NaN(precision)
            else -> MpfrFloat.of(token.toLong(), precision)
        }
        val chain = listOf("-Inf", "-3", "-2", "-1", "-0", "+0", "1", "2", "3", "+Inf", "NaN").map(::value)
        for (i in chain.indices) {
            check(chain[i].totalOrder(chain[i])) { "total order is reflexive at $i" }
            for (j in chain.indices) {
                val expected = i <= j
                check(chain[i].totalOrder(chain[j]) == expected) {
                    "totalOrder(chain[$i], chain[$j]) should be $expected"
                }
            }
        }
        for (i in 0 until chain.lastIndex) {
            check(chain[i].compareTo(chain[i + 1]) < 0) { "compareTo ordering at $i" }
        }
    }
})