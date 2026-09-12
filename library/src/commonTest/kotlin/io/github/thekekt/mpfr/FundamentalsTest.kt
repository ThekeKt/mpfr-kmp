package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.core.MpfrKind
import io.github.thekekt.mpfr.core.Sign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MpfrPrecisionTest {
    @Test fun validBounds() {
        assertEquals(1, MpfrPrecision(MpfrPrecision.MIN_BITS).bits)
        assertEquals(MpfrPrecision.MAX_BITS, MpfrPrecision(MpfrPrecision.MAX_BITS.toLong()).bits)
    }

    @Test fun outOfRangeRejects() {
        assertFailsWith<IllegalArgumentException> { MpfrPrecision(0) }
        assertFailsWith<IllegalArgumentException> { MpfrPrecision(MpfrPrecision.MAX_BITS + 1) }
        assertFailsWith<IllegalArgumentException> { MpfrPrecision(-1) }
        assertFailsWith<IllegalArgumentException> { MpfrPrecision(0L) }
        assertFailsWith<IllegalArgumentException> { MpfrPrecision(MpfrPrecision.MAX_BITS.toLong() + 1) }
    }

    @Test fun longCtorNarrows() {
        assertEquals(53, MpfrPrecision(53L).bits)
        assertEquals(MpfrPrecision.DOUBLE_BITS, 53)
        assertEquals(MpfrPrecision.SINGLE_BITS, 24)
    }

    @Test fun comparableAndDisplay() {
        assertTrue(MpfrPrecision(24) < MpfrPrecision(53))
        assertEquals("53 bits", MpfrPrecision(53).toString())
        assertEquals(MpfrPrecision(53), MpfrPrecision(53))
        assertEquals(MpfrPrecision(53).hashCode(), MpfrPrecision(53).hashCode())
    }
}

class RoundingModeTest {
    @Test fun ordinalsMatchMpfrRndConstants() {
        // mpfr.h L102–L116: RNDN=0 RNDZ=1 RNDU=2 RNDD=3 RNDA=4
        assertEquals(0, RoundingMode.NEAREST_EVEN.mpfrValue)
        assertEquals(1, RoundingMode.TOWARD_ZERO.mpfrValue)
        assertEquals(2, RoundingMode.TOWARD_POSITIVE.mpfrValue)
        assertEquals(3, RoundingMode.TOWARD_NEGATIVE.mpfrValue)
        assertEquals(4, RoundingMode.AWAY_FROM_ZERO.mpfrValue)
    }

    @Test fun labelsMatchMpfrPrintRndMode() {
        assertEquals("N", RoundingMode.NEAREST_EVEN.toString())
        assertEquals("Z", RoundingMode.TOWARD_ZERO.toString())
        assertEquals("U", RoundingMode.TOWARD_POSITIVE.toString())
        assertEquals("D", RoundingMode.TOWARD_NEGATIVE.toString())
        assertEquals("A", RoundingMode.AWAY_FROM_ZERO.toString())
    }

    @Test fun defaultLiteralOnly() {
        // No global default; DEFAULT merely names the literal.
        assertEquals(RoundingMode.NEAREST_EVEN, RoundingMode.DEFAULT)
    }
}

class FlagsMaskTest {
    @Test fun bitOrderMirrorsMpfrMacros() {
        // mpfr.h L77–L82
        assertEquals(1, MpfrExceptionFlag.UNDERFLOW.mpfrBit)
        assertEquals(2, MpfrExceptionFlag.OVERFLOW.mpfrBit)
        assertEquals(4, MpfrExceptionFlag.NAN.mpfrBit)
        assertEquals(8, MpfrExceptionFlag.INEXACT.mpfrBit)
        assertEquals(16, MpfrExceptionFlag.ERANGE.mpfrBit)
        assertEquals(32, MpfrExceptionFlag.DIVBY0.mpfrBit)
    }

