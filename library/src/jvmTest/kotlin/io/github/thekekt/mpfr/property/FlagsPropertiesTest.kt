package io.github.thekekt.mpfr.property

import io.github.thekekt.mpfr.MpfrExceptionFlag
import io.github.thekekt.mpfr.MpfrExceptionFlags
import io.github.thekekt.mpfr.MpfrFlagsMask
import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.RoundingMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Exception-flag properties: the set algebra of [MpfrFlagsMask], the
 * set/clear/save/restore behavior of the global flag set, and the per-operation
 * isolation contract (an operation never mutates the flags visible to callers).
 */
class FlagsPropertiesTest : FunSpec({

    test("flag masks follow the set algebra over the six defined bits") {
        checkAll(PROPS, Arb.int(0, 63), Arb.int(0, 63)) { bitsA, bitsB ->
            val a = maskOf(bitsA)
            val b = maskOf(bitsB)
            check((a + b).bits == (bitsA or bitsB)) { "union" }
            check((a and b).bits == (bitsA and bitsB)) { "intersection" }
            check(a.invert().bits == (63 xor bitsA)) { "complement within the six flags" }
            check(a.invert().invert() == a) { "double complement" }
            check(a.isEmpty == (bitsA == 0)) { "emptiness" }
            for (flag in MpfrExceptionFlag.entries) {
                check((flag in a) == (bitsA and flag.mpfrBit != 0)) { "membership of $flag" }
            }
        }
    }

    test("the global flag set supports set, clear, test, save and restore") {
        if (skipIfNativeMissing()) return@test
        try {
            MpfrExceptionFlags.clearAll()
            check(MpfrExceptionFlags.current().isEmpty()) { "clearAll leaves nothing set" }

            for (flag in MpfrExceptionFlag.entries) {
                MpfrExceptionFlags.set(flag)
                check(flag in MpfrExceptionFlags.current().flags) { "set($flag) is observable" }
                check(flag in MpfrExceptionFlags.test(MpfrFlagsMask.of(flag)).flags) { "test($flag)" }
                MpfrExceptionFlags.clear(flag)
                check(flag !in MpfrExceptionFlags.current().flags) { "clear($flag) is observable" }
            }

            MpfrExceptionFlags.set(MpfrExceptionFlag.UNDERFLOW)
            val savedState = MpfrExceptionFlags.current()
            val snapshot = MpfrExceptionFlags.save()
            MpfrExceptionFlags.set(MpfrExceptionFlag.INEXACT)
            MpfrExceptionFlags.set(MpfrExceptionFlag.DIVBY0)

            snapshot.restore(MpfrFlagsMask.NONE)
            check(MpfrExceptionFlags.current().flags.containsAll(listOf(MpfrExceptionFlag.INEXACT, MpfrExceptionFlag.DIVBY0))) {
                "restore with the empty mask writes nothing"
            }
            snapshot.restore(MpfrFlagsMask.ALL)
            check(MpfrExceptionFlags.current() == savedState) { "restore(ALL) writes the saved state back" }
        } finally {
            MpfrExceptionFlags.clearAll()
        }
    }

    test("native operations never leak their flag deltas to the caller") {
        if (skipIfNativeMissing()) return@test
        checkAll(PROPS, MpfrArbs.anyPair) { (a, b) ->
            val precision = maxOf(a.precision, b.precision)
            try {
                for (flag in MpfrExceptionFlag.entries) MpfrExceptionFlags.set(flag)
                // operations whose internals raise NaN, divide-by-zero, inexact and
                // range conditions, plus a typed parse failure
                a.div(b, RoundingMode.NEAREST_EVEN, precision)
                a.sqrt()
                (-b).log()
                MpfrFloat.parse("not a number", precision)
                a.add(b, RoundingMode.NEAREST_EVEN, precision)
                check(MpfrExceptionFlags.current().size == 6) {
                    "an operation modified the observable flag set: ${MpfrExceptionFlags.current()}"
                }
            } finally {
                MpfrExceptionFlags.clearAll()
            }
        }
    }
}) {
    private companion object {
        fun maskOf(bits: Int): MpfrFlagsMask =
            MpfrFlagsMask.of(*MpfrExceptionFlag.entries.filter { bits and it.mpfrBit != 0 }.toTypedArray())
    }
}