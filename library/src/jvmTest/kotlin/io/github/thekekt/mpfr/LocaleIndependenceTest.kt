package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.core.NumberStyle
import io.github.thekekt.mpfr.core.Result
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parse/format locale independence.
 *
 * The JVM's [Locale] does not modify the process C locale, and the native
 * bridge additionally pins a per-thread C numeric locale around every
 * `mpfr_strtofr`/`mpfr_asprintf` region — so the decimal point stays `.`
 * no matter what the host application does with its Java or C locale state.
 * This test flips the Java default to a comma-decimal locale and pins the
 * invariant on the wrapper surface.
 */
class LocaleIndependenceTest {

    @Test
    fun parseAndFormatStayDotDecimalUnderCommaLocale() = runNative {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            // sanity: the Java locale really is comma-decimal now
            assertEquals(",", java.text.DecimalFormatSymbols.getInstance().decimalSeparator.toString())

            val parsed = MpfrFloat.parse("1.5", MpfrPrecision(64), base = 10)
            assertTrue(parsed is Result.Success, "dot-decimal parse must succeed, got $parsed")
            assertEquals(1.5, (parsed as Result.Success).value.toDouble())

            // a comma-decimal input is NOT accepted (strict parse, no locale leakage)
            assertTrue(MpfrFloat.parse("1,5", MpfrPrecision(64), base = 10) is Result.Failure)

            val v = MpfrFloat.of(1.5, MpfrPrecision(64))
            assertEquals("1.5e0", v.toString(digits = 2))
            assertTrue(v.toString().startsWith("1.5"), "got ${v.toString()}")
            assertTrue(v.format(NumberStyle.FIXED, digits = 1).contains("."),
                "fixed format must keep '.', got ${v.format(NumberStyle.FIXED, digits = 1)}")
            assertTrue(v.format(NumberStyle.SCIENTIFIC, digits = 1).contains("."))
            assertTrue(v.format(NumberStyle.GENERAL).contains("."))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
