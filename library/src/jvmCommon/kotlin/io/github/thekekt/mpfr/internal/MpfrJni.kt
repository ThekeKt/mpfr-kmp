package io.github.thekekt.mpfr.internal

import io.github.thekekt.mpfr.InternalMpfrApi

/**
 * The `native` methods carrying the shim — an internal receiver object whose
 * public members compile to stable, un-mangled symbols
 * `Java_io_github_thekekt_mpfr_internal_MpfrJni_<name>` (the class itself is an
 * implementation detail; excluded from the API baseline via the marker wired in
 * the root `apiValidation` setup).
 *
 * Repr crossing convention: canonical byte arrays; `rnd` is the `mpfr_rnd_t`
 * int encoding; integers cross intmax-based.
 */
@InternalMpfrApi
internal object MpfrJni {

    external fun binaryOp(a: ByteArray, b: ByteArray, prec: Int, rnd: Int, op: Int): ByteArray
    external fun binary3Op(a: ByteArray, b: ByteArray, c: ByteArray, prec: Int, rnd: Int, op: Int): ByteArray
    external fun unaryOp(a: ByteArray, prec: Int, rnd: Int, op: Int): ByteArray
    external fun scalarOp(a: ByteArray, k: Long, prec: Int, rnd: Int, op: Int): ByteArray
    external fun doubleOp(a: ByteArray, d: Double, prec: Int, rnd: Int, op: Int): ByteArray
    external fun pairOp(a: ByteArray, prec: Int, rnd: Int, op: Int): Array<ByteArray>
    external fun constOp(prec: Int, rnd: Int, op: Int): ByteArray
    external fun factorialOp(n: Long, prec: Int, rnd: Int): ByteArray
    external fun sumOp(items: Array<ByteArray>, prec: Int, rnd: Int): ByteArray
    external fun dotOp(a: Array<ByteArray>, b: Array<ByteArray>, prec: Int, rnd: Int): ByteArray
    external fun compareIntOp(a: ByteArray, b: ByteArray): Int
    external fun cmpPredicateOp(a: ByteArray, b: ByteArray, op: Int): Boolean
    external fun eqUlpsOp(a: ByteArray, b: ByteArray, maxUlps: Int): Boolean
    external fun ofDoubleOp(d: Double, prec: Int, rnd: Int): ByteArray
    external fun ofFloatOp(f: Float, prec: Int): ByteArray
    external fun ofSintmaxOp(k: Long, prec: Int, rnd: Int): ByteArray
    external fun ofUintmaxOp(k: Long, prec: Int, rnd: Int): ByteArray
    external fun fromMpzOp(signum: Int, magnitudeBE: ByteArray, prec: Int, rnd: Int): ByteArray
    external fun toMpzOp(a: ByteArray, rnd: Int, signOut: IntArray): ByteArray
    external fun toDoubleOp(a: ByteArray, rnd: Int): Double
    external fun toFloatOp(a: ByteArray, rnd: Int): Float
    external fun toSintmaxOp(a: ByteArray, rnd: Int): Long
    external fun fitsSintmaxOp(a: ByteArray, rnd: Int): Boolean
    external fun getStrOp(a: ByteArray, base: Int, digits: Int, rnd: Int, header: LongArray): ByteArray
    external fun strtofrOp(s: String, prec: Int, base: Int, rnd: Int, status: IntArray): ByteArray?
    external fun formatOp(a: ByteArray, style: Int, digits: Int, rnd: Int): String
    external fun flagsGetOp(): Int
    external fun flagsSaveOp(): Int
    external fun flagsClearOp(mask: Int)
    external fun flagsSetOp(mask: Int)
    external fun flagsRestoreOp(saved: Int, alsoClear: Int)
    external fun buildoptTlsOp(): Boolean
}
