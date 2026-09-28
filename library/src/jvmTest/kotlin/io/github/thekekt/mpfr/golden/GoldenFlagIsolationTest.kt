package io.github.thekekt.mpfr.golden

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrRangeException
import io.github.thekekt.mpfr.runNative
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Focused flag-isolation coverage for the failure paths: the preset-all-six
 * contract must hold not only for ordinary value rows (checked inline by every
 * row driver) but also when the wrapper throws a typed failure or rejects a
 * domain before touching MPFR.
 */
class GoldenFlagIsolationTest {

    @Test fun presetFlagsSurviveTypedRangeFailures() = runNative {
        GoldenVectors.file("conversions.tsv").rowsWhere("CONV") { row ->
            if (row["kind"] != "RANGE_EX") return@rowsWhere
            val x = tokenToFloat(row["input"], precOf(row["prec"], row), row)
            val r = rndOf(row["rnd"], row)
            assertFailsWith<MpfrRangeException>("${row.rowId}: expected range failure") {
                when (row["op"]) {
                    "TO_LONG" -> x.toLong(r)
                    "TO_INT" -> x.toInt(r)
                    else -> error("${row.rowId}: unexpected range-failure row '${row["op"]}'")
                }
            }
        }
    }

    @Test fun presetFlagsSurviveDomainGuardFailures() = runNative {
        GoldenVectors.file("dot-sum.tsv").rowsWhere(op = "DOT") { row ->
            if (row["kind"] != "IAE") return@rowsWhere
            val prec = precOf(row["prec_out"], row)
            val a = row["a"].split(' ').filter { it.isNotEmpty() }.map { tokenToFloat(it, prec, row) }
            val b = row["b"].split(' ').filter { it.isNotEmpty() }.map { tokenToFloat(it, prec, row) }
            assertFailsWith<IllegalArgumentException>("${row.rowId}: expected domain guard failure") {
                MpfrFloat.dot(a, b, prec, rndOf(row["rnd"], row))
            }
        }
    }

    @Test fun presetFlagsSurviveParseFailures() = runNative {
        GoldenVectors.file("strings.tsv").rowsWhere("STR", op = "PARSE") { row ->
            if (row["kind"] == "REG") return@rowsWhere
            MpfrFloat.parse(row["input"], precOf(row["prec"], row), rndOf(row["rnd"], row), row["base"].toInt())
        }
    }
}