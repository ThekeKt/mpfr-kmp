package io.github.thekekt.mpfr.golden

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.RoundingMode
import io.github.thekekt.mpfr.runNative
import kotlin.test.Test

/**
 * Reference-vector tier for the arithmetic surface: binary, fused, unary,
 * scalar, mixed-double, pair, constants, ties, high precision, the trig grid
 * and factorial. Every row rebuilds its inputs through the public constructors,
 * calls the public wrapper method, and compares the result structurally with
 * the recorded oracle value.
 */
class GoldenOpsTest {

    // ---- operation tables (fixture op name -> public wrapper call) ----

    private val binary = mapOf<String, (MpfrFloat, MpfrFloat, RoundingMode, MpfrPrecision) -> MpfrFloat>(
        "ADD" to { a, b, r, p -> a.add(b, r, p) },
        "SUB" to { a, b, r, p -> a.sub(b, r, p) },
        "MUL" to { a, b, r, p -> a.mul(b, r, p) },
        "DIV" to { a, b, r, p -> a.div(b, r, p) },
        "POW" to { a, b, r, p -> a.pow(b, r, p) },
        "ATAN2" to { a, b, r, p -> a.atan2(b, r, p) },
        "MIN" to { a, b, r, p -> a.min(b, r, p) },
        "MAX" to { a, b, r, p -> a.max(b, r, p) },
        "DIM" to { a, b, r, p -> a.dim(b, r, p) },
        "HYPOT" to { a, b, r, p -> a.hypot(b, r, p) },
        "AGM" to { a, b, r, p -> a.agm(b, r, p) },
        "FMOD" to { a, b, r, p -> a.fmod(b, r, p) },
        "REMAINDER" to { a, b, r, p -> a.remainder(b, r, p) },
        "RELDIFF" to { a, b, r, p -> a.relativeDifference(b, r, p) },
    )

    private val fused = mapOf<String, (MpfrFloat, MpfrFloat, MpfrFloat, RoundingMode, MpfrPrecision) -> MpfrFloat>(
        "FMA" to { a, b, c, r, p -> a.fma(b, c, r, p) },
        "FMS" to { a, b, c, r, p -> a.fms(b, c, r, p) },
    )

    private val unary = mapOf<String, (MpfrFloat, RoundingMode) -> MpfrFloat>(
        "SQR" to { x, r -> x.sqr(r) },
        "SQRT" to { x, r -> x.sqrt(r) },
        "CBRT" to { x, r -> x.cbrt(r) },
        "REC_SQRT" to { x, r -> x.reciprocalSqrt(r) },
        "EXP" to { x, r -> x.exp(r) },
        "EXP2" to { x, r -> x.exp2(r) },
        "EXP10" to { x, r -> x.exp10(r) },
        "EXPM1" to { x, r -> x.expm1(r) },
        "EXP2M1" to { x, r -> x.exp2m1(r) },
        "EXP10M1" to { x, r -> x.exp10m1(r) },
        "LOG" to { x, r -> x.log(r) },
        "LOG2" to { x, r -> x.log2(r) },
        "LOG10" to { x, r -> x.log10(r) },
        "LOG1P" to { x, r -> x.log1p(r) },
        "LOG2P1" to { x, r -> x.log2p1(r) },
        "LOG10P1" to { x, r -> x.log10p1(r) },
        "SIN" to { x, r -> x.sin(r) },
        "COS" to { x, r -> x.cos(r) },
        "TAN" to { x, r -> x.tan(r) },
        "ASIN" to { x, r -> x.asin(r) },
        "ACOS" to { x, r -> x.acos(r) },
        "ATAN" to { x, r -> x.atan(r) },
        "SINH" to { x, r -> x.sinh(r) },
        "COSH" to { x, r -> x.cosh(r) },
        "TANH" to { x, r -> x.tanh(r) },
        "ASINH" to { x, r -> x.asinh(r) },
        "ACOSH" to { x, r -> x.acosh(r) },
        "ATANH" to { x, r -> x.atanh(r) },
        "SINPI" to { x, r -> x.sinpi(r) },
        "COSPI" to { x, r -> x.cospi(r) },
        "TANPI" to { x, r -> x.tanpi(r) },
        "ERF" to { x, r -> x.erf(r) },
        "ERFC" to { x, r -> x.erfc(r) },
        "GAMMA" to { x, r -> x.gamma(r) },
        "LNGAMMA" to { x, r -> x.lngamma(r) },
        "DIGAMMA" to { x, r -> x.digamma(r) },
        "ZETA" to { x, r -> x.zeta(r) },
        "TRUNC" to { x, r -> x.truncate(r) },
        "FLOOR" to { x, _ -> x.floor() },
        "CEIL" to { x, _ -> x.ceil() },
        "ROUND" to { x, _ -> x.round() },
        "ROUND_EVEN" to { x, _ -> x.roundToEven() },
        "FRAC" to { x, r -> x.fractional(r) },
        "NEXTUP" to { x, _ -> x.nextUp() },
        "NEXTDOWN" to { x, _ -> x.nextDown() },
    )

