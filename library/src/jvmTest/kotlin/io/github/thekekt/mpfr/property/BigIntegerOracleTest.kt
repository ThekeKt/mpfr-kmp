package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.RoundingMode
import io.github.thekekt.mpfr.core.Sign
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.math.BigInteger

/**
 * Differential tier against an exact-rational oracle: additions, subtractions,
 * products, quotients and fused products of small integers are rational values;
 * the tier rounds each exact value to the target width with BigInteger-only
 * arithmetic (all five modes) and requires the library result to match bitwise.
 * This checks the algebraic operations against a source that shares no code with
 * MPFR.
 */
class BigIntegerOracleTest : FunSpec({

    test("algebraic operations match an exact-rational rounding at every mode") {
        if (skipIfNativeMissing()) return@test
        checkAll(
            PROPS,
            Arb.int(-1_000_000, 1_000_000),
            Arb.int(-1_000_000, 1_000_000),
            Arb.int(0, MpfrArbs.ORACLE_PRECISIONS.lastIndex),
        ) { x, y, widthIndex ->
            val precision = MpfrPrecision(MpfrArbs.ORACLE_PRECISIONS[widthIndex])
            val source = MpfrPrecision(64)
            val a = MpfrFloat.of(x.toLong(), source)
            val b = MpfrFloat.of(y.toLong(), source)
            val one = MpfrFloat.of(1L, source)
            val bx = BigInteger.valueOf(x.toLong())
            val by = BigInteger.valueOf(y.toLong())

            val cases: List<Triple<String, Rational, (RoundingMode) -> MpfrFloat>> = buildList {
                fun withZeroSkip(name: String, value: Rational, call: (RoundingMode) -> MpfrFloat) {
                    if (value.numerator.signum() != 0) add(Triple(name, value, call))
                }
                withZeroSkip("add", Rational.of(bx.add(by), BigInteger.ONE)) { mode -> a.add(b, mode, precision) }
                withZeroSkip("sub", Rational.of(bx.subtract(by), BigInteger.ONE)) { mode -> a.sub(b, mode, precision) }
                withZeroSkip("mul", Rational.of(bx.multiply(by), BigInteger.ONE)) { mode -> a.mul(b, mode, precision) }
                // sqr has no explicit-width overload: the square of a 64-bit integer is
                // exact at the source width, so narrowing it is the correct rounding here
                withZeroSkip("sqr", Rational.of(bx.multiply(bx), BigInteger.ONE)) { mode ->
                    a.sqr(mode).add(MpfrFloat.Zero(Sign.POSITIVE, source), mode, precision)
                }
                withZeroSkip("fma", Rational.of(bx.multiply(by).add(BigInteger.ONE), BigInteger.ONE)) { mode ->
                    a.fma(b, one, mode, precision)
                }
                if (y != 0) {
                    withZeroSkip("div", Rational.of(bx, by)) { mode -> a.div(b, mode, precision) }
                }
            }

            for ((name, exact, call) in cases) {
                for (mode in RoundingMode.entries) {
                    val expected = roundToBinary(exact, precision.bits, mode)
                    assertSameValue(expected, call(mode)) {
                        "$name($x, $y) at ${precision.bits} bits under $mode (exact=$exact)"
                    }
                }
            }
        }
    }

    test("the oracle itself reproduces known hand-computed roundings") {
        if (skipIfNativeMissing()) return@test
        val third = Rational.of(BigInteger.ONE, BigInteger.valueOf(3))
        // 1/3 at 24 bits rounds up to the float32 significand 0x1.555556p-2.
        assertSameValue(
            MpfrFloat.parse("0x1.555556p-2", MpfrPrecision(24), RoundingMode.NEAREST_EVEN).valueOrFail(),
            roundToBinary(third, 24, RoundingMode.NEAREST_EVEN),
            { "1/3 at 24 bits, nearest-even" },
        )
        val threeHalves = Rational.of(BigInteger.valueOf(3), BigInteger.valueOf(2))
        assertSameValue(
            MpfrFloat.parse("0x1.8p0", MpfrPrecision(4), RoundingMode.NEAREST_EVEN).valueOrFail(),
            roundToBinary(threeHalves, 4, RoundingMode.NEAREST_EVEN),
            { "3/2 at 4 bits" },
        )
        // 5/8 = 0.101: at 1 bit this is closer to 1/2 than to 1.
        val fiveEighths = Rational.of(BigInteger.valueOf(5), BigInteger.valueOf(8))
        assertSameValue(
            MpfrFloat.parse("0x1p-1", MpfrPrecision(1), RoundingMode.NEAREST_EVEN).valueOrFail(),
            roundToBinary(fiveEighths, 1, RoundingMode.NEAREST_EVEN),
            { "5/8 at 1 bit, nearest-even" },
        )
    }
})