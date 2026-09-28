package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.RoundingMode
import io.github.thekekt.mpfr.core.Sign
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Rounding properties over the arithmetic surface: directed-rounding bracketing
 * across every operation class, half-ulp ties at every exercised width, exactness
 * at sufficient precision, and the idempotence of directed rounding when a wide
 * result is narrowed to a target width.
 */
class RoundingPropertiesTest : FunSpec({

    test("directed roundings bracket every binary operation result") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.regularPair) { (a, b) ->
            val precision = maxOf(a.precision, b.precision)
            for ((name, op) in BINARY) {
                val down = op(a, b, RoundingMode.TOWARD_NEGATIVE, precision)
                val up = op(a, b, RoundingMode.TOWARD_POSITIVE, precision)
                check(down.compareTo(up) <= 0) {
                    "$name: toward-negative=$down above toward-positive=$up (a=$a b=$b)"
                }
                for (mode in RoundingMode.entries) {
                    val value = op(a, b, mode, precision)
                    check(down.compareTo(value) <= 0 && value.compareTo(up) <= 0) {
                        "$name under $mode produced $value outside [$down, $up] (a=$a b=$b)"
                    }
                }
            }
        }
    }

    test("a half-ulp tie resolves by rounding mode at every exercised width") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, Arb.int(0, TIE_PRECISIONS.lastIndex)) { index ->
            val bits = TIE_PRECISIONS[index]
            val precision = MpfrPrecision(bits)
            val one = MpfrFloat.of(1L, precision)
            val halfUlp = one.shr(bits) // 2^-bits, exact at every width
            val minusOne = -one
            val up = one.nextUp()
            val down = minusOne.nextDown()

            for (mode in RoundingMode.entries) {
                val positive = when (mode) {
                    RoundingMode.NEAREST_EVEN, RoundingMode.TOWARD_ZERO, RoundingMode.TOWARD_NEGATIVE -> one
                    RoundingMode.TOWARD_POSITIVE, RoundingMode.AWAY_FROM_ZERO -> up
                }
                assertSameValue(positive, one.add(halfUlp, mode, precision)) {
                    "positive tie at $bits bits under $mode"
                }
                val negative = when (mode) {
                    RoundingMode.NEAREST_EVEN, RoundingMode.TOWARD_ZERO, RoundingMode.TOWARD_POSITIVE -> minusOne
                    RoundingMode.TOWARD_NEGATIVE, RoundingMode.AWAY_FROM_ZERO -> down
                }
                assertSameValue(negative, minusOne.sub(halfUlp, mode, precision)) {
                    "negative tie at $bits bits under $mode"
                }
            }
        }
    }

    test("exact results at sufficient precision agree across all five modes") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, Arb.int(-65_536, 65_536), Arb.int(-65_536, 65_536)) { x, y ->
            val precision = MpfrPrecision(53)
            val a = MpfrFloat.of(x.toLong(), precision)
            val b = MpfrFloat.of(y.toLong(), precision)
            val exactOps: List<Pair<String, (RoundingMode) -> MpfrFloat>> = listOf(
                "add" to { r -> a.add(b, r, precision) },
                "sub" to { r -> a.sub(b, r, precision) },
                "mul" to { r -> a.mul(b, r, precision) },
                "sqr" to { r -> a.sqr(r) },
                "fma" to { r -> a.fma(b, MpfrFloat.of(1L, precision), r, precision) },
            )
            for ((name, op) in exactOps) {
                val reference = op(RoundingMode.NEAREST_EVEN)
                for (mode in RoundingMode.entries) {
                    assertSameValue(reference, op(mode)) {
                        "$name($x, $y): exact result differs under $mode"
                    }
                }
            }
        }
    }

    test("directed rounding is idempotent: wide result narrowed equals the direct rounding") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.regularPair, Arb.int(0, NARROW_WIDTHS.lastIndex)) { (a, b), index ->
            val narrow = MpfrPrecision(NARROW_WIDTHS[index])
            val wide = MpfrPrecision(NARROW_WIDTHS[index] * 4 + 13)
            val zero = MpfrFloat.Zero(Sign.POSITIVE, wide)
            for ((name, op) in CORE_OPS) {
                for (mode in DIRECTED) {
                    val viaWide = op(a, b, mode, wide).add(zero, mode, narrow)
                    val direct = op(a, b, mode, narrow)
                    assertSameValue(direct, viaWide) {
                        "$name($a, $b): $mode narrow=$narrow wide=$wide"
                    }
                }
                val wideResult = op(a, b, RoundingMode.NEAREST_EVEN, wide)
                if (allModesAgree(wideResult, op, a, b, wide)) {
                    val viaWide = wideResult.add(zero, RoundingMode.NEAREST_EVEN, narrow)
                    val direct = op(a, b, RoundingMode.NEAREST_EVEN, narrow)
                    assertSameValue(direct, viaWide) {
                        "$name($a, $b): nearest-even narrowing of an exact wide result"
                    }
                }
            }
        }
    }

    test("directed narrowing composes at the widest exercised precisions (mul/div)") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS_HEAVY, MpfrArbs.regularPair) { (a, b) ->
            val narrow = MpfrPrecision(53)
            for (bits in HIGH_WIDTHS) {
                val wide = MpfrPrecision(bits)
                val zero = MpfrFloat.Zero(Sign.POSITIVE, wide)
                for ((name, op) in listOf(MUL, DIV)) {
                    for (mode in DIRECTED) {
                        val viaWide = op(a, b, mode, wide).add(zero, mode, narrow)
                        val direct = op(a, b, mode, narrow)
                        assertSameValue(direct, viaWide) {
                            "$name($a, $b): $mode narrow=$narrow wide=$wide"
                        }
                    }
                }
            }
        }
    }

    test("exact integer products agree across modes and widths") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS_HEAVY, Arb.int(-65_536, 65_536), Arb.int(-65_536, 65_536)) { x, y ->
            val low = MpfrPrecision(53)
            val exactLow = MpfrFloat.of(x.toLong(), low).mul(MpfrFloat.of(y.toLong(), low), RoundingMode.NEAREST_EVEN, low)
            for (bits in HIGH_WIDTHS) {
                val precision = MpfrPrecision(bits)
                val a = MpfrFloat.of(x.toLong(), precision)
                val b = MpfrFloat.of(y.toLong(), precision)
                val product = a.mul(b, RoundingMode.NEAREST_EVEN, precision)
                for (mode in RoundingMode.entries) {
                    assertSameValue(product, a.mul(b, mode, precision)) {
                        "exact product $x*$y at $bits bits differs under $mode"
                    }
                }
                val narrowed = product.add(MpfrFloat.Zero(Sign.POSITIVE, precision), RoundingMode.NEAREST_EVEN, low)
                assertSameValue(exactLow, narrowed) { "exact product $x*$y narrowed from $bits bits" }
            }
        }
    }

    test("commutativity is bitwise at equal result precision") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.regularPair) { (a, b) ->
            val precision = maxOf(a.precision, b.precision)
            val rnd = RoundingMode.NEAREST_EVEN
            assertSameValue(a.add(b, rnd, precision), b.add(a, rnd, precision)) { "add is not commutative" }
            assertSameValue(a.mul(b, rnd, precision), b.mul(a, rnd, precision)) { "mul is not commutative" }
            assertSameValue(a.min(b, rnd, precision), b.min(a, rnd, precision)) { "min is not commutative" }
            assertSameValue(a.max(b, rnd, precision), b.max(a, rnd, precision)) { "max is not commutative" }
            assertSameValue(a.hypot(b, rnd, precision), b.hypot(a, rnd, precision)) { "hypot is not symmetric" }
        }
    }
}) {
    private companion object {
        val TIE_PRECISIONS = listOf(2, 3, 7, 8, 16, 24, 53, 64, 113)
        val NARROW_WIDTHS = listOf(2, 8, 24, 53)
        val HIGH_WIDTHS = listOf(1_024, 2_048, 4_096)
        val DIRECTED = listOf(
            RoundingMode.TOWARD_NEGATIVE,
            RoundingMode.TOWARD_POSITIVE,
            RoundingMode.TOWARD_ZERO,
        )
        val MUL = "mul" to { a: MpfrFloat, b: MpfrFloat, r: RoundingMode, p: MpfrPrecision -> a.mul(b, r, p) }
        val DIV = "div" to { a: MpfrFloat, b: MpfrFloat, r: RoundingMode, p: MpfrPrecision -> a.div(b, r, p) }
        val CORE_OPS: List<Pair<String, (MpfrFloat, MpfrFloat, RoundingMode, MpfrPrecision) -> MpfrFloat>> = listOf(
            "add" to { a, b, r, p -> a.add(b, r, p) },
            "sub" to { a, b, r, p -> a.sub(b, r, p) },
            "mul" to { a, b, r, p -> a.mul(b, r, p) },
            "div" to { a, b, r, p -> a.div(b, r, p) },
        )
        val BINARY: List<Pair<String, (MpfrFloat, MpfrFloat, RoundingMode, MpfrPrecision) -> MpfrFloat>> = listOf(
            "add" to { a, b, r, p -> a.add(b, r, p) },
            "sub" to { a, b, r, p -> a.sub(b, r, p) },
            "mul" to { a, b, r, p -> a.mul(b, r, p) },
            "div" to { a, b, r, p -> a.div(b, r, p) },
            "pow" to { a, b, r, p -> a.pow(b, r, p) },
            "atan2" to { a, b, r, p -> a.atan2(b, r, p) },
            "hypot" to { a, b, r, p -> a.hypot(b, r, p) },
            "dim" to { a, b, r, p -> a.dim(b, r, p) },
            "fmod" to { a, b, r, p -> a.fmod(b, r, p) },
            "remainder" to { a, b, r, p -> a.remainder(b, r, p) },
            "min" to { a, b, r, p -> a.min(b, r, p) },
            "max" to { a, b, r, p -> a.max(b, r, p) },
        )

        fun allModesAgree(
            reference: MpfrFloat,
            op: (MpfrFloat, MpfrFloat, RoundingMode, MpfrPrecision) -> MpfrFloat,
            a: MpfrFloat,
            b: MpfrFloat,
            precision: MpfrPrecision,
        ): Boolean = RoundingMode.entries.all { mode ->
            val value = op(a, b, mode, precision)
            if (reference.isNaN) value.isNaN else value == reference
        }
    }
}
