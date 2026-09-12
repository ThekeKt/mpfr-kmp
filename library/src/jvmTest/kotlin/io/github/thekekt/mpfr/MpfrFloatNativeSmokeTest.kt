package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.core.MpfrParseError
import io.github.thekekt.mpfr.core.NumberStyle
import io.github.thekekt.mpfr.core.Result
import io.github.thekekt.mpfr.core.Sign
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end smoke through the JNI shim + bundled MPFR (the basic verification
 * tier; a golden-vector suite will refine coverage).
 * Exercises every bridge family; skipped when the native library is absent here.
 */
class MpfrFloatNativeSmokeTest {

    private fun L(v: Long, prec: Int = 64) = MpfrFloat.of(v, MpfrPrecision(prec))
    private fun D(v: Double, prec: Int = 64) = MpfrFloat.of(v, MpfrPrecision(prec))

    @Test fun piRoundTripsAtAnyPrecision() = runNative {
        val pi = MpfrFloat.PI(MpfrPrecision(256))
        val rendered = pi.toString()
        assertTrue(rendered.startsWith("3.141592653589793238462643383279502884197169399375"), "got $rendered")
        val reparsed = MpfrFloat.parse(rendered, MpfrPrecision(256), base = 10)
        assertTrue(reparsed is Result.Success)
        assertEquals(pi, (reparsed as Result.Success).value) // n=0 → MPFR-chosen exact round-trip
    }

    @Test fun eIsExpOfOneAtDoubleWidth() = runNative {
        assertEquals(2.718281828459045, MpfrFloat.E(MpfrPrecision(53)).toDouble())
    }

    @Test fun binaryOpsWidenToMax() = runNative {
        val a = L(1L, 64)
        val b = L(2L, 128)
        assertEquals(MpfrPrecision(128), (a * b).precision)
        assertEquals(2L, (a * b).toLong())
        assertEquals(MpfrPrecision(128), (a + b).precision)
        assertEquals(MpfrPrecision(128), (a - b).precision)
        assertEquals(-1L, (a - b).toLong())
        assertEquals(0.5, (a / b).toDouble())
    }

    @Test fun mixedScalarAndDoubleOps() = runNative {
        val x = L(10L)
        assertEquals(4L, (x - 6L).toLong())
        assertEquals(5.0, (x / 2.0).toDouble())
        assertEquals(20L, (x * 2L).toLong())
        assertEquals(15L, (x.add(2L).plus(3L)).toLong())
        assertEquals(-8L, (2L - x).toLong())
        assertEquals(Long.MAX_VALUE, L(0L).add(Long.MAX_VALUE).toLong())
        assertEquals(1.0, L(0L).add(0.5).add(0.5).toDouble())
    }

    @Test fun unaryFamilies() = runNative {
        assertEquals(4.0, L(16).sqrt().toDouble())
        assertEquals(3.0, L(27).cbrt().toDouble())
        assertEquals(0.5, L(4).reciprocalSqrt().toDouble())
        assertEquals(1.0, L(0).exp().toDouble())
        assertEquals(2.0, L(4).log2().toDouble())
        assertEquals(0.0, L(0).sin().toDouble())
        assertEquals(1.0, L(0).cos().toDouble())
        assertEquals(8.0, L(3).exp2().toDouble())
        assertEquals(4.0, L(2).sqr().toDouble())
        assertEquals(2L, L(8).root(3).toLong())
        assertEquals(2.0, D(2.7).truncate().toDouble())
        assertEquals(-3.0, D(-2.5).floor().toDouble())
        assertEquals(3L, D(2.5).round().toLong())
        assertEquals(2L, D(2.5).roundToEven().toLong())
        assertEquals(0.5, D(2.5).fractional().toDouble())
    }

    @Test fun fusedPairsConstSeries() = runNative {
        assertEquals(10L, L(2).fma(L(3), L(4)).toLong())
        assertEquals(2L, L(2).fms(L(4), L(6)).toLong())
        val parts = D(2.5).split()
        assertEquals(2L, parts.integer.toLong())
        assertEquals(0.5, parts.fraction.toDouble())
        val sc = L(0).sinCos()
        assertEquals(0.0, sc.sin.toDouble())
        assertEquals(1.0, sc.cos.toDouble())
        assertEquals(120L, MpfrFloat.factorial(5L, MpfrPrecision(64)).toLong())
        assertEquals(MpfrPrecision(128), MpfrFloat.sum(listOf(L(1, 64), L(1, 128))).precision)
        assertEquals(10L, MpfrFloat.sum(listOf(L(1), L(2), L(3), L(4)), MpfrPrecision(64)).toLong())
        assertEquals(32L, MpfrFloat.dot(
            listOf(L(1), L(2), L(3)),
            listOf(L(4), L(5), L(6)),
            MpfrPrecision(64),
        ).toLong())
    }

