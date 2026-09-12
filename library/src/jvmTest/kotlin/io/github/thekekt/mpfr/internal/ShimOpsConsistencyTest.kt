package io.github.thekekt.mpfr.internal

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the invariant: the checked-in generated pair
 * (`native/jni/mpfr_kmp_ops.h` X-macros vs `MpfrOps.kt` const ids) never drifts.
 * The hand-written dispatch in `mpfr_kmp_jni.c` expands the same headers, so this
 * check plus the native smoke (every family gets exercised there) is the drift gate.
 * Both files are parsed as text — deterministic, no codegen inputs needed.
 */
class ShimOpsConsistencyTest {

    private val opsHeader = File("native/jni/mpfr_kmp_ops.h")
    private val opsKotlin = File("src/commonMain/kotlin/io/github/thekekt/mpfr/internal/MpfrOps.kt")

    private val families = mapOf(
        "KMP_BINARY_OPS" to "MpfrBinaryOp",
        "KMP_FUSED_OPS" to "MpfrFusedOp",
        "KMP_UNARY_OPS" to "MpfrUnaryOp",
        "KMP_SCALAR_OPS" to "MpfrScalarOp",
        "KMP_DOUBLE_OPS" to "MpfrDoubleOp",
        "KMP_PAIR_OPS" to "MpfrPairOp",
        "KMP_CONST_OPS" to "MpfrConstOp",
        "KMP_PREDICATE_OPS" to "MpfrPredicateOp",
    )

    private fun cPairs(macro: String): Map<String, Int> {
        val text = opsHeader.readText()
        val start = text.indexOf("#define $macro(K)")
        check(start >= 0) { "$macro missing from ${opsHeader.path} — rerun :library:generateShim" }
        val continuation = StringBuilder()
        for (line in text.substring(start).lineSequence().drop(1)) {
            continuation.appendLine(line)
            if (!line.trimEnd().endsWith("\\")) break
        }
        return Regex("""K\(([A-Z0-9_]+),\s*(-?\d+),""").findAll(continuation).associate { m ->
            m.groupValues[1] to m.groupValues[2].toInt()
        }
    }

    private fun kotlinPairs(objectName: String): Map<String, Int> {
        val text = opsKotlin.readText()
        val body = Regex("internal object $objectName \\{([\\s\\S]*?)\\}").find(text)
            ?.groupValues?.get(1)
            ?: error("object $objectName missing from MpfrOps.kt")
        return Regex("""const val ([A-Z0-9_]+): Int = (-?\d+)""").findAll(body).associate { m ->
            m.groupValues[1] to m.groupValues[2].toInt()
        }
    }

    @Test fun cTablesAndKotlinConstantsAgree() {
        for ((macro, obj) in families) {
            val c = cPairs(macro)
            val k = kotlinPairs(obj)
            assertEquals(c, k, "opcode table drift between $macro (ops.h) and $obj (MpfrOps.kt)")
        }
    }
}
