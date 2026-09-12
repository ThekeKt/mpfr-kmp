package io.github.thekekt.mpfr

import io.github.thekekt.mpfr.core.IntegerAndFraction
import io.github.thekekt.mpfr.core.MpfrKind
import io.github.thekekt.mpfr.core.MpfrParseError
import io.github.thekekt.mpfr.core.NumberStyle
import io.github.thekekt.mpfr.core.Result
import io.github.thekekt.mpfr.core.Sign
import io.github.thekekt.mpfr.core.SinCosResult
import io.github.thekekt.mpfr.internal.MpfrBinaryOp
import io.github.thekekt.mpfr.internal.MpfrBridge
import io.github.thekekt.mpfr.internal.MpfrConstOp
import io.github.thekekt.mpfr.internal.MpfrDigits
import io.github.thekekt.mpfr.internal.MpfrDoubleOp
import io.github.thekekt.mpfr.internal.MpfrFormatStyle
import io.github.thekekt.mpfr.internal.MpfrFusedOp
import io.github.thekekt.mpfr.internal.MpfrPairOp
import io.github.thekekt.mpfr.internal.MpfrPredicateOp
import io.github.thekekt.mpfr.internal.MpfrScalarOp
import io.github.thekekt.mpfr.internal.MpfrUnaryOp
import io.github.thekekt.mpfr.internal.Repr
import io.github.thekekt.mpfr.internal.checkCanonical
import io.github.thekekt.mpfr.internal.toDecimalString
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * An MPFR floating-point value: (kind, sign, precision, mantissa · 2^exponent)
 * held as the canonical [Repr] byte encoding — immutable, GC-clean, shareable
 * across threads. Every arithmetic op returns a new value and **never throws**
 * for arithmetic reasons; NaN / ±Inf / ±0 propagate exactly as MPFR computes
 * them.
 *
 * Identity model: [equals] is structural — NaN never
 * equals NaN (even via `equals`), `+0 ≠ −0`, and same mantissa at different
 * precisions is a different value. [compareTo] is a **total order**, not
 * MPFR's partial order — [totalOrder] exposes the verbatim MPFR predicate.
 *
 * MPFR names are preserved at the API line (`add/sub/mul/div`, `sqrt`, `agm`,
 * `fma`, `dim`, `reciprocalSqrt` for `mpfr_rec_sqrt` — the single sanctioned rename).
 */
