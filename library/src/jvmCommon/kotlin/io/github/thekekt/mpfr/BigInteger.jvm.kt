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
    val raw = toByteArray() // sign byte first, then magnitude, two's complement
    var from = 1
    while (from < raw.size && raw[from] == 0.toByte()) from++
    return raw.copyOfRange(from, raw.size)
}
