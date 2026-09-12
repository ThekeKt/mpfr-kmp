package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.internal.MpfrDigits
import io.github.thekekt.mpfr.internal.checkCanonical
import io.github.thekekt.mpfr.internal.roundTripDecimalDigits
import io.github.thekekt.mpfr.internal.toDecimalString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pure decimal-assembly layer used by `toString(digits, rnd)` — no FFI. */
class DecimalAssemblyTest {

    private fun digits(name: String) = name.substringBefore('e').toByteArray()

    @Test fun rendersSignMantissaExponent() {
        // value = ·0.digits·10^exp  → shown as d.ddd·10^(exp-1)
        val pi = MpfrDigits(1, "31415926".toByteArray(), 1L)
        assertEquals("3.1415926e0", pi.toDecimalString())
        val neg = MpfrDigits(-1, "25".toByteArray(), 0L) // -0.25
        assertEquals("-2.5e-1", neg.toDecimalString())
    }

    @Test fun singleDigitPadsZero() {
        assertEquals("5.0e0", MpfrDigits(1, "5".toByteArray(), 1L).toDecimalString())
    }

    @Test fun zeroMantissaRendersShortForm() {
        assertEquals("0", MpfrDigits(0, ByteArray(0), 0L).toDecimalString())
        assertEquals("-0", MpfrDigits(-1, ByteArray(0), 0L).toDecimalString())
    }

    @Test fun canonicalProbeRejectsLeadingZero() {
        assertFailsWith<IllegalStateException> {
            MpfrDigits(1, "0123".toByteArray(), 1L).checkCanonical()
        }
        MpfrDigits(1, "1230".toByteArray(), 1L).checkCanonical() // trailing zeros are legal
    }

    @Test fun roundTripDigitCountCoversDoubleWidths() {
        // 53-bit doubles need ≤17 significant digits; formula must never undershoot.
        assertTrue(roundTripDecimalDigits(53) >= 17)
        assertTrue(roundTripDecimalDigits(1) >= 1)
        assertTrue(roundTripDecimalDigits(128) > 38)
    }
}
