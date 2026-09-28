package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.core.Sign
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.checkAll

/**
 * Order and identity properties: the total order of `compareTo` (antisymmetry,
 * transitivity, agreement with the comparison predicates), the NaN and signed-zero
 * placement, precision-aware structural equality, and the equals/hashCode contract.
 */
class OrderIdentityPropertiesTest : FunSpec({

    test("compareTo is antisymmetric and transitive over arbitrary values") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.anyTriple) { (a, b, c) ->
            val ab = a.compareTo(b)
            val ba = b.compareTo(a)
            check(Integer.signum(ab) == -Integer.signum(ba)) {
                "antisymmetry: $a vs $b gave $ab and $ba"
            }
            if (ab <= 0 && b.compareTo(c) <= 0) {
                check(a.compareTo(c) <= 0) { "transitivity across $a, $b, $c" }
            }
            check(Integer.signum(a.compareTo(a)) == 0) { "reflexivity of $a" }
        }
    }

    test("compareTo agrees with the comparison predicates on ordered pairs") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.anyPair) { (a, b) ->
            if (a.lt(b)) {
                check(a.compareTo(b) < 0) { "$a lt $b but compareTo says ${a.compareTo(b)}" }
            }
            if (a.gt(b)) {
                check(a.compareTo(b) > 0) { "$a gt $b but compareTo says ${a.compareTo(b)}" }
            }
            if (!a.isNaN && !b.isNaN && !a.lt(b) && !b.lt(a)) {
                if (a.isZero && b.isZero) {
                    val expected = when {
                        a.isNegative != b.isNegative -> if (a.isNegative) -1 else 1
                        else -> Integer.signum(a.precision.bits.compareTo(b.precision.bits))
                    }
                    check(a.compareTo(b) == expected) { "signed-zero order of $a vs $b" }
                } else {
                    check(
                        Integer.signum(a.compareTo(b)) ==
                            Integer.signum(a.precision.bits.compareTo(b.precision.bits)),
                    ) { "numerically-equal $a vs $b orders by width" }
                }
            }
        }
    }

    test("NaN sorts above every non-NaN value and orders by precision against NaN") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.any, MpfrArbs.nanFirstPair) { value, (nan, _) ->
            if (!value.isNaN) {
                check(nan.compareTo(value) > 0) { "NaN vs $value" }
                check(value.compareTo(nan) < 0) { "$value vs NaN" }
            }
        }
        check(MpfrFloat.NaN(MpfrPrecision(53)).compareTo(MpfrFloat.NaN(MpfrPrecision(64))) < 0) {
            "NaN ordering by width"
        }
    }

    test("signed zeros and precision ties follow the documented order") {
        if (skipIfNativeMissing()) return@test
        val precision = MpfrPrecision(53)
        val posZero = MpfrFloat.Zero(Sign.POSITIVE, precision)
        val negZero = MpfrFloat.Zero(Sign.NEGATIVE, precision)
        check(negZero.compareTo(posZero) < 0) { "-0 sorts below +0" }
        check(negZero.totalOrder(posZero)) { "total order: -0 <= +0" }
        check(!posZero.totalOrder(negZero)) { "total order: +0 <= -0 is false" }
        check(posZero.compareTo(negZero) > 0) { "+0 sorts above -0" }

        val low = MpfrFloat.of(1L, MpfrPrecision(53))
        val high = MpfrFloat.of(1L, MpfrPrecision(64))
        check(low.compareTo(high) < 0) { "a numerically-equal pair orders by precision" }
        check(high.compareTo(low) > 0) { "and the reverse flips" }
        check(!low.lt(high) && !high.lt(low)) { "the pair is numerically equal" }
        check(low.totalOrder(high) && high.totalOrder(low)) { "total order treats the pair as equivalent" }
    }

    test("equality is structural, reflexive for non-NaN, and agrees with hashCode") {
        checkAll(PROPS, MpfrArbs.anyPair) { (a, b) ->
            if (a.isNaN) {
                check(a != a) { "NaN never equals itself" }
            } else {
                check(a == a) { "non-NaN self-equality of $a" }
            }
            if (a == b) {
                check(a.hashCode() == b.hashCode()) { "equal values share a hash: $a" }
                check(a.precision == b.precision) { "equality implies equal width" }
            }
            if (a.isZero && b.isZero && a.isNegative != b.isNegative) {
                check(a != b) { "signed zeros are distinct" }
            }
        }
    }
})