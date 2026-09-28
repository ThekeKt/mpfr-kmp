package io.github.thekekt.mpfr.golden

import io.github.thekekt.mpfr.MpfrFloat
import io.github.thekekt.mpfr.MpfrRangeException
import io.github.thekekt.mpfr.of
import io.github.thekekt.mpfr.runNative
import io.github.thekekt.mpfr.toBigInteger
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reference-vector tier for the conversion surface: exact constructors from
 * Long/ULong/Double/Float/BigInteger, saturating `get_d`/`get_flt` narrowing
 * (raw IEEE bits compared), the fits-gated integer narrowings with their typed
 * range failures, and the BigInteger crossing.
 */
class GoldenConversionsTest {

    private inline fun rows(block: (GoldenRow) -> Unit) =
        GoldenVectors.file("conversions.tsv").rowsWhere("CONV") { block(it) }

    @Test fun ofLong() = runNative {
        rows { row ->
            if (row["op"] != "OF_LONG") return@rows
            val prec = precOf(row["prec"], row)
            val actual = MpfrFloat.of(row["input"].toLong(), prec, rndOf(row["rnd"], row))
            assertSameValue(row["value"], prec, actual, row)
        }
    }

    @Test fun ofULong() = runNative {
        rows { row ->
            if (row["op"] != "OF_ULONG") return@rows
            val prec = precOf(row["prec"], row)
            val actual = MpfrFloat.of(row["input"].toULong(), prec, rndOf(row["rnd"], row))
            assertSameValue(row["value"], prec, actual, row)
        }
    }

    @Test fun ofDouble() = runNative {
        rows { row ->
            if (row["op"] != "OF_DOUBLE") return@rows
            val prec = precOf(row["prec"], row)
            val d = Double.fromBits(row["input"].toULong(16).toLong())
            val actual = MpfrFloat.of(d, prec, rndOf(row["rnd"], row))
            if (d.isNaN()) {
                assertTrue(actual.isNaN, "${row.rowId}: NaN round-trip")
            } else {
                assertSameValue(row["value"], prec, actual, row)
            }
        }
    }

    @Test fun ofFloat() = runNative {
        rows { row ->
            if (row["op"] != "OF_FLOAT") return@rows
            val prec = precOf(row["prec"], row)
            val f = Float.fromBits(row["input"].toUInt(16).toInt())
            val actual = MpfrFloat.of(f, prec)
            if (f.isNaN()) {
                assertTrue(actual.isNaN, "${row.rowId}: NaN round-trip")
            } else {
                assertSameValue(row["value"], prec, actual, row)
            }
        }
    }

    @Test fun ofBigInteger() = runNative {
        rows { row ->
            if (row["op"] != "OF_BIGINT") return@rows
            val prec = precOf(row["prec"], row)
            val actual = MpfrFloat.of(BigInteger(row["input"]), prec, rndOf(row["rnd"], row))
            assertSameValue(row["value"], prec, actual, row)
        }
    }

    @Test fun toDouble() = runNative {
        rows { row ->
            if (row["op"] != "TO_DOUBLE") return@rows
            val x = tokenToFloat(row["input"], precOf(row["prec"], row), row)
            val r = rndOf(row["rnd"], row)
            val expected = Double.fromBits(row["value"].toULong(16).toLong())
            val actual = x.toDouble(r)
            if (expected.isNaN()) {
                assertTrue(actual.isNaN(), "${row.rowId}: NaN narrowing")
            } else {
                assertEquals(expected.toRawBits(), actual.toRawBits(), "${row.rowId}: raw IEEE bits")
            }
        }
    }

    @Test fun toFloat() = runNative {
        rows { row ->
            if (row["op"] != "TO_FLOAT") return@rows
            val x = tokenToFloat(row["input"], precOf(row["prec"], row), row)
            val r = rndOf(row["rnd"], row)
            val expected = Float.fromBits(row["value"].toUInt(16).toInt())
            val actual = x.toFloat(r)
            if (expected.isNaN()) {
                assertTrue(actual.isNaN(), "${row.rowId}: NaN narrowing")
            } else {
                assertEquals(expected.toRawBits(), actual.toRawBits(), "${row.rowId}: raw IEEE bits")
            }
        }
    }

    @Test fun toLong() = runNative {
        rows { row ->
            if (row["op"] != "TO_LONG") return@rows
            val x = tokenToFloat(row["input"], precOf(row["prec"], row), row)
            val r = rndOf(row["rnd"], row)
            if (row["kind"] == "RANGE_EX") {
                assertFailsWith<MpfrRangeException>("${row.rowId}: expected range failure") { x.toLong(r) }
            } else {
                assertEquals(row["value"].toLong(), x.toLong(r), "${row.rowId}: narrowing")
            }
        }
    }

    @Test fun toInt() = runNative {
        rows { row ->
            if (row["op"] != "TO_INT") return@rows
            val x = tokenToFloat(row["input"], precOf(row["prec"], row), row)
            val r = rndOf(row["rnd"], row)
            if (row["kind"] == "RANGE_EX") {
                assertFailsWith<MpfrRangeException>("${row.rowId}: expected range failure") { x.toInt(r) }
            } else {
                assertEquals(row["value"].toInt(), x.toInt(r), "${row.rowId}: narrowing")
            }
        }
    }

    @Test fun fitsLongAndInt() = runNative {
        rows { row ->
            when (row["op"]) {
                "FITS_LONG" -> {
                    val x = tokenToFloat(row["input"], precOf(row["prec"], row), row)
                    assertEquals(
                        row["value"] == "1", x.fitsLong(rndOf(row["rnd"], row)),
                        "${row.rowId}: fitsLong",
                    )
                }
                "FITS_INT" -> {
                    val x = tokenToFloat(row["input"], precOf(row["prec"], row), row)
                    assertEquals(
                        row["value"] == "1", x.fitsInt(rndOf(row["rnd"], row)),
                        "${row.rowId}: fitsInt",
                    )
                }
                else -> return@rows
            }
        }
    }

    @Test fun toBigInteger() = runNative {
        rows { row ->
            if (row["op"] != "TO_BIGINT") return@rows
            val x = tokenToFloat(row["input"], precOf(row["prec"], row), row)
            val r = rndOf(row["rnd"], row)
            if (row["kind"] == "IAE") {
                assertFailsWith<IllegalArgumentException>("${row.rowId}: expected illegal-argument failure") {
                    x.toBigInteger(r)
                }
            } else {
                assertEquals(row["value"], x.toBigInteger(r).toString(), "${row.rowId}: BigInteger")
            }
        }
    }
}