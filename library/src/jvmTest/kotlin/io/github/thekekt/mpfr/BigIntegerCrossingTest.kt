package io.github.thekekt.mpfr

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the BigInteger crossing: two's-complement byte
 * arrays use no leading sign byte for single-byte values, so the magnitude
 * extraction has to come from the absolute value.
 */
class BigIntegerCrossingTest {

    @Test fun singleByteMagnitudesCrossExactly() = runNative {
        for (v in listOf(0L, 1L, 2L, 42L, 127L, 128L, 255L, 256L, -1L, -42L, -127L, -128L, -129L)) {
            val bi = BigInteger.valueOf(v)
            assertEquals(
                v.toDouble(), MpfrFloat.of(bi, MpfrPrecision(64)).toDouble(),
                "of(BigInteger($v)) must cross exactly at precision 64",
            )
        }
    }

    @Test fun magnitudesRoundTrip() = runNative {
        val values = listOf(
            BigInteger.ZERO,
            BigInteger.ONE,
            BigInteger.valueOf(-1L),
            BigInteger.TEN,
            BigInteger.TWO.pow(100).add(BigInteger.ONE),
            BigInteger.TWO.pow(64).negate(),
        )
        for (bi in values) {
            assertEquals(
                bi, MpfrFloat.of(bi, MpfrPrecision(256)).toBigInteger(),
                "round trip of $bi",
            )
        }
    }
}