    @Test fun maskAlgebra() {
        // bit numbering of bitOrderMirrorsMpfrMacros: INEXACT=8, OVERFLOW=2, NAN=4
        val a = MpfrFlagsMask.of(MpfrExceptionFlag.INEXACT, MpfrExceptionFlag.OVERFLOW)
        val b = MpfrFlagsMask.of(MpfrExceptionFlag.NAN)
        assertTrue(MpfrExceptionFlag.INEXACT in a)
        assertFalse(MpfrExceptionFlag.NAN in a)
        assertEquals(14, (a + b).bits) // 8|2|4 disjoint union
        assertEquals(0, a.and(b).bits)
        assertEquals(a.bits, a.invert().invert().bits)
        assertEquals(0, MpfrFlagsMask.ALL.invert().bits)
        assertEquals(0b111_111, MpfrFlagsMask.ALL.bits)
        assertTrue((a + b).contains(MpfrExceptionFlag.NAN))
    }
}

class MpfrFloatSpecialsTest {
    // NaN/±Inf/±0 factories are pure (no FFI): the identity rules.

    @Test fun nanNeverEqualsAnything() {
        val a = MpfrFloat.NaN(MpfrPrecision(53))
        val b = MpfrFloat.NaN(MpfrPrecision(64))
        assertNotEquals(a, a) // deliberate deviation from Double — NaN never equals itself
        assertNotEquals(a, b)
    }

    @Test fun nanHashCodeIsStableSentinel() {
        // the single sentinel definition
        assertEquals(0x7FCBAD, MpfrFloat.NaN(MpfrPrecision(53)).hashCode())
        assertEquals(0x7FCBAD, MpfrFloat.NaN(MpfrPrecision(64)).hashCode())
    }

    @Test fun signedZerosAreDistinct() {
        val pz = MpfrFloat.Zero(Sign.POSITIVE, MpfrPrecision(53))
        val nz = MpfrFloat.Zero(Sign.NEGATIVE, MpfrPrecision(53))
        assertNotEquals(pz, nz)
        assertTrue(pz.isZero && nz.isZero)
        assertTrue(nz.isNegative)
        assertFalse(pz.isNegative)
        assertEquals(MpfrKind.ZERO, pz.kind)
        assertTrue(pz.isFinite && nz.isInteger)
    }

    @Test fun precisionIsPartOfIdentity() {
        val a = MpfrFloat.Zero(Sign.POSITIVE, MpfrPrecision(53))
        val b = MpfrFloat.Zero(Sign.POSITIVE, MpfrPrecision(64))
        assertNotEquals(a, b)
        assertEquals(MpfrPrecision(64), b.precision)
    }

    @Test fun nanAndInfAccessors() {
        val inf = MpfrFloat.Infinity(Sign.NEGATIVE, MpfrPrecision(53))
        assertTrue(inf.isInfinite)
        assertFalse(inf.isFinite)
        assertTrue(inf.isNegative)
        assertEquals(MpfrKind.INF, inf.kind)
        val nan = MpfrFloat.NaN(MpfrPrecision(53))
        assertFalse(nan.isInfinite)
        assertTrue(nan.isNaN)
        assertEquals(Sign.POSITIVE, nan.sign) // NaN carries no sign
        assertFalse(nan.isNegative)
    }

    @Test fun unaryMinusAndAbsOnSpecials() {
        val pz = MpfrFloat.Zero(Sign.POSITIVE, MpfrPrecision(53))
        val nz = MpfrFloat.Zero(Sign.NEGATIVE, MpfrPrecision(53))
        assertEquals(nz, -pz)
        assertEquals(pz, -nz)
        assertEquals(pz, nz.abs()) // abs(-0) = +0 (MPFR macro rule)
        val nan = MpfrFloat.NaN(MpfrPrecision(53))
        assertEquals(nan.repr.bytes.toList(), (-nan).repr.bytes.toList()) // NaN sign-immunity
    }
}