    private val scalar = mapOf<String, (MpfrFloat, Long, RoundingMode) -> MpfrFloat>(
        "ADD" to { x, k, r -> x.add(k, r) },
        "SUB" to { x, k, r -> x.sub(k, r) },
        "MUL" to { x, k, r -> x.mul(k, r) },
        "DIV" to { x, k, r -> x.div(k, r) },
        "MUL_2EXP" to { x, k, r -> x.shl(k.toInt(), r) },
        "DIV_2EXP" to { x, k, r -> x.shr(k.toInt(), r) },
        "ROOTN" to { x, k, r -> x.root(k.toInt(), r) },
        "COMPOUND" to { x, k, r -> x.compound(k, r) },
    )

    private val mixedDouble = mapOf<String, (MpfrFloat, Double, RoundingMode) -> MpfrFloat>(
        "ADD" to { x, d, r -> x.add(d, r) },
        "SUB" to { x, d, r -> x.sub(d, r) },
        "MUL" to { x, d, r -> x.mul(d, r) },
        "DIV" to { x, d, r -> x.div(d, r) },
    )

    // ---- row drivers ----

    private fun runBinary(row: GoldenRow) {
        val precOut = precOf(row["prec_out"], row)
        val a = tokenToFloat(row["a"], precOf(row["prec_a"], row), row)
        val b = tokenToFloat(row["b"], precOf(row["prec_b"], row), row)
        val fn = binary.getValue(row["op"])
        assertSameValue(row["value"], precOut, fn(a, b, rndOf(row["rnd"], row), precOut), row)
    }

    private fun runFused(row: GoldenRow) {
        val precOut = precOf(row["prec_out"], row)
        val a = tokenToFloat(row["a"], precOf(row["prec_a"], row), row)
        val b = tokenToFloat(row["b"], precOf(row["prec_b"], row), row)
        val c = tokenToFloat(row["c"], precOf(row["prec_c"], row), row)
        val fn = fused.getValue(row["op"])
        assertSameValue(row["value"], precOut, fn(a, b, c, rndOf(row["rnd"], row), precOut), row)
    }

    private fun runUnary(row: GoldenRow) {
        val precOut = precOf(row["prec_out"], row)
        val x = tokenToFloat(row["x"], precOf(row["prec_in"], row), row)
        val fn = unary.getValue(row["op"])
        assertSameValue(row["value"], precOut, fn(x, rndOf(row["rnd"], row)), row)
    }

    private fun runScalar(row: GoldenRow) {
        val precOut = precOf(row["prec_out"], row)
        val x = tokenToFloat(row["x"], precOf(row["prec_in"], row), row)
        val fn = scalar.getValue(row["op"])
        assertSameValue(row["value"], precOut, fn(x, row["k"].toLong(), rndOf(row["rnd"], row)), row)
    }

    private fun runMixedDouble(row: GoldenRow) {
        val precOut = precOf(row["prec_out"], row)
        val x = tokenToFloat(row["x"], precOf(row["prec_in"], row), row)
        val d = Double.fromBits(row["d_bits"].toULong(16).toLong())
        val fn = mixedDouble.getValue(row["op"])
        assertSameValue(row["value"], precOut, fn(x, d, rndOf(row["rnd"], row)), row)
    }

