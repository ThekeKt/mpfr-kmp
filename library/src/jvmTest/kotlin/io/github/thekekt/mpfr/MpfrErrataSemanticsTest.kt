package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.core.Sign
import io.github.thekekt.mpfr.internal.MpfrJni
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the semantics that the public KDoc and the op-table citations describe
 * (errata pass against the pinned MPFR sources):
 *
 * - `mpfr_min`/`mpfr_max`: a single NaN returns the OTHER operand (IEEE 754
 *   minNum/maxNum); NaN only when both operands are NaN; signed-zero rules.
 * - `mpfr_reldiff`: signed denominator `/op1`, not `/max(|op1|,|op2|)`.
 * - `mpfr_get_d`: saturates on overflow WITHOUT raising ERANGE.
 * - `mpfr_get_str` for zero: exponent written as 0 (the code is authoritative
 *   over the manual's "current minimal exponent" wording).
 * - `mpfr_dim`: NaN whenever any operand is NaN.
 */
class MpfrErrataSemanticsTest {

    private val p64 = MpfrPrecision(64)
    private fun L(v: Long) = MpfrFloat.of(v, p64)

    @Test
    fun minMaxNaNMatrix() = runNative {
        val nan = MpfrFloat.NaN(p64)
        val one = L(1)
        val two = L(2)
        // NaN × NaN → NaN
        assertTrue(nan.min(nan).isNaN)
        assertTrue(nan.max(nan).isNaN)
        // exactly one NaN → the other (non-NaN) operand, both orders
        assertEquals(one, nan.min(one))
        assertEquals(one, one.min(nan))
        assertEquals(one, nan.max(one))
        assertEquals(one, one.max(nan))
        // numeric pairs
        assertEquals(one, one.min(two))
        assertEquals(two, one.max(two))
        // signed zeros: min(−0,+0) = −0, max(−0,+0) = +0
        val mz = MpfrFloat.Zero(Sign.NEGATIVE, p64)
        val pz = MpfrFloat.Zero(Sign.POSITIVE, p64)
        assertEquals(mz, mz.min(pz))
        assertEquals(pz, mz.max(pz))
    }

    @Test
    fun relativeDifferenceUsesSignedFirstOperandDenominator() = runNative {
        // |a − b| / a with the SIGNED a: a = −2, b = −3 → |1| / (−2) = −0.5
        assertEquals(-0.5, L(-2).relativeDifference(L(-3)).toDouble())
        // positive control: a = 4, b = 1 → 3/4
        assertEquals(0.75, L(4).relativeDifference(L(1)).toDouble())
    }

    @Test
    fun toDoubleSaturatesWithoutRangeFlags() = runNative {
        val huge = MpfrFloat.of(1e300, p64).mul(MpfrFloat.of(1e300, p64)) // ≈1e600: finite in MPFR
        assertTrue(huge.isFinite)
        MpfrExceptionFlags.clearAll()
        assertEquals(Double.POSITIVE_INFINITY, huge.toDouble())
        assertEquals(Double.MAX_VALUE, huge.toDouble(RoundingMode.TOWARD_ZERO))
        assertEquals(Double.MAX_VALUE, huge.toDouble(RoundingMode.TOWARD_NEGATIVE))
        assertEquals(Double.NEGATIVE_INFINITY, (-huge).toDouble())
        assertEquals(-Double.MAX_VALUE, (-huge).toDouble(RoundingMode.TOWARD_ZERO))
        assertEquals(-Double.MAX_VALUE, (-huge).toDouble(RoundingMode.TOWARD_POSITIVE))
        // get_d raises no ERANGE (contrast: the integer narrowings do)
        assertTrue(MpfrExceptionFlags.current().isEmpty(), "toDouble must not raise flags")
        // underflow side: ±0
        assertEquals(0.0, L(1).shr(2000).toDouble())
    }

    @Test
    fun getStrOfZeroWritesExponentZero() = runNative {
        val zero = MpfrFloat.Zero(Sign.POSITIVE, p64)
        val header = LongArray(2)
        val digits = MpfrJni.getStrOp(zero.repr.bytes, 10, 0, 0, header)
        val rendered = String(digits)
        assertTrue(rendered.isNotEmpty() && rendered.all { it == '0' }, "got '$rendered'")
        assertEquals(0L, header[1], "get_str of zero must write exponent 0 (code over manual)")
        // the Kotlin rendering path keeps the plain labels
        assertEquals("0", zero.toString())
        assertEquals("-0", MpfrFloat.Zero(Sign.NEGATIVE, p64).toString())
    }

    @Test
    fun dimIsNaNOnAnyNaN() = runNative {
        val nan = MpfrFloat.NaN(p64)
        assertTrue(L(1).dim(nan).isNaN)
        assertTrue(nan.dim(L(1)).isNaN)
        // numeric behaviour: max(op1 − op2, +0)
        assertTrue(L(1).dim(L(2)).isZero)
        assertEquals(1L, L(3).dim(L(2)).toLong())
    }
}
