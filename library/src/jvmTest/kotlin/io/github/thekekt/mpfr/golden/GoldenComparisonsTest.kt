package io.github.thekekt.mpfr.golden

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.internal.MpfrBridge
import io.github.thekekt.mpfr.internal.MpfrPredicateOp
import io.github.thekekt.mpfr.runNative
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reference-vector tier for the comparison surface: the eight MPFR predicates
 * (including the raw `mpfr_equal_p` bridge case), the `mpfr_cmp` sign rows,
 * `mpfr_total_order_p`, and `mpfr_eq` (`equalUlps`).
 */
class GoldenComparisonsTest {

    private fun runPredicate(row: GoldenRow): Boolean {
        val (pa, pb) = pairPrecisions(row)
        val a = tokenToFloat(row["a"], pa, row)
        val b = tokenToFloat(row["b"], pb, row)
        return when (row["op"]) {
            "EQUAL" -> MpfrBridge.cmpPredicate(a.repr, b.repr, MpfrPredicateOp.EQUAL)
            "LESS" -> a lt b
            "LESS_EQUAL" -> a lte b
            "GREATER" -> a gt b
            "GREATER_EQUAL" -> a gte b
            "UNORDERED" -> a isUnorderedWith b
            "LESS_GREATER" -> a isLessOrGreater b
            "TOTAL_ORDER" -> a totalOrder b
            else -> error("${row.rowId}: unknown predicate '${row["op"]}'")
        }
    }

    private fun pairPrecisions(row: GoldenRow) =
        precOf(row["prec_a"], row) to precOf(row["prec_b"], row)

    @Test fun predicates() = runNative {
        GoldenVectors.file("predicates.tsv").rowsWhere("PRED") { row ->
            val hit = runPredicate(row)
            assertEquals(row["value"] == "1", hit, "${row.rowId}: predicate result")
        }
    }

    @Test fun cmpSignRows() = runNative {
        GoldenVectors.file("cmp.tsv").rowsWhere("CMP", op = "CMP") { row ->
            val (pa, pb) = pairPrecisions(row)
            val a = tokenToFloat(row["a"], pa, row)
            val b = tokenToFloat(row["b"], pb, row)
            val expected = row["value"].toInt()
            val bridge = MpfrBridge.compareInt(a.repr, b.repr)
            assertEquals(expected, bridge, "${row.rowId}: mpfr_cmp sign")
            val public = a.compareTo(b).coerceIn(-1, 1)
            assertEquals(expected, public, "${row.rowId}: compareTo sign")
        }
    }

    @Test fun equalUlpsRows() = runNative {
        GoldenVectors.file("cmp.tsv").rowsWhere("CMP", op = "EQUAL_ULPS") { row ->
            val (pa, pb) = pairPrecisions(row)
            val a = tokenToFloat(row["a"], pa, row)
            val b = tokenToFloat(row["b"], pb, row)
            val hit = a.equalUlps(b, row["k"].toInt())
            assertEquals(row["value"] == "1", hit, "${row.rowId}: equalUlps")
        }
    }

    @Test fun totalOrderRows() = runNative {
        GoldenVectors.file("cmp.tsv").rowsWhere("CMP", op = "TOTAL_ORDER") { row ->
            val (pa, pb) = pairPrecisions(row)
            val a = tokenToFloat(row["a"], pa, row)
            val b = tokenToFloat(row["b"], pb, row)
            val hit = a.totalOrder(b)
            assertEquals(row["value"] == "1", hit, "${row.rowId}: totalOrder verbatim")
        }
    }
}