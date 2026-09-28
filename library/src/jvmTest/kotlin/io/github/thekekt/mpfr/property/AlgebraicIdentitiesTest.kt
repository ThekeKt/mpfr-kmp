package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.RoundingMode
import io.github.thekekt.mpfr.core.Sign
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.checkAll

/**
 * Algebraic identities that hold bitwise because both sides are the correctly
 * rounded value of the same exact expression: square vs self-multiplication,
 * n-th root vs the dedicated roots, integer powers vs repeated operations, fused
 * operations with a unit factor, definitional rewrites (`dim`, `hypot`, the
 * relative difference), and the parity symmetries of the trigonometric functions.
 */
class AlgebraicIdentitiesTest : FunSpec({

    test("sqr is self-multiplication and both agree bitwise") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.any) { x ->
            val precision = x.precision
            assertSameValue(x.mul(x, RoundingMode.NEAREST_EVEN, precision), x.sqr(RoundingMode.NEAREST_EVEN)) {
                "sqr(x) vs x*x"
            }
        }
    }

    test("root agrees with sqrt and cbrt at the matching degrees") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.any) { x ->
            // n-th root of -0 is +0 for even degrees while sqrt keeps -0: the
            // functions agree as values, so the comparison is numeric here.
            assertNumericSame(x.sqrt(RoundingMode.NEAREST_EVEN), x.root(2, RoundingMode.NEAREST_EVEN)) { "sqrt vs root(2)" }
            assertNumericSame(x.cbrt(RoundingMode.NEAREST_EVEN), x.root(3, RoundingMode.NEAREST_EVEN)) { "cbrt vs root(3)" }
            assertSameValue(x, x.root(1, RoundingMode.NEAREST_EVEN)) { "root(1) is the identity" }
        }
    }

    test("integer powers agree with multiplication and division") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.any) { x ->
            val precision = x.precision
            val one = MpfrFloat.of(1L, precision)
            val two = MpfrFloat.of(2L, precision)
            val zero = MpfrFloat.of(0L, precision)
            val minusOne = MpfrFloat.of(-1L, precision)
            val rnd = RoundingMode.NEAREST_EVEN

            assertSameValue(x.mul(x, rnd, precision), x.pow(two, rnd, precision)) { "pow(x, 2)" }
            assertSameValue(x, x.pow(one, rnd, precision)) { "pow(x, 1)" }
            assertSameValue(one, x.pow(zero, rnd, precision)) { "pow(x, 0)" }
            if (!x.isNaN) {
                assertSameValue(one.div(x, rnd, precision), x.pow(minusOne, rnd, precision)) { "pow(x, -1)" }
            }
        }
    }

    test("fused operations reduce to unfused ones for a unit factor") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.regularPair) { (a, b) ->
            val precision = maxOf(a.precision, b.precision)
            val one = MpfrFloat.of(1L, precision)
            val zero = MpfrFloat.Zero(Sign.POSITIVE, precision)
            val rnd = RoundingMode.NEAREST_EVEN
            assertSameValue(a.add(b, rnd, precision), a.fma(one, b, rnd, precision)) { "fma(a, 1, b) vs a+b" }
            assertSameValue(a.sub(b, rnd, precision), a.fms(one, b, rnd, precision)) { "fms(a, 1, b) vs a-b" }
            assertSameValue(a.mul(b, rnd, precision), a.fma(b, zero, rnd, precision)) { "fma(a, b, 0) vs a*b" }
            assertSameValue(a.mul(b, rnd, precision), a.fms(b, zero, rnd, precision)) { "fms(a, b, 0) vs a*b" }
        }
    }

    test("dim, hypot and the relative difference match their definitions") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.regularPair) { (a, b) ->
            val precision = maxOf(a.precision, b.precision)
            val rnd = RoundingMode.NEAREST_EVEN
            val zero = MpfrFloat.Zero(Sign.POSITIVE, precision)
            val aAtPrecision = a.add(zero, rnd, precision) // exact copy at the shared width

            val expectedDim = if (a.lt(b)) zero else a.sub(b, rnd, precision)
            assertSameValue(expectedDim, a.dim(b, rnd, precision)) { "dim(a, b)" }
            assertSameValue(aAtPrecision.abs(), a.hypot(zero, rnd, precision)) { "hypot(a, 0) vs |a|" }

            for (mode in RoundingMode.entries) {
                val composed = a.sub(b, mode, precision).abs().div(a, mode, precision)
                assertNumericSame(composed, a.relativeDifference(b, mode, precision)) { "reldiff under $mode" }
            }
        }
    }

    test("trigonometric parity: sin is odd and cos is even under every rounding mode") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.any) { x ->
            val negated = -x
            for (mode in RoundingMode.entries) {
                val flipped = when (mode) {
                    RoundingMode.NEAREST_EVEN -> RoundingMode.NEAREST_EVEN
                    RoundingMode.TOWARD_NEGATIVE -> RoundingMode.TOWARD_POSITIVE
                    RoundingMode.TOWARD_POSITIVE -> RoundingMode.TOWARD_NEGATIVE
                    RoundingMode.TOWARD_ZERO -> RoundingMode.TOWARD_ZERO
                    RoundingMode.AWAY_FROM_ZERO -> RoundingMode.AWAY_FROM_ZERO
                }
                assertSameValue(-(x.sin(flipped)), negated.sin(mode)) { "sin(-x) under $mode" }
                assertSameValue(x.cos(mode), negated.cos(mode)) { "cos(-x) under $mode" }
            }
        }
    }

    test("min/max are idempotent and negation/absolute value are involutions") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.any) { x ->
            val precision = x.precision
            val rnd = RoundingMode.NEAREST_EVEN
            assertSameValue(x, x.min(x, rnd, precision)) { "min(x, x)" }
            assertSameValue(x, x.max(x, rnd, precision)) { "max(x, x)" }
            assertSameValue(x, -(-x)) { "double negation" }
            assertSameValue(x.abs(), x.abs().abs()) { "abs is idempotent" }
            assertSameValue(x.abs(), (-x).abs()) { "abs ignores the sign" }
        }
    }
})
