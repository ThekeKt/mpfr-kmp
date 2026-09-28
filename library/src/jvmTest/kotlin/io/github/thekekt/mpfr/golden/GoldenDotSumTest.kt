package io.github.thekekt.mpfr.golden

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.runNative
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Reference-vector tier for the list operations: correctly-rounded `mpfr_sum`
 * and the `mpfr_dot` crash-guard domain, including both edges of the exponent
 * boundary (inside — a value; outside — the typed domain rejection).
 */
class GoldenDotSumTest {

    private fun floats(cell: String, prec: MpfrPrecision, row: GoldenRow): List<MpfrFloat> =
        cell.split(' ').filter { it.isNotEmpty() }.map { tokenToFloat(it, prec, row) }

    @Test fun dot() = runNative {
        GoldenVectors.file("dot-sum.tsv").rowsWhere(op = "DOT") { row ->
            val prec = precOf(row["prec_out"], row)
            val a = floats(row["a"], prec, row)
            val b = floats(row["b"], prec, row)
            val r = rndOf(row["rnd"], row)
            if (row["kind"] == "IAE") {
                assertFailsWith<IllegalArgumentException>("${row.rowId}: expected domain guard failure") {
                    MpfrFloat.dot(a, b, prec, r)
                }
            } else {
                assertSameValue(row["value"], prec, MpfrFloat.dot(a, b, prec, r), row)
            }
        }
    }

    @Test fun sum() = runNative {
        GoldenVectors.file("dot-sum.tsv").rowsWhere(op = "SUM") { row ->
            val prec = precOf(row["prec_out"], row)
            val a = floats(row["a"], prec, row)
            assertSameValue(row["value"], prec, MpfrFloat.sum(a, prec, rndOf(row["rnd"], row)), row)
        }
    }
}