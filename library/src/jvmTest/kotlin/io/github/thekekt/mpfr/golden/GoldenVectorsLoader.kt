package io.github.thekekt.mpfr.golden

import io.github.thekekt.mpfr.MpfrExceptionFlag
import io.github.thekekt.mpfr.MpfrExceptionFlags
import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrPrecision
import io.github.thekekt.mpfr.RoundingMode
import io.github.thekekt.mpfr.core.Result
import io.github.thekekt.mpfr.core.Sign
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One reference-vector row: the raw TSV cells of a fixture file plus its
 * origin, so failure messages can always name `<row_id> of <file>`.
 */
internal class GoldenRow(val file: String, private val cells: Map<String, String>) {

    val rowId: String get() = cells.getValue("row_id")

    operator fun get(column: String): String =
        cells[column] ?: error("column '$column' missing (row $rowId of $file)")
}

internal class GoldenFile(val name: String, val rows: List<GoldenRow>)

/**
 * Reads the captured reference-vector fixtures from the test classpath and
 * verifies the manifest before any row is executed: schema version, the pinned
 * MPFR version, per-file row counts, and per-file SHA-256. A stale or
 * hand-edited fixture set fails here, before the first assertion.
 */
internal object GoldenVectors {

    /** The MPFR version the committed fixtures must have been captured from. */
    private const val PINNED_MPFR_VERSION = "4.2.1"

    private val fileOrder = listOf(
        "ops-binary.tsv", "ops-fused.tsv", "ops-unary.tsv", "ops-scalar.tsv",
        "ops-double.tsv", "ops-pair.tsv", "consts.tsv", "predicates.tsv",
        "cmp.tsv", "dot-sum.tsv", "factorial.tsv", "conversions.tsv", "strings.tsv",
    )

    val files: Map<String, GoldenFile> by lazy { loadAll() }

    fun file(name: String): GoldenFile = files.getValue(name)

    private fun readResource(path: String): ByteArray =
        GoldenVectors::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("missing test resource $path (were the golden fixtures installed?)")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun loadAll(): Map<String, GoldenFile> {
        val manifest = String(readResource("/golden/manifest.tsv"), Charsets.UTF_8)
            .lineSequence().filter { it.isNotEmpty() }.map { it.split('\t').map(String::trim) }.toList()
        val entries = manifest.drop(1)
        assertEquals("1", entries.first { it[0] == "schema_version" }[1], "manifest schema version")
        assertEquals(
            PINNED_MPFR_VERSION, entries.first { it[0] == "mpfr_version" }[1],
            "fixtures were captured from a different MPFR build than the pinned one",
        )
        val perFile = entries.filter { it[0] == "file" }.associate { it[1] to (it[2] to it[3]) }
        assertEquals(fileOrder.toSet(), perFile.keys, "manifest file set")

        val loaded = LinkedHashMap<String, GoldenFile>()
        for (name in fileOrder) {
            val (rowCount, sha) = perFile.getValue(name)
            val bytes = readResource("/golden/$name")
            assertEquals(sha, sha256Hex(bytes), "$name: SHA-256 differs from the manifest")
            val lines = String(bytes, Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
            val header = lines.first().split('\t')
            val rows = lines.drop(1).map { line ->
                val cells = line.split('\t')
                assertEquals(header.size, cells.size, "$name: malformed row: ${line.take(60)}")
                GoldenRow(name, header.zip(cells).toMap())
            }
            assertEquals(rowCount.toInt(), rows.size, "$name: row count differs from the manifest")
            loaded[name] = GoldenFile(name, rows)
        }
        return loaded
    }
}

internal fun rndOf(token: String, row: GoldenRow): RoundingMode =
    RoundingMode.entries.firstOrNull { it.toString() == token }
        ?: error("${row.rowId}: unknown rounding token '$token'")

internal fun precOf(cell: String, row: GoldenRow): MpfrPrecision =
    MpfrPrecision(cell.toIntOrNull() ?: error("${row.rowId}: bad precision '$cell'"))

/** Rebuilds the exact value a fixture cell denotes (hex literal or special tag). */
internal fun tokenToFloat(token: String, prec: MpfrPrecision, row: GoldenRow): MpfrFloat = when (token) {
    "@nan@" -> MpfrFloat.NaN(prec)
    "@inf+" -> MpfrFloat.Infinity(Sign.POSITIVE, prec)
    "@inf-" -> MpfrFloat.Infinity(Sign.NEGATIVE, prec)
    "@zero+" -> MpfrFloat.Zero(Sign.POSITIVE, prec)
    "@zero-" -> MpfrFloat.Zero(Sign.NEGATIVE, prec)
    else -> when (val parsed = MpfrFloat.parse(token, prec, base = 0)) {
        is Result.Success -> parsed.value
        is Result.Failure ->
            error("${row.rowId}: literal '$token' does not parse at prec ${prec.bits} (${parsed.error})")
    }
}

/** Structural comparison against a fixture expectation; NaN compares by kind + precision. */
internal fun assertSameValue(expectedToken: String, precOut: MpfrPrecision, actual: MpfrFloat, row: GoldenRow) {
    val expected = tokenToFloat(expectedToken, precOut, row)
    if (expected.isNaN) {
        assertTrue(actual.isNaN, "${row.rowId}: expected NaN, got $actual")
        assertEquals(precOut.bits, actual.precision.bits, "${row.rowId}: result precision")
    } else {
        assertEquals(expected, actual, "${row.rowId}: value mismatch (expected=$expected actual=$actual)")
    }
}

/**
 * Presets all six exception flags, runs [body], and asserts afterwards that the
 * preset survived untouched — the observable flag-isolation contract of the
 * native bridge (per-operation flag deltas are discarded by design). Runs on
 * every row, including the rows that expect a typed failure.
 */
internal inline fun withPresetFlags(row: GoldenRow, body: () -> Unit) {
    for (flag in MpfrExceptionFlag.entries) MpfrExceptionFlags.set(flag)
    try {
        body()
    } finally {
        assertEquals(
            6, MpfrExceptionFlags.current().size,
            "${row.rowId}: preset exception flags were not preserved (per-operation isolation broken)",
        )
        MpfrExceptionFlags.clearAll()
    }
}

internal inline fun GoldenFile.rowsWhere(
    category: String? = null,
    op: String? = null,
    block: (GoldenRow) -> Unit,
) {
    for (row in rows) {
        if (category != null && row["category"] != category) continue
        if (op != null && row["op"] != op) continue
        withPresetFlags(row) { block(row) }
    }
}