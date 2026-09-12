package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.core.MpfrKind
import io.github.thekekt.mpfr.internal.Repr
import io.github.thekekt.mpfr.internal.readIntLE
import io.github.thekekt.mpfr.internal.readLongLE
import io.github.thekekt.mpfr.internal.writeLongLE
import io.github.thekekt.mpfr.internal.writeIntLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Codec-level guarantees for the canonical Repr byte format — the format is
 * defined in common Kotlin and mirrored by the shim; these tests pin both
 * directions without any native library.
 */
class ReprCodecTest {

    @Test
    fun headerLayoutIsLittleEndianAndOrderedPerSection6() {
        // mantissa byte 0x90 (MSB set), prec 53, exponent -1073, sign 1 => 32 bytes header+1
        val repr = Repr.regular(MpfrPrecision(53), signbit = true, exponent = -1073L, mantissaBE = byteArrayOf(0x90.toByte(), 0x00))
        val b = repr.bytes
        assertEquals(18, b.size)
        assertEquals(3, b[0].toInt()) // REGULAR = 3
        assertEquals(1, b[1].toInt()) // sign
        assertEquals(53, b.readIntLE(2))
        assertEquals(-1073L, b.readLongLE(6))
        assertEquals(2, b.readIntLE(14) and 0xFFFF) // mantLen i16
    }

    @Test
    fun accessorsRoundTripTheHeader() {
        val m = byteArrayOf(0xFF.toByte(), 0x01)
        val repr = Repr.regular(MpfrPrecision(64), signbit = false, exponent = 7L, mantissaBE = m)
        assertEquals(MpfrKind.REGULAR, repr.kind)
        assertEquals(MpfrPrecision(64), repr.precision)
        assertEquals(7L, repr.exponent)
        assertEquals(2, repr.mantissaLength)
        assertFalse(repr.signbit)
        assertEquals(16, repr.mantissaBits) // both leading bits set in 0xFF01? 0xFF MSB→ 8 + 8
    }

    @Test
    fun canonicalFormRejectsNonMsbSetMantissa() {
        assertFailsWith<IllegalArgumentException> {
            Repr.regular(MpfrPrecision(10), signbit = false, exponent = 0L, mantissaBE = byteArrayOf(0x01))
        }
    }

    @Test
    fun specialsCarryZeroExponentAndMantissa() {
        for (kind in listOf(MpfrKind.ZERO, MpfrKind.NAN, MpfrKind.INF)) {
            val r = Repr.singular(kind, signbit = kind != MpfrKind.NAN, precision = MpfrPrecision(8))
            assertEquals(kind, r.kind)
            assertEquals(0L, r.exponent)
            assertEquals(0, r.mantissaLength)
            assertEquals(16, r.bytes.size)
            if (kind == MpfrKind.NAN) assertFalse(r.signbit) // require() kept it signless
        }
    }

    @Test
    fun nanWithSignByteIsRejectedOnDecode() {
        val bytes = ByteArray(16)
        bytes[0] = MpfrKind.NAN.ordinal.toByte()
        bytes[1] = 1 // forbidden: NaN must not carry a sign
        assertFailsWith<IllegalArgumentException> { Repr.fromBytes(bytes) }
    }

    @Test
    fun lengthMismatchRejectedOnDecode() {
        val bytes = ByteArray(20)
        bytes[0] = MpfrKind.REGULAR.ordinal.toByte()
        bytes[14] = 8 // mantLen says 8 but array carries only 4
        assertFailsWith<IllegalArgumentException> { Repr.fromBytes(bytes) }
    }

    @Test
    fun precisionOutOfRangeRejectedByEncodeAndDecode() {
        val bytes = ByteArray(16)
        bytes[0] = MpfrKind.REGULAR.ordinal.toByte()
        bytes.writeIntLE(2, 0) // illegal precision
        assertFailsWith<IllegalArgumentException> { Repr.fromBytes(bytes) }
    }

    @Test
    fun signFlipsPreserveEverythingButSignAndSkipNaN() {
        val r = Repr.regular(MpfrPrecision(53), signbit = false, exponent = 3L, mantissaBE = byteArrayOf(0x81.toByte()))
        val f = r.flippedSign()
        assertTrue(f.signbit)
        assertEquals(3L, f.exponent)
        assertEquals(MpfrPrecision(53), f.precision)
        val nan = Repr.singular(MpfrKind.NAN, signbit = false, precision = MpfrPrecision(53))
        assertEquals(nan, nan.flippedSign())
        // double flip is identity
        assertEquals(r.bytes.toList(), r.withSignBit(!r.signbit).withSignBit(r.signbit).bytes.toList())
    }

    @Test
    fun trailingZerosCountedFromLsbOfMantissa() {
        // mantissa 0b1000_0000_0000_0000 exported MSB-first: [0x80, 0x00] → tz = 15
        val r = Repr.regular(MpfrPrecision(16), signbit = false, exponent = 0L, mantissaBE = byteArrayOf(0x80.toByte(), 0x00))
        assertEquals(15, r.trailingZeros)
    }
}
