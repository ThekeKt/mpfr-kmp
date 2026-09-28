package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.core.MpfrParseError
import io.github.thekekt.mpfr.core.Result
import io.github.thekekt.mpfr.core.Sign
import io.github.thekekt.mpfr.of
import io.github.thekekt.mpfr.toBigInteger
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigInteger

/**
 * Parse/format and integer-crossing properties: the decimal round trip at a fixed
 * width, the base boundaries of the parser, the special literals, and the
 * BigInteger crossings at full width.
 */
class ParseRangePropertiesTest : FunSpec({

    test("decimal strings round-trip exactly at the same width") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.any) { x ->
            val text = x.toString()
            val parsed = MpfrFloat.parse(text, x.precision).valueOrFail()
            assertSameValue(x, parsed) { "round trip through '$text'" }
        }
    }

    test("the parser accepts exactly the documented base range") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, Arb.int(-10, 70)) { base ->
            if (base != 0 && base !in 2..62) {
                val result = MpfrFloat.parse("1", base = base)
                check(result is Result.Failure && result.error == MpfrParseError.UnsupportedBase) {
                    "base $base must be rejected as unsupported"
                }
            } else {
                val result = MpfrFloat.parse("1", base = base)
                check(result is Result.Success) { "base $base must be accepted" }
                assertSameValue(MpfrFloat.of(1L, MpfrFloat.defaultPrecision), result.value) {
                    "1 parses to one at base $base"
                }
            }
        }
    }

    test("malformed input and the empty string fail typed") {
        val empty = MpfrFloat.parse("")
        check(empty is Result.Failure && empty.error == MpfrParseError.MalformedInput) { "empty input" }
        if (skipIfNativeMissing()) return@test
        val garbage = MpfrFloat.parse("not a number")
        check(garbage is Result.Failure && garbage.error == MpfrParseError.MalformedInput) { "garbage input" }
    }

    test("special literals parse to the canonical specials") {
        if (skipIfNativeMissing()) return@test
        val precision = MpfrPrecision(53)
        val nan = MpfrFloat.parse("NaN", precision).valueOrFail()
        check(nan.isNaN) { "NaN literal" }
        for (literal in listOf("inf", "+inf", "Infinity")) {
            val value = MpfrFloat.parse(literal, precision).valueOrFail()
            check(value.isInfinite && !value.isNegative) { "$literal literal" }
        }
        val negative = MpfrFloat.parse("-inf", precision).valueOrFail()
        assertSameValue(MpfrFloat.Infinity(Sign.NEGATIVE, precision), negative) { "-inf literal" }
    }

    test("BigInteger crossings round-trip at full width") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, Arb.long(), Arb.long()) { high, low ->
            val value = BigInteger.valueOf(high).shiftLeft(64).add(BigInteger.valueOf(low))
            val precision = MpfrPrecision(129)
            val wrapped = MpfrFloat.of(value, precision)
            check(wrapped.toBigInteger() == value) { "BigInteger round trip of $value" }
        }
    }
})