package io.github.thekekt.mpfr.golden

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drift gate for the reference-vector set: every operation the generated shim
 * tables expose must have at least one captured row (and no fixture may name
 * an operation the tables do not know), every row category must be executed by
 * a test method, row ids must be unique, and the total row count must stay
 * inside the agreed budget.
 */
class GoldenCoverageConsistencyTest {

    private val opsHeader = File("native/jni/mpfr_kmp_ops.h")

    private fun macroNames(macro: String): Set<String> {
        val text = opsHeader.readText()
        val start = text.indexOf("#define $macro(K)")
        check(start >= 0) { "$macro missing from ${opsHeader.path} — rerun :library:generateShim" }
        val continuation = StringBuilder()
        for (line in text.substring(start).lineSequence().drop(1)) {
            continuation.appendLine(line)
            if (!line.trimEnd().endsWith("\\")) break
        }
        return Regex("""K\(([A-Z0-9_]+),\s*-?\d+,""").findAll(continuation)
            .map { it.groupValues[1] }.toSet()
    }

    private fun opsOf(file: String): Set<String> =
        GoldenVectors.file(file).rows.map { it["op"] }.toSet()

    @Test fun everyShimOperationHasVectors() {
        val families = mapOf(
            "KMP_BINARY_OPS" to "ops-binary.tsv",
            "KMP_FUSED_OPS" to "ops-fused.tsv",
            "KMP_UNARY_OPS" to "ops-unary.tsv",
            "KMP_SCALAR_OPS" to "ops-scalar.tsv",
            "KMP_DOUBLE_OPS" to "ops-double.tsv",
            "KMP_PAIR_OPS" to "ops-pair.tsv",
            "KMP_CONST_OPS" to "consts.tsv",
            "KMP_PREDICATE_OPS" to "predicates.tsv",
        )
        for ((macro, file) in families) {
            val shim = macroNames(macro)
            val fixtures = opsOf(file)
            assertTrue(fixtures.containsAll(shim), "$file: missing rows for ${shim - fixtures}")
            assertTrue(shim.containsAll(fixtures), "$file: unknown operations ${fixtures - shim}")
        }
    }

    @Test fun nonOperationFamiliesAreCovered() {
        assertEquals(setOf("CMP", "EQUAL_ULPS", "TOTAL_ORDER"), opsOf("cmp.tsv"))
        assertEquals(setOf("DOT", "SUM"), opsOf("dot-sum.tsv"))
        assertEquals(setOf("FACTORIAL"), opsOf("factorial.tsv"))
        assertEquals(
            setOf(
                "OF_LONG", "OF_ULONG", "OF_DOUBLE", "OF_FLOAT", "OF_BIGINT",
                "TO_DOUBLE", "TO_FLOAT", "TO_LONG", "TO_INT", "FITS_LONG", "FITS_INT", "TO_BIGINT",
            ),
            opsOf("conversions.tsv"),
        )
        assertEquals(setOf("PARSE", "GET_STR", "FORMAT", "TO_STRING"), opsOf("strings.tsv"))
    }

    @Test fun categoriesAreFullyHandled() {
        val expected = mapOf(
            "ops-binary.tsv" to setOf("CORE", "SPECIAL", "TIE", "HIGHPREC"),
            "ops-fused.tsv" to setOf("CORE", "SPECIAL", "TIE"),
            "ops-unary.tsv" to setOf("CORE", "SPECIAL", "GRID", "HIGHPREC"),
            "ops-scalar.tsv" to setOf("CORE", "SPECIAL"),
            "ops-double.tsv" to setOf("CORE", "SPECIAL", "TIE"),
            "ops-pair.tsv" to setOf("CORE", "SPECIAL", "GRID"),
            "consts.tsv" to setOf("CORE"),
            "predicates.tsv" to setOf("PRED"),
            "cmp.tsv" to setOf("CMP"),
            "dot-sum.tsv" to setOf("CORE", "BOUNDARY", "SPECIAL"),
            "factorial.tsv" to setOf("CORE"),
            "conversions.tsv" to setOf("CONV"),
            "strings.tsv" to setOf("STR"),
        )
        for ((file, cats) in expected) {
            assertEquals(cats, GoldenVectors.file(file).rows.map { it["category"] }.toSet(), file)
        }
    }

    @Test fun rowIdsAreUniqueAndBudgetHolds() {
        var total = 0
        for ((name, file) in GoldenVectors.files) {
            val ids = file.rows.map { it.rowId }
            assertEquals(ids.size, ids.toSet().size, "$name: duplicate row ids")
            total += ids.size
        }
        assertTrue(total <= 12_000, "fixture budget exceeded: $total rows (cap 12000)")
    }
}