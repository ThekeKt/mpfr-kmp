package io.github.thekekt.mpfr.shim

/**
 * Canonical shim dispatch tables. Single source of truth for BOTH emitted
 * artifacts:
 *
 *  - `library/native/jni/mpfr_kmp_jni.c` — the JNI shim;
 *  - `library/src/commonMain/kotlin/io/github/thekekt/mpfr/internal/MpfrOps.kt` —
 *    the Kotlin opcode constants consumed by `commonMain`.
 *
 * Every `c` line is the exact MPFR call anchored by the cited `mpfr.h` or
 * manual row. Rerun `gradlew :library:generateShim` after editing; both files
 * are checked in.
 */
data class OpEntry(val id: Int, val name: String, val c: String, val cite: String)

object ShimTables {

    // binary — variables in scope: rop, x, y, r
    val binary = listOf(
        OpEntry(0, "ADD", "mpfr_add(rop, x, y, r)", "mpfr.h L580"),
        OpEntry(1, "SUB", "mpfr_sub(rop, x, y, r)", "mpfr.h L583"),
        OpEntry(2, "MUL", "mpfr_mul(rop, x, y, r)", "mpfr.h L585"),
        OpEntry(3, "DIV", "mpfr_div(rop, x, y, r)", "mpfr.h L587"),
        OpEntry(4, "POW", "mpfr_pow(rop, x, y, r)", "mpfr_pow"),
        // receiver is the ordinate (y-coordinate), argument the abscissa:
        // mpfr_atan2 takes (y, x) per the MPFR manual.
        OpEntry(5, "ATAN2", "mpfr_atan2(rop, x, y, r)", "mpfr.h L735"),
        OpEntry(6, "MIN", "mpfr_min(rop, x, y, r)", "mpfr_min: NaN propagates verbatim"),
        OpEntry(7, "MAX", "mpfr_max(rop, x, y, r)", "mpfr_max: NaN propagates verbatim"),
        OpEntry(8, "DIM", "mpfr_dim(rop, x, y, r)", "mpfr_dim"),
        OpEntry(9, "HYPOT", "mpfr_hypot(rop, x, y, r)", "mpfr_hypot"),
        OpEntry(10, "AGM", "mpfr_agm(rop, x, y, r)", "mpfr_agm"),
        OpEntry(11, "FMOD", "mpfr_fmod(rop, x, y, r)", "mpfr_fmod"),
        OpEntry(12, "REMAINDER", "mpfr_remainder(rop, x, y, r)", "mpfr_remainder"),
        // mpfr.texi: computes |op1 − op2| / |op1|; NOT an exactly-rounded relative
        // difference (a max(|a|,|b|) denominator would summarize it loosely —
        // the upstream MPFR call semantics decide).
        OpEntry(13, "RELDIFF", "mpfr_reldiff(rop, x, y, r)", "mpfr.texi: |op1−op2|/|op1|, not exactly rounded"),
    )

    val fused = listOf(
        OpEntry(0, "FMA", "mpfr_fma(rop, x, y, z, r)", "mpfr_fma: fused multiply-add"),
        OpEntry(1, "FMS", "mpfr_fms(rop, x, y, z, r)", "mpfr_fms: fused minus-multiply"),
    )