    private fun runPair(row: GoldenRow) {
        val precOut = precOf(row["prec_out"], row)
        val x = tokenToFloat(row["x"], precOf(row["prec_in"], row), row)
        val r = rndOf(row["rnd"], row)
        when (row["op"]) {
            "MODF" -> {
                val parts = x.split(r)
                assertSameValue(row["value"], precOut, parts.integer, row)
                assertSameValue(row["value2"], precOut, parts.fraction, row)
            }
            "SIN_COS" -> {
                val parts = x.sinCos(r, precOut)
                assertSameValue(row["value"], precOut, parts.sin, row)
                assertSameValue(row["value2"], precOut, parts.cos, row)
            }
            else -> error("${row.rowId}: unknown pair operation '${row["op"]}'")
        }
    }

    // ---- test methods by file x category ----

    @Test fun binaryCore() = runNative { GoldenVectors.file("ops-binary.tsv").rowsWhere("CORE") { runBinary(it) } }
    @Test fun binarySpecials() = runNative { GoldenVectors.file("ops-binary.tsv").rowsWhere("SPECIAL") { runBinary(it) } }
    @Test fun binaryTies() = runNative { GoldenVectors.file("ops-binary.tsv").rowsWhere("TIE") { runBinary(it) } }
    @Test fun binaryHighPrecision() = runNative { GoldenVectors.file("ops-binary.tsv").rowsWhere("HIGHPREC") { runBinary(it) } }

    @Test fun fusedCore() = runNative { GoldenVectors.file("ops-fused.tsv").rowsWhere("CORE") { runFused(it) } }
    @Test fun fusedSpecials() = runNative { GoldenVectors.file("ops-fused.tsv").rowsWhere("SPECIAL") { runFused(it) } }
    @Test fun fusedTies() = runNative { GoldenVectors.file("ops-fused.tsv").rowsWhere("TIE") { runFused(it) } }

    @Test fun unaryCore() = runNative { GoldenVectors.file("ops-unary.tsv").rowsWhere("CORE") { runUnary(it) } }
    @Test fun unarySpecials() = runNative { GoldenVectors.file("ops-unary.tsv").rowsWhere("SPECIAL") { runUnary(it) } }
    @Test fun unaryGrid() = runNative { GoldenVectors.file("ops-unary.tsv").rowsWhere("GRID") { runUnary(it) } }
    @Test fun unaryHighPrecision() = runNative { GoldenVectors.file("ops-unary.tsv").rowsWhere("HIGHPREC") { runUnary(it) } }

    @Test fun scalarCore() = runNative { GoldenVectors.file("ops-scalar.tsv").rowsWhere("CORE") { runScalar(it) } }
    @Test fun scalarSpecials() = runNative { GoldenVectors.file("ops-scalar.tsv").rowsWhere("SPECIAL") { runScalar(it) } }

    @Test fun doubleCore() = runNative { GoldenVectors.file("ops-double.tsv").rowsWhere("CORE") { runMixedDouble(it) } }
    @Test fun doubleSpecials() = runNative { GoldenVectors.file("ops-double.tsv").rowsWhere("SPECIAL") { runMixedDouble(it) } }
    @Test fun doubleTies() = runNative { GoldenVectors.file("ops-double.tsv").rowsWhere("TIE") { runMixedDouble(it) } }

    @Test fun pairCore() = runNative { GoldenVectors.file("ops-pair.tsv").rowsWhere("CORE") { runPair(it) } }
    @Test fun pairSpecials() = runNative { GoldenVectors.file("ops-pair.tsv").rowsWhere("SPECIAL") { runPair(it) } }
    @Test fun pairGrid() = runNative { GoldenVectors.file("ops-pair.tsv").rowsWhere("GRID") { runPair(it) } }

    @Test fun consts() = runNative {
        GoldenVectors.file("consts.tsv").rowsWhere("CORE") { row ->
            val prec = precOf(row["prec_out"], row)
            val r = rndOf(row["rnd"], row)
            val actual = when (row["op"]) {
                "PI" -> MpfrFloat.PI(prec, r)
                "E" -> MpfrFloat.E(prec, r)
                else -> error("${row.rowId}: unknown constant '${row["op"]}'")
            }
            assertSameValue(row["value"], prec, actual, row)
        }
    }

    @Test fun factorial() = runNative {
        GoldenVectors.file("factorial.tsv").rowsWhere("CORE") { row ->
            val prec = precOf(row["prec_out"], row)
            val actual = MpfrFloat.factorial(row["n"].toLong(), prec, rndOf(row["rnd"], row))
            assertSameValue(row["value"], prec, actual, row)
        }
    }
}