    @Test fun comparisonsAndSpecials() = runNative {
        val nan = MpfrFloat.NaN(MpfrPrecision(64))
        val one = L(1)
        assertTrue(one lt L(2))
        assertFalse(one lt nan)
        assertFalse(nan lt one)
        assertTrue(one.isUnorderedWith(nan))
        assertTrue(L(0) totalOrder L(1))
        val mz = MpfrFloat.Zero(Sign.NEGATIVE, MpfrPrecision(64))
        val pz = MpfrFloat.Zero(Sign.POSITIVE, MpfrPrecision(64))
        assertTrue(mz totalOrder pz)
        assertFalse(pz.totalOrder(mz)) // MPFR total order distinguishes -0 < +0
        assertEquals(-1, mz.compareTo(pz))
        val inv = L(1).div(L(0))
        assertTrue(inv.isInfinite && inv.isPositiveInf())
        assertTrue((L(0) / L(0)).isNaN)
        assertTrue(L(-4).sqrt().isNaN)
        MpfrExceptionFlags.set(MpfrExceptionFlag.INEXACT)
        L(1) + L(1) // op region must not leak a set flag out, and restores the saved state after
        assertEquals(1, MpfrExceptionFlags.test(MpfrFlagsMask.of(MpfrExceptionFlag.INEXACT)).size)
        MpfrExceptionFlags.clearAll()
        L(1).div(L(0))
        assertTrue(MpfrExceptionFlags.current().isEmpty()) // DIVBY0 delta discarded (per-op flag isolation)
    }

    private fun MpfrFloat.isPositiveInf(): Boolean = isInfinite && !isNegative

    @Test fun ulpsShiftsAndOverflow() = runNative {
        val one = L(1)
        val up = one.nextUp()
        assertNotEquals(one, up)
        assertTrue(one.equalUlps(up, 1))
        assertFalse(one.equalUlps(L(2), 1))
        assertEquals(-1, one.compareTo(up))
        assertEquals(1.0, up.nextDown().toDouble()) // exact round-trip neighbours at prec 64: nextDown(nextUp(1)) == 1
        // MPFR's exponent range (emax ≈ 2²⁶) makes 1e600 a regular value — no MPFR-side overflow;
        // saturation happens only on the narrowing get_d (MPFR ≠ Double semantics).
        val huge = D(1e300).mul(D(1e300))
        assertTrue(huge.isFinite)
        assertEquals(Double.POSITIVE_INFINITY, huge.toDouble())
        assertEquals(1024L, one.shl(10).toLong())
        // MPFR has no subnormals: 2⁻²⁰⁰⁰ is a regular finite value; get_d underflows to 0.0
        val tiny = one.shr(2000)
        assertTrue(tiny.isFinite && tiny.isPositive())
        assertEquals(0.0, tiny.toDouble())
        assertTrue(one.shl(10000).isFinite)
    }

    private fun MpfrFloat.isPositive(): Boolean = !isNegative && !isZero && !isNaN

    @Test fun narrowingAndBigValues() = runNative {
        val ten18 = MpfrFloat.parse("1234567890123456789", MpfrPrecision(64), base = 10)
        val big = (ten18 as Result.Success).value
        assertEquals(1234567890123456789L, big.toLong())
        assertEquals(BigInteger("1234567890123456789"), big.toBigInteger())
        assertTrue(big.fitsLong() && !big.fitsInt())
        assertTrue(MpfrFloat.of(BigInteger("2").pow(100), MpfrPrecision(128)).isInteger)
        val huge = one().shl(8000)
        assertFailsWith<MpfrRangeException> { huge.toLong() }
        assertEquals(2, D(2.7).toInt(RoundingMode.TOWARD_ZERO))
    }

    private fun one() = L(1)

    @Test fun parseAndFormatStrings() = runNative {
        val ok = MpfrFloat.parse("-12.5", MpfrPrecision(64), base = 10)
        assertTrue(ok is Result.Success && ok.value.toDouble() == -12.5, "parse -12.5 -> $ok")
        val auto = MpfrFloat.parse("0x1F", MpfrPrecision(64))
        assertTrue(auto is Result.Success && auto.value.toLong() == 31L, "parse 0x1F -> $auto")
        assertEquals(
            MpfrParseError.MalformedInput,
            (MpfrFloat.parse("abc", MpfrPrecision(64), base = 10) as Result.Failure).error,
        )
        assertEquals(
            MpfrParseError.ExponentSyntax,
            (MpfrFloat.parse("1e", MpfrPrecision(64), base = 10) as Result.Failure).error,
        )
        assertTrue(MpfrFloat.parse("5", MpfrPrecision(64), base = 63) is Result.Failure)
        assertEquals("1.0e2", L(100).toString(digits = 2))
        assertEquals("8.0e-1", D(0.8).toString(digits = 1))
        val sci = L(2).format(NumberStyle.SCIENTIFIC, digits = 1)
        assertTrue(sci.startsWith("2.0e"), "got $sci") // %R → exponent form
        assertEquals("NaN", MpfrFloat.NaN().toString())
    }
}