    // unary — scope: rop, x, r (prec already applied to rop)
    val unary = listOf(
        OpEntry(0, "SQR", "mpfr_sqr(rop, x, r)", "mpfr_sqr"),
        OpEntry(1, "SQRT", "mpfr_sqrt(rop, x, r)", "mpfr_sqrt"),
        OpEntry(2, "CBRT", "mpfr_cbrt(rop, x, r)", "mpfr_cbrt"),
        OpEntry(3, "REC_SQRT", "mpfr_rec_sqrt(rop, x, r)", "mpfr.h L578 (sanctioned name: reciprocalSqrt)"),
        OpEntry(4, "EXP", "mpfr_exp(rop, x, r)", "MPFR manual: exponential functions"),
        OpEntry(5, "EXP2", "mpfr_exp2(rop, x, r)", "MPFR manual: exponential functions"),
        OpEntry(6, "EXP10", "mpfr_exp10(rop, x, r)", "MPFR manual: exponential functions"),
        OpEntry(7, "EXPM1", "mpfr_expm1(rop, x, r)", "MPFR manual: near-one exponentials"),
        OpEntry(8, "EXP2M1", "mpfr_exp2m1(rop, x, r)", "MPFR manual: near-one exponentials"),
        OpEntry(9, "EXP10M1", "mpfr_exp10m1(rop, x, r)", "MPFR manual: near-one exponentials"),
        OpEntry(10, "LOG", "mpfr_log(rop, x, r)", "MPFR manual: logarithm functions"),
        OpEntry(11, "LOG2", "mpfr_log2(rop, x, r)", "MPFR manual: logarithm functions"),
        OpEntry(12, "LOG10", "mpfr_log10(rop, x, r)", "MPFR manual: logarithm functions"),
        OpEntry(13, "LOG1P", "mpfr_log1p(rop, x, r)", "MPFR manual: near-one logarithms"),
        OpEntry(14, "LOG2P1", "mpfr_log2p1(rop, x, r)", "MPFR manual: near-one logarithms"),
        OpEntry(15, "LOG10P1", "mpfr_log10p1(rop, x, r)", "MPFR manual: near-one logarithms"),
        OpEntry(16, "SIN", "mpfr_sin(rop, x, r)", "MPFR manual: trigonometric functions"),
        OpEntry(17, "COS", "mpfr_cos(rop, x, r)", "MPFR manual: trigonometric functions"),
        OpEntry(18, "TAN", "mpfr_tan(rop, x, r)", "MPFR manual: trigonometric functions"),
        OpEntry(19, "ASIN", "mpfr_asin(rop, x, r)", "MPFR manual: inverse trigonometric functions"),
        OpEntry(20, "ACOS", "mpfr_acos(rop, x, r)", "MPFR manual: inverse trigonometric functions"),
        OpEntry(21, "ATAN", "mpfr_atan(rop, x, r)", "MPFR manual: inverse trigonometric functions"),
        OpEntry(22, "SINH", "mpfr_sinh(rop, x, r)", "MPFR manual: hyperbolic functions"),
        OpEntry(23, "COSH", "mpfr_cosh(rop, x, r)", "MPFR manual: hyperbolic functions"),
        OpEntry(24, "TANH", "mpfr_tanh(rop, x, r)", "MPFR manual: hyperbolic functions"),
        OpEntry(25, "ASINH", "mpfr_asinh(rop, x, r)", "MPFR manual: inverse hyperbolic functions"),
        OpEntry(26, "ACOSH", "mpfr_acosh(rop, x, r)", "MPFR manual: inverse hyperbolic functions"),
        OpEntry(27, "ATANH", "mpfr_atanh(rop, x, r)", "MPFR manual: inverse hyperbolic functions"),
        OpEntry(28, "SINPI", "mpfr_sinpi(rop, x, r)", "MPFR manual: sin(pi*x) family"),
        OpEntry(29, "COSPI", "mpfr_cospi(rop, x, r)", "MPFR manual: sin(pi*x) family"),
        OpEntry(30, "TANPI", "mpfr_tanpi(rop, x, r)", "MPFR manual: sin(pi*x) family"),
        OpEntry(31, "ERF", "mpfr_erf(rop, x, r)", "MPFR manual: special functions"),
        OpEntry(32, "ERFC", "mpfr_erfc(rop, x, r)", "MPFR manual: special functions"),
        OpEntry(33, "GAMMA", "mpfr_gamma(rop, x, r)", "MPFR manual: special functions"),
        OpEntry(34, "LNGAMMA", "mpfr_lngamma(rop, x, r)", "MPFR manual: special functions"),
        OpEntry(35, "DIGAMMA", "mpfr_digamma(rop, x, r)", "MPFR manual: special functions"),
        OpEntry(36, "ZETA", "mpfr_zeta(rop, x, r)", "MPFR manual: special functions"),
        OpEntry(37, "TRUNC", "mpfr_trunc(rop, x)", "MPFR manual: rounding & integer functions (rnd-free)"),
        OpEntry(38, "FLOOR", "mpfr_floor(rop, x)", "MPFR manual: rounding & integer functions (rnd-free)"),
        OpEntry(39, "CEIL", "mpfr_ceil(rop, x)", "MPFR manual: rounding & integer functions (rnd-free)"),
        OpEntry(40, "ROUND", "mpfr_round(rop, x)", "MPFR manual: ties away from zero (rnd-free)"),
        OpEntry(41, "ROUND_EVEN", "mpfr_roundeven(rop, x)", "mpfr_roundeven: ties to nearest-even"),
        OpEntry(42, "FRAC", "mpfr_frac(rop, x, r)", "MPFR manual: fractional part"),
        OpEntry(43, "NEXTUP", "mpfr_nextabove(rop)", "mpfr_nextabove (x copied into rop first)"),
        OpEntry(44, "NEXTDOWN", "mpfr_nextbelow(rop)", "mpfr_nextbelow"),
    )

    const val TMP_PREC_INT = 65L   // sintmax (63b) + uintmax (64b) exact with headroom
    const val TMP_PREC_DBL = 53L   // double significand exact at 53

