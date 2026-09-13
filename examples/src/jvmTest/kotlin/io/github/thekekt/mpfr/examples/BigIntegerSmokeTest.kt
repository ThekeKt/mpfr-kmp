package io.github.thekekt.mpfr.examples

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.of
import io.github.thekekt.mpfr.toBigInteger
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BigInteger crossings through the published JVM/Android extension surface.
 */
class BigIntegerSmokeTest {

    private inline fun native(body: () -> Unit) {
        if (!io.github.thekekt.mpfr.examples.ExamplesRuntime.mpfrAvailable) {
            println("SKIPPED (MPFR native backend unavailable on this host)")
        } else {
            body()
        }
    }

    @Test fun magnitudeAndBigIntegerRoundTrip() = native {
        val big = MpfrFloat.parse("1234567890123456789", MpfrPrecision(64), base = 10)
        val value = (big as io.github.thekekt.mpfr.core.Result.Success).value
        assertEquals(BigInteger("1234567890123456789"), value.toBigInteger())
        assertTrue(MpfrFloat.of(BigInteger("2").pow(100), MpfrPrecision(128)).isInteger)
    }
}
