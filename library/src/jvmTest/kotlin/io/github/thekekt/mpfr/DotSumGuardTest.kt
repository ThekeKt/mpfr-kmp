package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.core.MpfrKind
import io.github.thekekt.mpfr.core.Sign
import io.github.thekekt.mpfr.internal.MpfrBinaryOp
import io.github.thekekt.mpfr.internal.MpfrJni
import io.github.thekekt.mpfr.internal.MpfrUnaryOp
import io.github.thekekt.mpfr.internal.Repr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exponent-domain guards of the native bridge (crash-safety tier).
 *
 * Upstream `mpfr_dot` multiplies each pair exactly and asserts the multiply was
 * exact; an intermediate product that overflows or underflows the exponent range
 * would abort the whole process. The shim therefore rejects such domains with an
 * [IllegalArgumentException] instead. These tests pin both sides of every guard
 * boundary: just inside — the operation completes; just outside — a typed
 * exception, never a crash.
 *
 * Boundary arithmetic uses this build's runtime exponent range defaults
 * (±(2^30 − 1), per the MPFR manual's default emin/emax); the guards themselves
 * read the range at runtime.
 */
class DotSumGuardTest {

    /** Default `mpfr_get_emax()` of the bundled build. */
    private val emaxDefault = 1073741823L

    /** Default `mpfr_get_emin()` of the bundled build. */
    private val eminDefault = -1073741823L

    private val p64 = MpfrPrecision(64)

    /** 2^n with MPFR exponent (EXP) = n + 1. */
    private fun pow2(n: Int): MpfrFloat = MpfrFloat.of(1L, p64).shl(n)

    /** 2^-n with MPFR exponent (EXP) = -n + 1. */
    private fun pow2neg(n: Int): MpfrFloat = MpfrFloat.of(1L, p64).shr(n)

    // ---- layer II: dot intermediate overflow -----------------------------------------

    @Test
    fun dotRejectsIntermediateProductOverflow() = runNative {
        // EXP(a) + EXP(b) = 2^29 + 2^29 = emax + 1 → the exact product exceeds the range
        val a = pow2(536870911)
        assertFailsWith<IllegalArgumentException> {
            MpfrFloat.dot(listOf(a), listOf(a), p64)
        }
        // the JVM is alive: the guard threw instead of aborting
        assertEquals(2L, (MpfrFloat.of(1L, p64) + MpfrFloat.of(1L, p64)).toLong())
    }

    @Test
    fun dotAcceptsOverflowBoundary() = runNative {
        // EXP(a) + EXP(b) = (2^29 − 1) + 2^29 = emax → exact product still representable
        val a = pow2(536870910)
        val b = pow2(536870911)
        val r = MpfrFloat.dot(listOf(a), listOf(b), p64)
        assertEquals(MpfrKind.REGULAR, r.kind)
        assertTrue(r.isFinite)
    }

    @Test
    fun dotRejectsIntermediateProductUnderflow() = runNative {
        // MPFR has no gradual subnormals: the smallest positive value is 2^(emin−1),
        // so a product with EXP(a)+EXP(b) = emin can fall below it and flush to zero
        // inexactly — upstream would abort on the exactness assert
        val a = pow2neg(536870912) // EXP = −536870911
        val b = pow2neg(536870913) // EXP = −536870912 → s = emin → rejected
        assertFailsWith<IllegalArgumentException> {
            MpfrFloat.dot(listOf(a), listOf(b), p64)
        }
    }

    @Test
    fun dotAcceptsUnderflowBoundary() = runNative {
        // EXP(a)+EXP(b) = emin + 1 → the exact product is at least the smallest
        // positive value 2^(emin−1): representable exactly, no abort
        val a = pow2neg(536870912) // EXP = −536870911, s = emin + 1 → accepted
        val r = MpfrFloat.dot(listOf(a), listOf(a), p64)
        assertEquals(MpfrKind.REGULAR, r.kind) // product = 2^(emin−1) exactly
    }

    // ---- layer II: guard skips special elements ---------------------------------------

    @Test
    fun dotWithSpecialsNeverHitsTheGuard() = runNative {
        val huge = pow2(536870911) // EXP = 2^29: any REG×REG pair with it would overflow
        val a = listOf(MpfrFloat.NaN(p64), MpfrFloat.Infinity(Sign.POSITIVE, p64),
            MpfrFloat.Zero(Sign.NEGATIVE, p64), MpfrFloat.of(1L, p64))
        val b = listOf(huge, MpfrFloat.of(1L, p64), MpfrFloat.of(1L, p64), huge)
        // pairs: NaN×huge, Inf×1, (−0)×1 are exact specials — guard skips them;
        // the REG×REG pair 1×huge is far inside the domain
        val r = MpfrFloat.dot(a, b, p64)
        assertTrue(r.isNaN) // NaN product poisons the accumulation
    }

    // ---- layer II: sum (n≥3 accumulator path) ------------------------------------------

    @Test
    fun sumWithExtremeButLegalExponentsDoesNotAbort() = runNative {
        // each element EXP = 2^29 (≈ emax/2): legal regulars, n = 3 → the upstream
        // accumulator path; the exact sum stays inside the range → normal result
        val x = pow2(536870911)
        val r = MpfrFloat.sum(listOf(x, x, x), p64)
        assertEquals(MpfrKind.REGULAR, r.kind)
        // cancellation across the accumulator path
        val c = MpfrFloat.sum(listOf(x, -x, x), MpfrPrecision(128))
        assertEquals(MpfrKind.REGULAR, c.kind)
    }

    @Test
    fun sumDelegationBranches() = runNative {
        // n = 1 (mpfr_set), n = 2 (mpfr_add), n ≥ 3 (accumulator): all three upstream routes
        val one = MpfrFloat.of(1L, p64)
        assertEquals(one, MpfrFloat.sum(listOf(one), p64))
        assertEquals(3L, MpfrFloat.sum(listOf(one, MpfrFloat.of(2L, p64)), p64).toLong())
        assertEquals(10L, MpfrFloat.sum(listOf(one, MpfrFloat.of(2L, p64), MpfrFloat.of(3L, p64),
            MpfrFloat.of(4L, p64)), p64).toLong())
        // specials mixed into the n≥3 route: ±Inf of one sign wins, opposite infinities → NaN
        val inf = MpfrFloat.Infinity(Sign.POSITIVE, p64)
        assertTrue(MpfrFloat.sum(listOf(one, inf, MpfrFloat.of(2L, p64)), p64).isInfinite)
        assertTrue(MpfrFloat.sum(listOf(one, inf, -inf, MpfrFloat.of(2L, p64)), p64).isNaN)
    }

    // ---- layer I: canonical exponent band at the wire boundary --------------------------

    private fun craftedRegular(exponent: Long): ByteArray =
        Repr.regular(p64, signbit = false, exponent = exponent,
            mantissaBE = byteArrayOf(0x80.toByte())).bytes

    @Test
    fun shimRejectsExponentAboveTheCanonicalBand() = runNative {
        val above = craftedRegular(emaxDefault + 1)
        assertFailsWith<IllegalArgumentException> { MpfrJni.sumOp(arrayOf(above), 64, 0) }
        assertFailsWith<IllegalArgumentException> {
            MpfrJni.dotOp(arrayOf(above), arrayOf(craftedRegular(0)), 64, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MpfrJni.binaryOp(above, craftedRegular(0), 64, 0, MpfrBinaryOp.ADD)
        }
    }

    @Test
    fun shimRejectsExponentBelowTheCanonicalBand() = runNative {
        val below = craftedRegular(eminDefault - 64 - 9) // one step under the band floor (emin − prec − 8)
        assertFailsWith<IllegalArgumentException> { MpfrJni.sumOp(arrayOf(below), 64, 0) }
        val bottom = craftedRegular(Long.MIN_VALUE)
        assertFailsWith<IllegalArgumentException> { MpfrJni.unaryOp(bottom, 64, 0, MpfrUnaryOp.SQR) }
    }

    @Test
    fun shimAcceptsExponentsInsideTheCanonicalBand() = runNative {
        // near the upper edge: exp = emax − 8 with mantissa 0x80 (z = 2^7) decodes to
        // 2^(emax−1) — the largest power of two inside the exponent range
        val top = craftedRegular(emaxDefault - 8)
        val decoded = MpfrJni.sumOp(arrayOf(top), 64, 0)
        assertEquals(MpfrKind.REGULAR, Repr.fromBytes(decoded).kind)
        // ordinary values keep round-tripping through every list-taking entry point
        val one = MpfrFloat.of(1L, p64)
        assertEquals(one.repr.bytes.toList(), MpfrJni.sumOp(arrayOf(one.repr.bytes), 64, 0).toList())
    }
}
