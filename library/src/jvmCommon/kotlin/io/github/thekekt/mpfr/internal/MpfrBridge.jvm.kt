package io.github.thekekt.mpfr.internal

import io.github.thekekt.mpfr.InternalMpfrApi

/**
 * JVM/Android actual of the FFI seam: every call is
 * encode→native compute→decode over the canonical [Repr] bytes; nothing MPFR-side
 * outlives one bridge call. Flag isolation (save→run→delta→restore) lives
 * in the shim side for a single locking point.
 */
@InternalMpfrApi
internal actual object MpfrBridge {

    init {
        MpfrNativeLoader.ensureLoaded()
    }

    actual fun binaryOp(a: Repr, b: Repr, prec: Int, rnd: UByte, op: Int): Repr =
        Repr.fromBytes(MpfrJni.binaryOp(a.bytes, b.bytes, prec, rnd.toInt(), op))

    actual fun binary3Op(a: Repr, b: Repr, c: Repr, prec: Int, rnd: UByte, op: Int): Repr =
        Repr.fromBytes(MpfrJni.binary3Op(a.bytes, b.bytes, c.bytes, prec, rnd.toInt(), op))

    actual fun unaryOp(a: Repr, prec: Int, rnd: UByte, op: Int): Repr =
        Repr.fromBytes(MpfrJni.unaryOp(a.bytes, prec, rnd.toInt(), op))

    actual fun scalarOp(a: Repr, k: Long, prec: Int, rnd: UByte, op: Int): Repr =
        Repr.fromBytes(MpfrJni.scalarOp(a.bytes, k, prec, rnd.toInt(), op))

    actual fun doubleOp(a: Repr, d: Double, prec: Int, rnd: UByte, op: Int): Repr =
        Repr.fromBytes(MpfrJni.doubleOp(a.bytes, d, prec, rnd.toInt(), op))

    actual fun pairOp(a: Repr, prec: Int, rnd: UByte, op: Int): Array<Repr> =
        MpfrJni.pairOp(a.bytes, prec, rnd.toInt(), op).map(Repr::fromBytes).toTypedArray()

    actual fun constOp(prec: Int, rnd: UByte, op: Int): Repr =
        Repr.fromBytes(MpfrJni.constOp(prec, rnd.toInt(), op))

    actual fun factorial(n: Long, prec: Int, rnd: UByte): Repr =
        Repr.fromBytes(MpfrJni.factorialOp(n, prec, rnd.toInt()))

    actual fun sum(items: Array<Repr>, prec: Int, rnd: UByte): Repr =
        Repr.fromBytes(MpfrJni.sumOp(items.mapBytes(), prec, rnd.toInt()))

    actual fun dot(a: Array<Repr>, b: Array<Repr>, prec: Int, rnd: UByte): Repr =
        Repr.fromBytes(MpfrJni.dotOp(a.mapBytes(), b.mapBytes(), prec, rnd.toInt()))

    actual fun compareInt(a: Repr, b: Repr): Int = MpfrJni.compareIntOp(a.bytes, b.bytes)

    actual fun cmpPredicate(a: Repr, b: Repr, op: Int): Boolean =
        MpfrJni.cmpPredicateOp(a.bytes, b.bytes, op)

    actual fun eqUlps(a: Repr, b: Repr, maxUlps: Int): Boolean = MpfrJni.eqUlpsOp(a.bytes, b.bytes, maxUlps)

    actual fun ofDouble(d: Double, prec: Int, rnd: UByte): Repr =
        Repr.fromBytes(MpfrJni.ofDoubleOp(d, prec, rnd.toInt()))

    actual fun ofFloat(f: Float, prec: Int): Repr = Repr.fromBytes(MpfrJni.ofFloatOp(f, prec))

    actual fun ofSintmax(k: Long, prec: Int, rnd: UByte): Repr =
        Repr.fromBytes(MpfrJni.ofSintmaxOp(k, prec, rnd.toInt()))

    actual fun ofUintmax(k: Long, prec: Int, rnd: UByte): Repr =
        Repr.fromBytes(MpfrJni.ofUintmaxOp(k, prec, rnd.toInt()))

    actual fun fromMpz(signum: Int, magnitudeBE: ByteArray, prec: Int, rnd: UByte): Repr =
        Repr.fromBytes(MpfrJni.fromMpzOp(signum, magnitudeBE, prec, rnd.toInt()))

    actual fun toMpz(a: Repr, rnd: UByte, signOut: IntArray): ByteArray = MpfrJni.toMpzOp(a.bytes, rnd.toInt(), signOut)

    actual fun toDouble(a: Repr, rnd: UByte): Double = MpfrJni.toDoubleOp(a.bytes, rnd.toInt())

    actual fun toFloat(a: Repr, rnd: UByte): Float = MpfrJni.toFloatOp(a.bytes, rnd.toInt())

    actual fun toSintmax(a: Repr, rnd: UByte): Long = MpfrJni.toSintmaxOp(a.bytes, rnd.toInt())

    actual fun fitsSintmax(a: Repr, rnd: UByte): Boolean = MpfrJni.fitsSintmaxOp(a.bytes, rnd.toInt())

    actual fun getStr(a: Repr, base: Int, digits: Int, rnd: UByte, header: LongArray): ByteArray =
        MpfrJni.getStrOp(a.bytes, base, digits, rnd.toInt(), header)

    actual fun strtofr(s: String, prec: Int, base: Int, rnd: UByte, status: IntArray): Repr? =
        MpfrJni.strtofrOp(s, prec, base, rnd.toInt(), status)?.let(Repr::fromBytes)

    actual fun format(a: Repr, style: Int, digits: Int, rnd: UByte): String =
        MpfrJni.formatOp(a.bytes, style, digits, rnd.toInt())

    actual fun flagsGet(): UInt = MpfrJni.flagsGetOp().toUInt()
    actual fun flagsSave(): UInt = MpfrJni.flagsSaveOp().toUInt()
    actual fun flagsClear(mask: UInt): Unit = MpfrJni.flagsClearOp(mask.toInt())
    actual fun flagsSet(mask: UInt): Unit = MpfrJni.flagsSetOp(mask.toInt())
    actual fun flagsRestore(saved: UInt, alsoClear: UInt): Unit = MpfrJni.flagsRestoreOp(saved.toInt(), alsoClear.toInt())
    actual fun buildoptTls(): Boolean = MpfrJni.buildoptTlsOp()
}

private fun Array<Repr>.mapBytes(): Array<ByteArray> = Array(size) { this[it].bytes }
