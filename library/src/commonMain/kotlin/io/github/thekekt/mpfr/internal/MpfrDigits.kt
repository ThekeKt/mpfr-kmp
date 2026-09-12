package io.github.thekekt.mpfr.internal

import io.github.thekekt.mpfr.InternalMpfrApi
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Raw decimal result of `mpfr_get_str`. The mantissa
 * carries ASCII digit bytes (`'0'..'9'`), most significant first; `exponent`
 * follows MPFR's convention: value = sign · 0.mantissa · base^exponent,
 * i.e. the first digit sits at position exponent−1.
 */
@InternalMpfrApi
internal class MpfrDigits(
    internal val sign: Int,
    internal val mantissa: ByteArray,
    internal val exponent: Long,
)

/**
 * Decimal digits that guarantee a round-trip of a [bits]-wide significand
 * (full-precision `toString()` correctness). Formula:
 * `ceil(bits · log10(2)) + 1` (one guard digit beyond the exact bound).
 */
internal fun roundTripDecimalDigits(bits: Int): Int =
    ceil(bits.toDouble() * LN10_OF_2) .toInt() + 1

private const val LN10_OF_2: Double = 0.301_029_995_663_981_2 // log10(2)

/**
 * Renders [MpfrDigits] as an exactly parseable by `strtofr` decimal string
 * (`-?d(.ddd)?e[+-]?x`, at least one fractional digit, lowercase `e` like
 * `Double.toString`). No FFI — pure, golden-testable.
 */
internal fun MpfrDigits.toDecimalString(): String {
    if (mantissa.isEmpty()) return if (sign < 0) "-0" else "0"
    val sb = StringBuilder()
    if (sign < 0) sb.append('-')
    sb.append(mantissa[0].toInt().toChar())
    sb.append('.')
    if (mantissa.size > 1) {
        for (i in 1 until mantissa.size) sb.append(mantissa[i].toInt().toChar())
    } else {
        sb.append('0')
    }
    sb.append('e').append(exponent - 1)
    return sb.toString()
}

/** Leading-zero-free digit count sanity (shim guarantees MSB != '0' for non-zero). */
internal fun MpfrDigits.checkCanonical() {
    if (mantissa.isNotEmpty()) {
        check(mantissa[0] != '0'.code.toByte()) { "get_str produced a non-canonical mantissa" }
    }
}
