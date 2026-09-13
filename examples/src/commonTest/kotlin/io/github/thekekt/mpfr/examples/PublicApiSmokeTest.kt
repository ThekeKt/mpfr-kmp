package io.github.thekekt.mpfr.examples

import io.github.thekekt.mpfr.MpfrExceptionFlag
import io.github.thekekt.mpfr.MpfrExceptionFlags
import io.github.thekekt.mpfr.MpfrFlagsMask
import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.MpfrRangeException
import io.github.thekekt.mpfr.RoundingMode
import io.github.thekekt.mpfr.core.MpfrParseError
import io.github.thekekt.mpfr.core.NumberStyle
import io.github.thekekt.mpfr.core.Result
import io.github.thekekt.mpfr.core.Sign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Smoke coverage of the v0.0.1 surface driven as an external consumer: every
 * call crosses the `:library` boundary through public declarations only. On
 * hosts without the native backend the bodies print a skip; Linux/CI (and any
 * host with the shim discovered) run them for real.
 */
class PublicApiSmokeTest {

    private fun L(v: Long, prec: Int = 64) = MpfrFloat.of(v, MpfrPrecision(prec))
    private fun D(v: Double, prec: Int = 64) = MpfrFloat.of(v, MpfrPrecision(prec))

    private inline fun native(body: () -> Unit) {
        if (!ExamplesRuntime.mpfrAvailable) {
            println("SKIPPED (MPFR native backend unavailable on this host)")
        } else {
            body()
        }
    }

    @Test fun piRoundTripsAtAnyPrecision() = native {
        val pi = MpfrFloat.PI(MpfrPrecision(256))
        val rendered = pi.toString()
        assertTrue(rendered.startsWith("3.141592653589793238462643383279502884197169399375"), "got $rendered")
        val reparsed = MpfrFloat.parse(rendered, MpfrPrecision(256), base = 10)
        assertTrue(reparsed is Result.Success)
        assertEquals(pi, (reparsed as Result.Success).value)
    }

    @Test fun eIsExpOfOneAtDoubleWidth() = native {
        assertEquals(2.718281828459045, MpfrFloat.E(MpfrPrecision(53)).toDouble())
    }

    @Test fun binaryOpsWidenToMax() = native {
        val a = L(1L, 64)
        val b = L(2L, 128)
        assertEquals(MpfrPrecision(128), (a * b).precision)
        assertEquals(2L, (a * b).toLong())
        assertEquals(MpfrPrecision(128), (a + b).precision)
        assertEquals(-1L, (a - b).toLong())
        assertEquals(0.5, (a / b).toDouble())
    }

    @Test fun unaryFamilies() = native {
        assertEquals(4.0, L(16).sqrt().toDouble())
        assertEquals(3.0, L(27).cbrt().toDouble())
        assertEquals(0.5, L(4).reciprocalSqrt().toDouble())
        assertEquals(1.0, L(0).exp().toDouble())
        assertEquals(2.0, L(4).log2().toDouble())
        assertEquals(0.0, L(0).sin().toDouble())
        assertEquals(1.0, L(0).cos().toDouble())
        assertEquals(2L, L(8).root(3).toLong())
        assertEquals(2.0, D(2.7).truncate().toDouble())
        assertEquals(-3.0, D(-2.5).floor().toDouble())
        assertEquals(3L, D(2.5).round().toLong())
    }

    @Test fun fusedPairsConstSeries() = native {
        assertEquals(10L, L(2).fma(L(3), L(4)).toLong())
        val parts = D(2.5).split()
        assertEquals(2L, parts.integer.toLong())
        assertEquals(0.5, parts.fraction.toDouble())
        val sc = L(0).sinCos()
        assertEquals(0.0, sc.sin.toDouble())
        assertEquals(1.0, sc.cos.toDouble())
        assertEquals(120L, MpfrFloat.factorial(5L, MpfrPrecision(64)).toLong())
        assertEquals(10L, MpfrFloat.sum(listOf(L(1), L(2), L(3), L(4)), MpfrPrecision(64)).toLong())
        assertEquals(32L, MpfrFloat.dot(
            listOf(L(1), L(2), L(3)),
            listOf(L(4), L(5), L(6)),
            MpfrPrecision(64),
        ).toLong())
    }

    @Test fun comparisonsAndSpecials() = native {
        val nan = MpfrFloat.NaN(MpfrPrecision(64))
        val one = L(1)
        assertTrue(one lt L(2))
        assertFalse(one lt nan)
        assertTrue(one.isUnorderedWith(nan))
        assertTrue(L(0) totalOrder L(1))
        val mz = MpfrFloat.Zero(Sign.NEGATIVE, MpfrPrecision(64))
        val pz = MpfrFloat.Zero(Sign.POSITIVE, MpfrPrecision(64))
        assertTrue(mz totalOrder pz)
        assertFalse(pz.totalOrder(mz))
        assertNotEquals(nan, nan)
        assertNotEquals(mz, pz)
        assertEquals(pz, mz.abs())
        assertTrue((L(0) / L(0)).isNaN)
        assertTrue(L(-4).sqrt().isNaN)
        MpfrExceptionFlags.set(MpfrExceptionFlag.INEXACT)
        L(1) + L(1)
        assertEquals(1, MpfrExceptionFlags.test(MpfrFlagsMask.of(MpfrExceptionFlag.INEXACT)).size)
        MpfrExceptionFlags.clearAll()
        L(1).div(L(0))
        assertTrue(MpfrExceptionFlags.current().isEmpty())
    }

    @Test fun ulpsShiftsAndOverflow() = native {
        val one = L(1)
        val up = one.nextUp()
        assertNotEquals(one, up)
        assertTrue(one.equalUlps(up, 1))
        assertFalse(one.equalUlps(L(2), 1))
        assertEquals(1.0, up.nextDown().toDouble())
        val huge = D(1e300).mul(D(1e300))
        assertTrue(huge.isFinite)
        assertEquals(Double.POSITIVE_INFINITY, huge.toDouble())
        assertEquals(1024L, one.shl(10).toLong())
        val tiny = one.shr(2000)
        assertTrue(tiny.isFinite && !tiny.isNegative && !tiny.isZero)
        assertEquals(0.0, tiny.toDouble())
    }

    @Test fun narrowingOnBigValues() = native {
        val big = (MpfrFloat.parse("1234567890123456789", MpfrPrecision(64), base = 10) as Result.Success).value
        assertEquals(1234567890123456789L, big.toLong())
        assertTrue(big.fitsLong() && !big.fitsInt())
        assertFailsWith<MpfrRangeException> { big.shl(8000).toLong() }
        assertEquals(2, D(2.7).toInt(RoundingMode.TOWARD_ZERO))
    }

    @Test fun parseAndFormatStrings() = native {
        val ok = MpfrFloat.parse("-12.5", MpfrPrecision(64), base = 10)
        assertTrue(ok is Result.Success && ok.value.toDouble() == -12.5)
        val auto = MpfrFloat.parse("0x1F", MpfrPrecision(64))
        assertTrue(auto is Result.Success && auto.value.toLong() == 31L)
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
        val sci = L(2).format(NumberStyle.SCIENTIFIC, digits = 1)
        assertTrue(sci.startsWith("2.0e"), "got $sci")
        assertEquals("NaN", MpfrFloat.NaN().toString())
    }
}
