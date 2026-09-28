package io.github.thekekt.mpfr.golden

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.core.MpfrParseError
import io.github.thekekt.mpfr.core.NumberStyle
import io.github.thekekt.mpfr.core.Result
import io.github.thekekt.mpfr.internal.MpfrBridge
import io.github.thekekt.mpfr.mpfrValueU
import io.github.thekekt.mpfr.runNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Reference-vector tier for the string surface: `parse` statuses, raw
 * `get_str` digits/exponent pairs over bases 2..62, `%R` formatting, and the
 * canonical `toString` rendering.
 */
class GoldenStringsTest {

    @Test fun parseRows() = runNative {
        GoldenVectors.file("strings.tsv").rowsWhere("STR", op = "PARSE") { row ->
            val prec = precOf(row["prec"], row)
            val result = MpfrFloat.parse(row["input"], prec, rndOf(row["rnd"], row), row["base"].toInt())
            when (row["kind"]) {
                "REG" -> {
                    val parsed = assertIs<Result.Success<MpfrFloat>>(
                        result, "${row.rowId}: expected successful parse of '${row["input"]}'",
                    )
                    assertSameValue(row["value"], prec, parsed.value, row)
                }
                "PARSE_MALFORMED" -> assertIs<Result.Failure<MpfrParseError>>(result, row.rowId).also {
                    assertEquals(MpfrParseError.MalformedInput, it.error, row.rowId)
                }
                "PARSE_BASE" -> assertIs<Result.Failure<MpfrParseError>>(result, row.rowId).also {
                    assertEquals(MpfrParseError.UnsupportedBase, it.error, row.rowId)
                }
                "PARSE_EXP" -> assertIs<Result.Failure<MpfrParseError>>(result, row.rowId).also {
                    assertEquals(MpfrParseError.ExponentSyntax, it.error, row.rowId)
                }
                else -> error("${row.rowId}: unexpected parse kind '${row["kind"]}'")
            }
        }
    }

    @Test fun getStrRaw() = runNative {
        GoldenVectors.file("strings.tsv").rowsWhere("STR", op = "GET_STR") { row ->
            val prec = precOf(row["prec"], row)
            val x = tokenToFloat(row["input"], prec, row)
            val header = LongArray(2)
            val digits = MpfrBridge.getStr(
                x.repr, row["base"].toInt(), row["digits"].toInt(),
                rndOf(row["rnd"], row).mpfrValueU(), header,
            )
            assertEquals(row["value2"].toLong(), header[1], "${row.rowId}: get_str exponent")
            val expectedSigned = row["value"]
            val expectedSign = if (expectedSigned.startsWith("-")) -1L else 1L
            assertEquals(expectedSign, header[0], "${row.rowId}: get_str sign")
            val expectedDigits = expectedSigned.removePrefix("-")
            assertEquals(expectedDigits, String(digits, Charsets.US_ASCII), "${row.rowId}: get_str digits")
        }
    }

    @Test fun formatRows() = runNative {
        GoldenVectors.file("strings.tsv").rowsWhere("STR", op = "FORMAT") { row ->
            val prec = precOf(row["prec"], row)
            val x = tokenToFloat(row["input"], prec, row)
            val style = when (row["style"]) {
                "e" -> NumberStyle.SCIENTIFIC
                "f" -> NumberStyle.FIXED
                "g" -> NumberStyle.GENERAL
                else -> error("${row.rowId}: unknown format style '${row["style"]}'")
            }
            assertEquals(
                row["value"], x.format(style, row["digits"].toInt(), rndOf(row["rnd"], row)),
                "${row.rowId}: format output",
            )
        }
    }

    @Test fun toStringRows() = runNative {
        GoldenVectors.file("strings.tsv").rowsWhere("STR", op = "TO_STRING") { row ->
            val prec = precOf(row["prec"], row)
            val x = tokenToFloat(row["input"], prec, row)
            assertEquals(
                row["value"], x.toString(row["digits"].toInt(), rndOf(row["rnd"], row)),
                "${row.rowId}: toString output",
            )
        }
    }
}