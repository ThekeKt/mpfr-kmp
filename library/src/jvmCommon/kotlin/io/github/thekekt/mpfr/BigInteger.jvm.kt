package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.internal.MpfrBridge
import java.math.BigInteger

// BigInteger ↔ MpfrFloat crossings: JVM/Android surface only, backed
// by GMP `mpz` through the shim (`mpfr_set_z` / `mpfr_get_z`). java.math.BigDecimal
// is never used (MPFR performs the exact conversion).

/** `MpfrFloat.of(BigInteger, …)` — `mpfr_set_z` over the BigInteger magnitude bytes. */
public fun MpfrFloat.Companion.of(
    value: BigInteger,
    precision: MpfrPrecision = MpfrFloat.defaultPrecision,
    rnd: RoundingMode = RoundingMode.NEAREST_EVEN,
): MpfrFloat = ofMpzBE(value.signum(), value.magnitudeBE(), precision, rnd)

/**
 * `mpfr_get_z` narrowing: rounds toward the integer set with [rnd] and returns the
 * [BigInteger]. NaN/Infinity throw [IllegalArgumentException] — no integer exists
 * for them (caller gates with the value predicates).
 */
public fun MpfrFloat.toBigInteger(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): BigInteger {
    require(isFinite) { "toBigInteger has no representation for NaN/Infinity" }
    val sign = IntArray(1)
    val magnitude = MpfrBridge.toMpz(repr, rnd.mpfrValueU(), sign)
    return BigInteger(sign[0], magnitude)
}

/** Big-endian unsigned magnitude without leading zeros (GMP `mpz` convention). */
private fun BigInteger.magnitudeBE(): ByteArray {
    // The sign byte of a two's-complement array is absent for single-byte
    // values (1..127 encode as [0x7f] etc., and negatives as [0x80]..[0xff]),
    // so extract the magnitude from the absolute value instead of skipping a
    // fixed leading byte.
    val raw = abs().toByteArray()
    if (raw.size == 1 && raw[0] == 0.toByte()) return ByteArray(0)
    var from = 0
    while (from < raw.size - 1 && raw[from] == 0.toByte()) from++
    return raw.copyOfRange(from, raw.size)
}