    // scalar — scope: rop, x, tmp, k (intmax), r; tmp is initialized before the switch
    val scalar = listOf(
        OpEntry(0, "ADD", "mpfr_set_sj(tmp, (intmax_t) k, MPFR_RNDN); mpfr_add(rop, x, tmp, r)", "intmax crossing via exact tmp (scalar results keep receiver precision)"),
        OpEntry(1, "SUB", "mpfr_set_sj(tmp, (intmax_t) k, MPFR_RNDN); mpfr_sub(rop, x, tmp, r)", "intmax crossing via exact tmp"),
        OpEntry(2, "MUL", "mpfr_set_sj(tmp, (intmax_t) k, MPFR_RNDN); mpfr_mul(rop, x, tmp, r)", "intmax crossing via exact tmp"),
        OpEntry(3, "DIV", "mpfr_set_sj(tmp, (intmax_t) k, MPFR_RNDN); mpfr_div(rop, x, tmp, r)", "intmax crossing via exact tmp; k=0 → MPFR ±Inf/NaN + DIVBY0"),
        OpEntry(4, "MUL_2EXP", "mpfr_mul_2ui(rop, x, (unsigned long) k, r)", "power-of-two scaling (n≥0 by Kotlin contract)"),
        OpEntry(5, "DIV_2EXP", "mpfr_div_2ui(rop, x, (unsigned long) k, r)", "power-of-two scaling (n≥0 by Kotlin contract)"),
        OpEntry(6, "ROOTN", "mpfr_rootn_ui(rop, x, (unsigned long) k, r)", "mpfr_rootn_ui: n-th root"),
        OpEntry(7, "COMPOUND", "mpfr_compound_si(rop, x, (long) k, r)", "mpfr_compound_si: (1+x)^k"),
    )

    // double — scope: rop, x, tmp, d, r
    val doubleOps = listOf(
        OpEntry(0, "ADD", "mpfr_set_d(tmp, d, MPFR_RNDN); mpfr_add(rop, x, tmp, r)", "mixed double, exact tmp"),
        OpEntry(1, "SUB", "mpfr_set_d(tmp, d, MPFR_RNDN); mpfr_sub(rop, x, tmp, r)", "mixed double, exact tmp"),
        OpEntry(2, "MUL", "mpfr_set_d(tmp, d, MPFR_RNDN); mpfr_mul(rop, x, tmp, r)", "mixed double, exact tmp"),
        OpEntry(3, "DIV", "mpfr_set_d(tmp, d, MPFR_RNDN); mpfr_div(rop, x, tmp, r)", "mixed double, exact tmp"),
    )

    // pair — scope: rop (first out), rop2 (second out), x, r
    val pair = listOf(
        OpEntry(0, "MODF", "mpfr_modf(rop, rop2, x, r)", "mpfr_modf: [integer, fraction]"),
        OpEntry(1, "SIN_COS", "mpfr_sin_cos(rop, rop2, x, r)", "mpfr_sin_cos: first output is sin"),
    )

    // const — scope: rop, tmp, r
    val consts = listOf(
        OpEntry(0, "PI", "mpfr_const_pi(rop, r)", "mpfr_const_pi (MPFR manual: constants)"),
        // mpfr_const_e absent from the pinned 4.2.1 header (only const_euler, L616):
        // E := exp(1) — mathematically identical, single correct rounding.
        OpEntry(1, "E", "mpfr_set_ui(tmp, 1, MPFR_RNDN); mpfr_exp(rop, tmp, r)", "4.2.1 lacks mpfr_const_e → exp(1)"),
    )

    // predicates — scope: x, y (returns truthy int)
    val predicates = listOf(
        OpEntry(0, "EQUAL", "mpfr_equal_p(x, y)", "mpfr.h L712"),
        OpEntry(1, "LESS", "mpfr_less_p(x, y)", "MPFR manual: comparisons"),
        OpEntry(2, "LESS_EQUAL", "mpfr_lessequal_p(x, y)", "MPFR manual: comparisons"),
        OpEntry(3, "GREATER", "mpfr_greater_p(x, y)", "MPFR manual: comparisons"),
        OpEntry(4, "GREATER_EQUAL", "mpfr_greaterequal_p(x, y)", "MPFR manual: comparisons"),
        OpEntry(5, "UNORDERED", "mpfr_unordered_p(x, y)", "MPFR manual: comparisons"),
        OpEntry(6, "LESS_GREATER", "mpfr_lessgreater_p(x, y)", "MPFR manual: comparisons"),
        OpEntry(7, "TOTAL_ORDER", "mpfr_total_order_p(x, y)", "mpfr.h L834"),
    )

    val formatStyles = listOf(
        OpEntry(0, "SCIENTIFIC", "e", "mpfr_snprintf %R family"),
        OpEntry(1, "FIXED", "f", "mpfr_snprintf %R family"),
        OpEntry(2, "GENERAL", "g", "mpfr_snprintf %R family"),
    )
}