@OptIn(ExperimentalAtomicApi::class)
public class MpfrFloat private constructor(@InternalMpfrApi internal val repr: Repr) :
    Comparable<MpfrFloat> {

    // ---- accessors (no FFI: the Repr bytes answer everything) -----------------------

    /** Value significand width — part of the identity. */
    public val precision: MpfrPrecision get() = repr.precision

    /** ZERO/NAN/INF/REGULAR, computed from the representation byte. */
    public val kind: MpfrKind get() = repr.kind

    public val isNaN: Boolean get() = kind == MpfrKind.NAN
    public val isInfinite: Boolean get() = kind == MpfrKind.INF
    public val isZero: Boolean get() = kind == MpfrKind.ZERO
    public val isFinite: Boolean get() = !isNaN && !isInfinite

    /** Sign bit; NaN reports `false` even for a NaN-repr (there is no negative NaN). */
    public val isNegative: Boolean get() = repr.signbit

    /** [Sign.POSITIVE] for NaN — NaN never exposes a sign. */
    public val sign: Sign get() = if (repr.signbit && !isNaN) Sign.NEGATIVE else Sign.POSITIVE

    /** Mathematically-integer test, pure over the representation (mpfr_integer_p equivalent). */
    public val isInteger: Boolean
        get() = when (kind) {
            MpfrKind.ZERO -> true
            MpfrKind.REGULAR -> exponentIsInteger(repr.exponent, repr.trailingZeros, repr.mantissaLength * 8)
            else -> false
        }

    // ---- identity (with deliberate deviations from Double/comparable semantics) -----

    /** Structural equality; NaN equals nothing, including itself. */
    override fun equals(other: Any?): Boolean =
        other is MpfrFloat && repr.kind != MpfrKind.NAN && repr.bytes.contentEquals(other.repr.bytes)

    /** Value-based; NaN uses the stable sentinel (defined once, in the companion). */
    override fun hashCode(): Int =
        if (repr.kind == MpfrKind.NAN) NaN_HASH_CODE else repr.bytes.contentHashCode()

    // ---- comparison surface ----------------------------------------------------------

    /**
     * Total order: numeric magnitude via `mpfr_cmp`; ties broken by
     * `mpfr_total_order_p` (−0 < +0), then by precision for numerically-equal
     * cross-precision pairs. NaN sorts above every non-NaN value; two NaNs order by
     * precision (stable, semantically meaningless).
     */
    override fun compareTo(other: MpfrFloat): Int {
        if (this === other) return 0
        if (isNaN) return if (other.isNaN) precision.bits.compareTo(other.precision.bits) else 1
        if (other.isNaN) return -1
        val cmp = MpfrBridge.compareInt(repr, other.repr)
        if (cmp != 0) return if (cmp > 0) 1 else -1
        val forward = MpfrBridge.cmpPredicate(repr, other.repr, MpfrPredicateOp.TOTAL_ORDER)
        val back = MpfrBridge.cmpPredicate(other.repr, repr, MpfrPredicateOp.TOTAL_ORDER)
        if (forward && !back) return -1
        if (back && !forward) return 1
        // numerically equal and total-order-equivalent (incl. +0 ≡ −0 handled above;
        // MPFR's total order does distinguish signed zeros empirically):
        // precision tie-breaker for numerically-equal cross-precision pairs
        return precision.bits.compareTo(other.precision.bits)
    }

    /** `this < other` (`mpfr_less_p`); false when either operand is NaN. */
    public infix fun lt(other: MpfrFloat): Boolean = predicate(other, MpfrPredicateOp.LESS)

    public infix fun lte(other: MpfrFloat): Boolean = predicate(other, MpfrPredicateOp.LESS_EQUAL)
    public infix fun gt(other: MpfrFloat): Boolean = predicate(other, MpfrPredicateOp.GREATER)
    public infix fun gte(other: MpfrFloat): Boolean = predicate(other, MpfrPredicateOp.GREATER_EQUAL)

    /** `mpfr_unordered_p`: true iff either operand is NaN. */
    public infix fun isUnorderedWith(other: MpfrFloat): Boolean = predicate(other, MpfrPredicateOp.UNORDERED)

    /** `mpfr_lessgreater_p`: ordered and unequal. */
    public infix fun isLessOrGreater(other: MpfrFloat): Boolean = predicate(other, MpfrPredicateOp.LESS_GREATER)

    /** `mpfr_total_order_p` verbatim (distinct from [compareTo]). */
    public infix fun totalOrder(other: MpfrFloat): Boolean = predicate(other, MpfrPredicateOp.TOTAL_ORDER)

    /**
     * `mpfr_eq` — equal within [maxUlps] representable steps. Equal precisions and
     * non-NaN operands are required (programmer error otherwise: MPFR leaves that
     * case unspecified).
     */
    public fun equalUlps(other: MpfrFloat, maxUlps: Int): Boolean {
        require(!(isNaN || other.isNaN)) { "equalUlps is undefined for NaN operands" }
        require(precision == other.precision) { "equalUlps requires equal precisions (mpfr_eq domain)" }
        return MpfrBridge.eqUlps(repr, other.repr, maxUlps)
    }

    /** `mpfr_reldiff(this, other)`: |other − this| / |this| — anchored to the manual line in the shim. */
    public fun relativeDifference(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.RELDIFF))

    // ---- arithmetic (result precision defaults to max of the operands) ---------------

    public fun add(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.ADD))

    public fun sub(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.SUB))

    public fun mul(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.MUL))

    public fun div(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.DIV))

    public operator fun plus(other: MpfrFloat): MpfrFloat = add(other)
    public operator fun minus(other: MpfrFloat): MpfrFloat = sub(other)
    public operator fun times(other: MpfrFloat): MpfrFloat = mul(other)
    public operator fun div(other: MpfrFloat): MpfrFloat = div(other, RoundingMode.NEAREST_EVEN, maxOf(precision, other.precision))

    // mixed scalar — result precision = this.precision (the scalar is exact);
    // integer crossings are intmax-based.

    public fun add(other: Long, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = scalar(other, MpfrScalarOp.ADD, rnd)
    public fun sub(other: Long, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = scalar(other, MpfrScalarOp.SUB, rnd)
    public fun mul(other: Long, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = scalar(other, MpfrScalarOp.MUL, rnd)
    public fun div(other: Long, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = scalar(other, MpfrScalarOp.DIV, rnd)

    public operator fun plus(other: Long): MpfrFloat = add(other)
    public operator fun minus(other: Long): MpfrFloat = sub(other)
    public operator fun times(other: Long): MpfrFloat = mul(other)
    public operator fun div(other: Long): MpfrFloat = div(other, RoundingMode.NEAREST_EVEN)

    public fun add(other: Double, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = double(other, MpfrDoubleOp.ADD, rnd)
    public fun sub(other: Double, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = double(other, MpfrDoubleOp.SUB, rnd)
    public fun mul(other: Double, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = double(other, MpfrDoubleOp.MUL, rnd)
    public fun div(other: Double, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = double(other, MpfrDoubleOp.DIV, rnd)

    public operator fun plus(other: Double): MpfrFloat = add(other)
    public operator fun minus(other: Double): MpfrFloat = sub(other)
    public operator fun times(other: Double): MpfrFloat = mul(other)
    public operator fun div(other: Double): MpfrFloat = div(other, RoundingMode.NEAREST_EVEN)

    // pure sign ops + unary family

    /** Sign flip as pure Repr transform; NaN untouched (there is no negative NaN). */
    public operator fun unaryMinus(): MpfrFloat = MpfrFloat(repr.flippedSign())

    /** `|x|`; `abs` of ±0 returns the **+0** variant (MPFR macro rule). */
    public fun abs(): MpfrFloat = when (kind) {
        MpfrKind.NAN -> this
        MpfrKind.REGULAR, MpfrKind.INF, MpfrKind.ZERO ->
            if (repr.signbit) MpfrFloat(repr.withSignBit(false)) else this
    }

    public fun sqr(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.SQR, rnd)
    public fun sqrt(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.SQRT, rnd)
    public fun cbrt(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.CBRT, rnd)

    /** n-th root (`mpfr_rootn_ui`), n ≥ 1; operand precision preserved. */
    public fun root(n: Int, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat {
        require(n >= 1) { "root degree must be >= 1, was $n" }
        return MpfrFloat(MpfrBridge.scalarOp(repr, n.toLong(), precision.bits, rnd.mpfrValueU(), MpfrScalarOp.ROOTN))
    }

    /** `mpfr_rec_sqrt`: 1/√x — the sanctioned rename. */
    public fun reciprocalSqrt(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.REC_SQRT, rnd)

    public fun exp(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.EXP, rnd)
    public fun exp2(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.EXP2, rnd)
    public fun exp10(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.EXP10, rnd)
    public fun expm1(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.EXPM1, rnd)
    public fun exp2m1(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.EXP2M1, rnd)
    public fun exp10m1(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.EXP10M1, rnd)
    public fun log(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.LOG, rnd)
    public fun log2(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.LOG2, rnd)
    public fun log10(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.LOG10, rnd)
    public fun log1p(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.LOG1P, rnd)
    public fun log2p1(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.LOG2P1, rnd)
    public fun log10p1(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.LOG10P1, rnd)
    public fun sin(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.SIN, rnd)
    public fun cos(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.COS, rnd)
    public fun tan(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.TAN, rnd)
    public fun asin(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.ASIN, rnd)
    public fun acos(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.ACOS, rnd)
    public fun atan(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.ATAN, rnd)
    public fun sinh(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.SINH, rnd)
    public fun cosh(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.COSH, rnd)
    public fun tanh(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.TANH, rnd)
    public fun asinh(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.ASINH, rnd)
    public fun acosh(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.ACOSH, rnd)
    public fun atanh(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.ATANH, rnd)
    public fun sinpi(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.SINPI, rnd)
    public fun cospi(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.COSPI, rnd)
    public fun tanpi(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.TANPI, rnd)
    public fun erf(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.ERF, rnd)
    public fun erfc(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.ERFC, rnd)
    public fun gamma(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.GAMMA, rnd)
    public fun lngamma(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.LNGAMMA, rnd)
    public fun digamma(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.DIGAMMA, rnd)
    public fun zeta(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.ZETA, rnd)

    /** Round toward zero to an integer value at this precision (`mpfr_trunc`). */
    public fun truncate(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.TRUNC, rnd)

    /** Floor/ceil/round are directionally exact — no rounding parameter (mpfr.h L474–L477). */
    public fun floor(): MpfrFloat = unary(MpfrUnaryOp.FLOOR, RoundingMode.NEAREST_EVEN)
    public fun ceil(): MpfrFloat = unary(MpfrUnaryOp.CEIL, RoundingMode.NEAREST_EVEN)

    /** `mpfr_round` (ties away from zero); for ties-to-even use [roundToEven]. */
    public fun round(): MpfrFloat = unary(MpfrUnaryOp.ROUND, RoundingMode.NEAREST_EVEN)

    public fun roundToEven(): MpfrFloat = unary(MpfrUnaryOp.ROUND_EVEN, RoundingMode.NEAREST_EVEN)
    public fun fractional(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat = unary(MpfrUnaryOp.FRAC, rnd)

    /** `mpfr_modf`: (integer part, fractional part), both at this precision. */
    public fun split(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): IntegerAndFraction {
        val parts = MpfrBridge.pairOp(repr, precision.bits, rnd.mpfrValueU(), MpfrPairOp.MODF)
        return IntegerAndFraction(MpfrFloat(parts[0]), MpfrFloat(parts[1]))
    }

    /** `mpfr_sin_cos`: (sin, cos) at one precision. */
    public fun sinCos(rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = precision): SinCosResult {
        val parts = MpfrBridge.pairOp(repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrPairOp.SIN_COS)
        return SinCosResult(MpfrFloat(parts[0]), MpfrFloat(parts[1]))
    }

    /** `mpfr_min` verbatim — NaN if either operand is NaN (MPFR macro semantics). */
    public fun min(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.MIN))

    /** `mpfr_max` verbatim — NaN if either operand is NaN (MPFR macro semantics). */
    public fun max(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.MAX))

    public fun dim(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.DIM))

    public fun hypot(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.HYPOT))

    public fun agm(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.AGM))

    /** `mpfr_atan2(y, x)`: called on the **y**-side (`y.atan2(x)`). */
    public fun atan2(x: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, x.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, x.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.ATAN2))

    public fun fmod(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.FMOD))

    public fun remainder(other: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, other.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, other.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.REMAINDER))

    /** `mpfr_pow`: binary exponent (powr/powi stay out of v1). */
    public fun pow(exponent: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = maxOf(precision, exponent.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binaryOp(repr, exponent.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrBinaryOp.POW))

    /** `mpfr_compound_si`: (1 + this)^n, n ≥ 0, this precision. */
    public fun compound(n: Long, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat {
        require(n >= 0) { "compound degree must be >= 0, was $n" }
        return MpfrFloat(MpfrBridge.scalarOp(repr, n, precision.bits, rnd.mpfrValueU(), MpfrScalarOp.COMPOUND))
    }

    /** `this * factor + addend` — single-rounded FMA (`mpfr_fma`). */
    public fun fma(factor: MpfrFloat, addend: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = widest(precision, factor.precision, addend.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binary3Op(repr, factor.repr, addend.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrFusedOp.FMA))

    /** `this * factor − addend` fused (`mpfr_fms`). */
    public fun fms(factor: MpfrFloat, subtrahend: MpfrFloat, rnd: RoundingMode = RoundingMode.NEAREST_EVEN, resultPrecision: MpfrPrecision = widest(precision, factor.precision, subtrahend.precision)): MpfrFloat =
        MpfrFloat(MpfrBridge.binary3Op(repr, factor.repr, subtrahend.repr, resultPrecision.bits, rnd.mpfrValueU(), MpfrFusedOp.FMS))

    /** Next representable toward +∞ / −∞ (`mpfr_nextabove` / `mpfr_nextbelow`). */
    public fun nextUp(): MpfrFloat = unary(MpfrUnaryOp.NEXTUP, RoundingMode.NEAREST_EVEN)
    public fun nextDown(): MpfrFloat = unary(MpfrUnaryOp.NEXTDOWN, RoundingMode.NEAREST_EVEN)

    /** × 2^n (`mpfr_mul_2exp`/`mpfr_div_2exp`); negative n routes to the division side. Named functions, not operators. */
    public fun shl(n: Int, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat =
        shiftTwo(n, multiply = true, rnd)

    /** ÷ 2^n — signed shift (named, not operator). */
    public fun shr(n: Int, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat =
        shiftTwo(n, multiply = false, rnd)

    // ---- conversions ------------------------------------------------------------------

    public fun toDouble(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): Double = MpfrBridge.toDouble(repr, rnd.mpfrValueU())
    public fun toFloat(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): Float = MpfrBridge.toFloat(repr, rnd.mpfrValueU())

    public fun toLong(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): Long {
        if (!fitsLong(rnd)) throw MpfrRangeException("MpfrFloat does not fit Long")
        return MpfrBridge.toSintmax(repr, rnd.mpfrValueU())
    }

    public fun toInt(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): Int {
        if (!fitsInt(rnd)) throw MpfrRangeException("MpfrFloat does not fit Int")
        return MpfrBridge.toSintmax(repr, rnd.mpfrValueU()).toInt()
    }

    public fun fitsLong(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): Boolean = MpfrBridge.fitsSintmax(repr, rnd.mpfrValueU())

    public fun fitsInt(rnd: RoundingMode = RoundingMode.NEAREST_EVEN): Boolean =
        MpfrBridge.fitsSintmax(repr, rnd.mpfrValueU()) && MpfrBridge.toSintmax(repr, rnd.mpfrValueU()) in Int.MIN_VALUE..Int.MAX_VALUE

    // ---- strings (specials match Double.toString labels) --------------------------------

    /** Full round-trip decimal — MPFR-chosen width (mpfr.h L537: `n=0` ⇒ exact round-trip). */
    override fun toString(): String = toString(digits = 0, rnd = RoundingMode.NEAREST_EVEN)

    /** Decimal at [digits] significant digits; [digits] 0 → MPFR-chosen shortest. */
    public fun toString(digits: Int, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): String {
        when (kind) {
            MpfrKind.NAN -> return "NaN"
            MpfrKind.INF -> return if (repr.signbit) "-Infinity" else "Infinity"
            MpfrKind.ZERO -> return if (repr.signbit) "-0" else "0"
            MpfrKind.REGULAR -> Unit
        }
        val header = LongArray(2)
        val mantissa = MpfrBridge.getStr(repr, BASE_TEN, digits, rnd.mpfrValueU(), header)
        val parsed = MpfrDigits(header[0].toInt(), mantissa, header[1])
        parsed.checkCanonical()
        return parsed.toDecimalString()
    }

    /** `mpfr_snprintf` `%R` family (style selects e/f/g; digits=0 → MPFR default). */
    public fun format(style: NumberStyle = NumberStyle.GENERAL, digits: Int = 0, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): String =
        MpfrBridge.format(repr, style.mpfrFormatOp(), digits, rnd.mpfrValueU())

    private fun scalar(k: Long, op: Int, rnd: RoundingMode): MpfrFloat =
        MpfrFloat(MpfrBridge.scalarOp(repr, k, precision.bits, rnd.mpfrValueU(), op))

    private fun double(d: Double, op: Int, rnd: RoundingMode): MpfrFloat =
        MpfrFloat(MpfrBridge.doubleOp(repr, d, precision.bits, rnd.mpfrValueU(), op))

    private fun shiftTwo(n: Int, multiply: Boolean, rnd: RoundingMode): MpfrFloat {
        val magnitude: Long = if (n >= 0) n.toLong() else n.toLong().unaryMinusChecked()
        val goesMultiplication = if (n >= 0) multiply else !multiply
        val op = if (goesMultiplication) MpfrScalarOp.MUL_2EXP else MpfrScalarOp.DIV_2EXP
        return MpfrFloat(MpfrBridge.scalarOp(repr, magnitude, precision.bits, rnd.mpfrValueU(), op))
    }

    private fun predicate(other: MpfrFloat, op: Int): Boolean = MpfrBridge.cmpPredicate(repr, other.repr, op)

    private fun unary(op: Int, rnd: RoundingMode): MpfrFloat =
        MpfrFloat(MpfrBridge.unaryOp(repr, precision.bits, rnd.mpfrValueU(), op))

    public companion object {
        /** NaN structural hashCode sentinel — the single definition. */
        internal const val NaN_HASH_CODE: Int = 0x7FCBAD

        internal const val BASE_TEN: Int = 10

        private val defaultPrecBits = AtomicInt(MpfrPrecision.DOUBLE_BITS)

        /**
         * Kotlin-land process default precision: starts at the
         * 53-bit double width; the MPFR C global default is never read or written.
         * Reads/writes are atomic; [withDefaultPrecision] scopes are per-slice and
         * rely on the documented single-thread discipline.
         */
        public var defaultPrecision: MpfrPrecision
            get() = MpfrPrecision(defaultPrecBits.load())
            set(value) { defaultPrecBits.store(value.bits) }

        /** Runs [action] at [precision]; restores the previous default in `finally`. */
        public inline fun <R> withDefaultPrecision(precision: MpfrPrecision = defaultPrecision, action: () -> R): R {
            val saved = defaultPrecision
            defaultPrecision = precision
            try {
                return action()
            } finally {
                defaultPrecision = saved
            }
        }

        /** `mpfr_set_flt` — exact at any precision ≥ 24. */
        public fun of(value: Float, precision: MpfrPrecision = defaultPrecision): MpfrFloat =
            MpfrFloat(MpfrBridge.ofFloat(value, precision.bits))

        public fun of(value: Double, precision: MpfrPrecision = defaultPrecision, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat =
            MpfrFloat(MpfrBridge.ofDouble(value, precision.bits, rnd.mpfrValueU()))

        public fun of(value: Long, precision: MpfrPrecision = defaultPrecision, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat =
            MpfrFloat(MpfrBridge.ofSintmax(value, precision.bits, rnd.mpfrValueU()))

        public fun of(value: ULong, precision: MpfrPrecision = defaultPrecision, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat =
            MpfrFloat(MpfrBridge.ofUintmax(value.toLong(), precision.bits, rnd.mpfrValueU()))

        /** `mpfr_set_z` side entry for the BigInteger factories (jvmCommon-only; raw GMP magnitude bytes). */
        @InternalMpfrApi
        internal fun ofMpzBE(signum: Int, magnitudeBE: ByteArray, precision: MpfrPrecision, rnd: RoundingMode): MpfrFloat =
            MpfrFloat(MpfrBridge.fromMpz(signum, magnitudeBE, precision.bits, rnd.mpfrValueU()))

        @InternalMpfrApi
        internal fun toMpzBE(value: MpfrFloat, rnd: RoundingMode): Pair<Int, ByteArray> {
            val sign = IntArray(1)
            val bytes = MpfrBridge.toMpz(value.repr, rnd.mpfrValueU(), sign)
            return sign[0] to bytes
        }

        /** ±0 with sign preserved (`Zero(sign, precision)`). */
        public fun Zero(sign: Sign = Sign.POSITIVE, precision: MpfrPrecision = defaultPrecision): MpfrFloat =
            MpfrFloat(Repr.singular(MpfrKind.ZERO, sign == Sign.NEGATIVE, precision))

        /** IEEE ±∞ with sign. */
        public fun Infinity(sign: Sign = Sign.POSITIVE, precision: MpfrPrecision = defaultPrecision): MpfrFloat =
            MpfrFloat(Repr.singular(MpfrKind.INF, sign == Sign.NEGATIVE, precision))

        /** Quiet NaN (no sign). */
        public fun NaN(precision: MpfrPrecision = defaultPrecision): MpfrFloat =
            MpfrFloat(Repr.singular(MpfrKind.NAN, false, precision))

        /** `mpfr_const_pi`. */
        public fun PI(precision: MpfrPrecision = defaultPrecision, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat =
            MpfrFloat(MpfrBridge.constOp(precision.bits, rnd.mpfrValueU(), MpfrConstOp.PI))

        /** `mpfr_const_e`. */
        public fun E(precision: MpfrPrecision = defaultPrecision, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat =
            MpfrFloat(MpfrBridge.constOp(precision.bits, rnd.mpfrValueU(), MpfrConstOp.E))

        /** `mpfr_fac_ui(n!)`. */
        public fun factorial(n: Long, precision: MpfrPrecision = defaultPrecision, rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat {
            require(n >= 0) { "factorial argument must be >= 0, was $n" }
            return MpfrFloat(MpfrBridge.factorial(n, precision.bits, rnd.mpfrValueU()))
        }

        /** `mpfr_sum` — correctly-rounded sum of all items (single rounding of the series). */
        public fun sum(items: List<MpfrFloat>, precision: MpfrPrecision = items.widestPrecision(defaultPrecision), rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat {
            require(items.isNotEmpty()) { "sum() of an empty list is not defined in v1" }
            return MpfrFloat(MpfrBridge.sum(items.map { it.repr }.toTypedArray(), precision.bits, rnd.mpfrValueU()))
        }

        /** `mpfr_dot`. */
        public fun dot(a: List<MpfrFloat>, b: List<MpfrFloat>, precision: MpfrPrecision = (a + b).widestPrecision(defaultPrecision), rnd: RoundingMode = RoundingMode.NEAREST_EVEN): MpfrFloat {
            require(a.isNotEmpty() && a.size == b.size) { "dot() needs two non-empty lists of equal size" }
            return MpfrFloat(MpfrBridge.dot(a.map { it.repr }.toTypedArray(), b.map { it.repr }.toTypedArray(), precision.bits, rnd.mpfrValueU()))
        }

        /**
         * `mpfr_strtofr` wrapper: one `parse`, `Result`-typed, no `endPtr`.
         * [base] = 0 → MPFR auto-detect (`0x`, `0b`, leading-0 octal);
         * bases outside `[0, 2..62]` fail as [MpfrParseError.UnsupportedBase].
         * `NaN`/`inf`/`-inf` literals parse to the specials.
         */
        public fun parse(
            source: String,
            precision: MpfrPrecision = defaultPrecision,
            roundingMode: RoundingMode = RoundingMode.NEAREST_EVEN,
            base: Int = 0,
        ): Result<MpfrFloat, MpfrParseError> {
            if (base != 0 && base !in 2..62) return Result.Failure(MpfrParseError.UnsupportedBase)
            if (source.isEmpty()) return Result.Failure(MpfrParseError.MalformedInput)
            val status = IntArray(1)
            val parsed = MpfrBridge.strtofr(source, precision.bits, base, roundingMode.mpfrValueU(), status)
                ?: return when (status[0]) {
                    -2 -> Result.Failure(MpfrParseError.UnsupportedBase)
                    -3 -> Result.Failure(MpfrParseError.ExponentSyntax)
                    else -> Result.Failure(MpfrParseError.MalformedInput)
                }
            return Result.Success(MpfrFloat(parsed))
        }

        /** Internal factory used by the bridge-wrapping layer (never public). */
        @InternalMpfrApi
        internal fun ofRepr(repr: Repr): MpfrFloat = MpfrFloat(repr)
    }
}

private fun widestPrecision(a: MpfrPrecision, b: MpfrPrecision): MpfrPrecision =
    if (a.bits >= b.bits) a else b

private fun widest(a: MpfrPrecision, b: MpfrPrecision, c: MpfrPrecision): MpfrPrecision =
    widestPrecision(widestPrecision(a, b), c)

private fun List<MpfrFloat>.widestPrecision(fallback: MpfrPrecision): MpfrPrecision =
    maxOfOrNull { it.precision } ?: fallback

/** mpfr_integer_p equivalent over canonical Repr: m·2^e is an integer iff e ≥ 0 ∨ tz(m) ≥ −e. */
internal fun exponentIsInteger(exponent: Long, trailingZerosMantissaBits: Int, mantissaBitsTotal: Int): Boolean {
    if (exponent >= 0L) return true
    val need = -exponent
    return need <= trailingZerosMantissaBits.toLong() && trailingZerosMantissaBits < mantissaBitsTotal
}

@InternalMpfrApi
internal fun NumberStyle.mpfrFormatOp(): Int = when (this) {
    NumberStyle.SCIENTIFIC -> MpfrFormatStyle.SCIENTIFIC
    NumberStyle.FIXED -> MpfrFormatStyle.FIXED
    NumberStyle.GENERAL -> MpfrFormatStyle.GENERAL
}

@InternalMpfrApi
internal fun RoundingMode.mpfrValueU(): UByte = mpfrValue.toUByte()

@InternalMpfrApi
internal fun Long.unaryMinusChecked(): Long {
    check(this != Long.MIN_VALUE) { "2^63-bit shifts are outside the v1 intmax domain" }
    return -this
}
