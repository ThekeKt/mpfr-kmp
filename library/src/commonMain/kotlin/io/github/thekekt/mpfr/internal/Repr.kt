package io.github.thekekt.mpfr.internal

import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.core.MpfrKind

/**
 * The canonical cross-platform value encoding:
 * fixed little-endian header + big-endian mantissa bytes.
 *
 * ```
 * offset 0  u8   kind      0=ZERO, 1=NAN, 2=INF, 3=REGULAR  (MpfrKind ordinal)
 * offset 1  u8   sign      0/1 — MPFR signbit (NaN is always 0)
 * offset 2  i32  prec      significand bits, little-endian
 * offset 6  i64  exp       x = ± mantissa * 2^exp, little-endian (0 for specials)
 * offset 14 i16 mantLen    mantissa byte count (0 for specials)
 * offset 16 …      bytes   mpz_export(order=1,size=1,endian=0): MSB-first,
 *                          canonical form: Regular mantissa has its MSB set
 * ```
 *
 * The object is immutable; `equals`/`hashCode` are byte-structural, which gives
 * `MpfrFloat` a precision-aware, NaN-sentinel-free identity without any FFI call.
 */
internal class Repr private constructor(internal val bytes: ByteArray) {

    val kind: MpfrKind get() = MpfrKind.entries[bytes[0].toInt()]
    val signbit: Boolean get() = bytes[1].toInt() != 0
    val precision: MpfrPrecision get() = MpfrPrecision(bytes.readIntLE(2))
    val exponent: Long get() = bytes.readLongLE(6)
    val mantissaLength: Int get() = bytes[14].toInt() and 0xFF or ((bytes[15].toInt() and 0xFF) shl 8)

    val isRegular: Boolean get() = kind == MpfrKind.REGULAR

    /** Number of significant mantissa bits (MSB position): the canonical form guarantees the MSB is set. */
    val mantissaBits: Int
        get() {
            val len = mantissaLength
            if (len == 0) return 0
            val top = bytes[HEADER].toInt() and 0xFF
            return (len - 1) * 8 + (8 - countLeadingZeroBitsOfByte(top))
        }

    /** Trailing zero bits of the mantissa; 0 for specials. Canonical form: non-zero MSB. */
    val trailingZeros: Int
        get() {
            var count = 0
            while (count < mantissaLength * 8 &&
                bytes[HEADER + mantissaLength - 1 - (count / 8)].toInt()
                    .let { (it shr (count % 8)) and 1 } == 0
            ) {
                count++
            }
            return count
        }

    override fun equals(other: Any?): Boolean = other is Repr && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    /** Sign replacement preserving kind; NaN stays signless. */
    fun withSignBit(newSign: Boolean): Repr =
        if (kind == MpfrKind.NAN) this
        else fromBytes(bytes.copyOf().apply { this[1] = (if (newSign) 1 else 0).toByte() })

    /** ± flip ([io.github.thekekt.mpfr.MpfrFloat.unaryMinus]'s pure path). */
    fun flippedSign(): Repr = if (kind == MpfrKind.NAN) this else withSignBit(!signbit)

    override fun toString(): String = when (kind) {
        MpfrKind.ZERO -> if (signbit) "-0" else "+0"
        MpfrKind.NAN -> "NaN"
        MpfrKind.INF -> if (signbit) "-Inf" else "+Inf"
        MpfrKind.REGULAR -> "${if (signbit) "-" else "+"}$mantissaBits bits·2^$exponent (${precision.bits})"
    }

    companion object {
        private const val HEADER = 16

        fun regular(precision: MpfrPrecision, signbit: Boolean, exponent: Long, mantissaBE: ByteArray): Repr {
            require(mantissaBE.isNotEmpty() && (mantissaBE[0].toInt() and 0x80) != 0) {
                "canonical form violated: MSB of the leading mantissa byte must be 1"
            }
            require(mantissaBE.size <= Short.MAX_VALUE) { "mantissa too long" }
            val bytes = ByteArray(HEADER + mantissaBE.size)
            bytes[0] = MpfrKind.REGULAR.ordinal.toByte()
            bytes[1] = (if (signbit) 1 else 0).toByte()
            bytes.writeIntLE(2, precision.bits)
            bytes.writeLongLE(6, exponent)
            bytes.writeShortLE(14, mantissaBE.size)
            mantissaBE.copyInto(bytes, HEADER)
            return Repr(bytes)
        }

        fun singular(kind: MpfrKind, signbit: Boolean, precision: MpfrPrecision): Repr {
            require(kind != MpfrKind.REGULAR) { "use regular()" }
            require(kind != MpfrKind.NAN || !signbit) { "NaN has no sign" }
            val bytes = ByteArray(HEADER)
            bytes[0] = kind.ordinal.toByte()
            bytes[1] = (if (kind == MpfrKind.NAN) 0 else if (signbit) 1 else 0).toByte()
            bytes.writeIntLE(2, precision.bits)
            return Repr(bytes)
        }

        fun fromBytes(bytes: ByteArray): Repr {
            require(bytes.size >= HEADER) { "truncated repr (${bytes.size} bytes)" }
            require(MpfrKind.entries.getOrNull(bytes[0].toInt()) != null) { "bad kind byte" }
            MpfrPrecision(bytes.readIntLE(2)) // header precision must be within the v1 domain
            require(bytes.size == HEADER + (bytes[14].toInt() and 0xFF or ((bytes[15].toInt() and 0xFF) shl 8))) {
                "mantissa length mismatch"
            }
            // canonical invariants (also produced by the shim; enforced both sides)
            require(bytes[1] == 0.toByte() || bytes[0] != MpfrKind.NAN.ordinal.toByte()) {
                "NaN must not carry a sign"
            }
            if (MpfrKind.entries[bytes[0].toInt()] == MpfrKind.REGULAR) {
                require((bytes[HEADER].toInt() and 0x80) != 0) { "non-canonical mantissa MSB" }
            } else {
                require(bytes[6] == 0.toByte() && bytes[14] == 0.toByte() && bytes[15] == 0.toByte()) {
                    "exp and mantissa must be zero for non-regular kinds"
                }
            }
            return Repr(bytes)
        }
    }
}

// --- little-endian helpers kept package-internal (used by Repr and the codec tests) ---

internal fun ByteArray.readIntLE(offset: Int): Int =
    this[offset].toInt() and 0xFF or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.readLongLE(offset: Int): Long =
    (readIntLE(offset).toLong() and 0xFFFF_FFFFL) or
        (readIntLE(offset + 4).toLong() shl 32)

internal fun ByteArray.writeIntLE(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

internal fun ByteArray.writeLongLE(offset: Int, value: Long) {
    writeIntLE(offset, value.toInt())
    writeIntLE(offset + 4, (value ushr 32).toInt())
}

internal fun ByteArray.writeShortLE(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

private fun countLeadingZeroBitsOfByte(v: Int): Int {
    if (v and 0x80 != 0) return 0
    if (v and 0x40 != 0) return 1
    if (v and 0x20 != 0) return 2
    if (v and 0x10 != 0) return 3
    if (v and 0x08 != 0) return 4
    if (v and 0x04 != 0) return 5
    if (v and 0x02 != 0) return 6
    return 7
}
