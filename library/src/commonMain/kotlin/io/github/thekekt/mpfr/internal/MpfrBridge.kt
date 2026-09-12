package io.github.thekekt.mpfr.internal

import io.github.thekekt.mpfr.InternalMpfrApi

/**
 * The single FFI seam: one Kotlin function per result-producing
 * *family*, never a 1:1 mirror of `mpfr_*`. Operands and results cross as canonical
 * [Repr] byte encodings; rounding crosses as `mpfr_rnd_t` (`RoundingMode.mpfrValue`);
 * integers cross intmax-based only. Validation lives above the bridge in
 * common code — the bridge must never validate Kotlin invariants.
 *
 * `actual` backends: `jvmCommon` (JNI shim, v1) and `nativeMain` (cinterop, v1.x,
 * mirroring the JVM algorithm verbatim).
 */
@InternalMpfrApi
internal expect object MpfrBridge {

    // ---- value-producing ops (one function per family) ----------------------------

    fun binaryOp(a: Repr, b: Repr, prec: Int, rnd: UByte, op: Int): Repr
    fun binary3Op(a: Repr, b: Repr, c: Repr, prec: Int, rnd: UByte, op: Int): Repr // MpfrFusedOp
    fun unaryOp(a: Repr, prec: Int, rnd: UByte, op: Int): Repr
    fun scalarOp(a: Repr, k: Long, prec: Int, rnd: UByte, op: Int): Repr
    fun doubleOp(a: Repr, d: Double, prec: Int, rnd: UByte, op: Int): Repr
    fun pairOp(a: Repr, prec: Int, rnd: UByte, op: Int): Array<Repr> // length 2: [first, second]
    fun constOp(prec: Int, rnd: UByte, op: Int): Repr               // PI / E
    fun factorial(n: Long, prec: Int, rnd: UByte): Repr             // mpfr_fac_ui
    fun sum(items: Array<Repr>, prec: Int, rnd: UByte): Repr        // mpfr_sum
    fun dot(a: Array<Repr>, b: Array<Repr>, prec: Int, rnd: UByte): Repr // mpfr_dot (equal lengths)

    // ---- comparisons ------------------------------------------------------------

    /** `mpfr_cmp` for the magnitudes (signed-zero and NaN ties resolve above the bridge, in common code). */
    fun compareInt(a: Repr, b: Repr): Int

    /** `mpfr_*_p` predicates over [MpfrPredicateOp] ids. */
    fun cmpPredicate(a: Repr, b: Repr, op: Int): Boolean

    /** `mpfr_eq`: equal within [maxUlps] representable steps. */
    fun eqUlps(a: Repr, b: Repr, maxUlps: Int): Boolean

    // ---- scalar conversions -------------------------------------------------------

    fun ofDouble(d: Double, prec: Int, rnd: UByte): Repr
    fun ofFloat(f: Float, prec: Int): Repr
    fun ofSintmax(k: Long, prec: Int, rnd: UByte): Repr
    fun ofUintmax(k: Long, prec: Int, rnd: UByte): Repr // ULong bit pattern; shim casts to uintmax_t

    /** GMP mpz crossing (JVM/Android BigInteger surface): MSB-first magnitude bytes. */
    fun fromMpz(signum: Int, magnitudeBE: ByteArray, prec: Int, rnd: UByte): Repr
    /** `mpfr_get_z` into mpz: `signOut[0]` = signum, returns magnitude bytes (may be empty for 0). */
    fun toMpz(a: Repr, rnd: UByte, signOut: IntArray): ByteArray

    fun toDouble(a: Repr, rnd: UByte): Double
    fun toFloat(a: Repr, rnd: UByte): Float
    fun toSintmax(a: Repr, rnd: UByte): Long  // out-of-range → MPFR ERANGE + undefined value; call via fits-gated path only
    fun fitsSintmax(a: Repr, rnd: UByte): Boolean

    // ---- strings -------------------------------------------------------------------

    /**
     * `mpfr_get_str` in [base], [digits] significant digits (0 → MPFR-chosen shortest).
     * Returns the ASCII digit bytes; `header[0]` = sign (`-1|0|1`), `header[1]` =
     * decimal exponent (mantissa·base^exp convention). Buffer freed inside.
     */
    fun getStr(a: Repr, base: Int, digits: Int, rnd: UByte, header: LongArray): ByteArray

    /**
     * `mpfr_strtofr`: on success returns the value and status 0 via [status]; on
     * MPFR failure status ∈ {-1, -2, -3} (mapped to MpfrParseError in common code)
     * and the return value is null.
     */
    fun strtofr(s: String, prec: Int, base: Int, rnd: UByte, status: IntArray): Repr?

    /** `mpfr_snprintf` over the `%R[efg]` family; specials use the MPFR labels. */
    fun format(a: Repr, style: Int, digits: Int, rnd: UByte): String

    // ---- global exception flags ----------------------------------------------------

    fun flagsGet(): UInt
    fun flagsSave(): UInt
    fun flagsClear(mask: UInt)
    fun flagsSet(mask: UInt)
    fun flagsRestore(saved: UInt, alsoClear: UInt)

    /** `mpfr_buildopt_tls_p()` — asserted true by the native tests (thread-safe MPFR build). */
    fun buildoptTls(): Boolean
}
