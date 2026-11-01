// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.marketdata

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class MarketDataFilesTest {
    private fun csv(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun `a csv with a header and quoted cells parses every row`() {
        val rows = MarketDataFiles.parseCsv(
            csv("Index,Date,Value\n\nPRIBOR_3M,2026-10-05,3.75\n \"ESTR\" , 2026-10-06 ,-0.5\n"),
        )
        assertThat(rows).containsExactly(
            Triple("PRIBOR_3M", LocalDate.of(2026, 10, 5), BigDecimal("3.75")),
            Triple("ESTR", LocalDate.of(2026, 10, 6), BigDecimal("-0.5")),
        )
    }

    @Test
    fun `a csv without a header keeps its first row`() {
        assertThat(MarketDataFiles.parseCsv(csv("ESTR,2026-10-05,1"))).hasSize(1)
    }

    @Test
    fun `an empty, header-only or malformed csv is refused whole`() {
        assertThatThrownBy { MarketDataFiles.parseCsv(ByteArray(0)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MarketDataFiles.parseCsv(csv("index,date,value\n")) }
            .hasMessageContaining("rows")
        assertThatThrownBy { MarketDataFiles.parseCsv(csv("ESTR,2026-10-05")) }
            .hasMessageContaining("row 1: expected index,date,value")
        assertThatThrownBy { MarketDataFiles.parseCsv(csv("ESTR,2026-10-05,1\nESTR,2026-10-06,1,9")) }
            .hasMessageContaining("row 2")
        assertThatThrownBy {
            MarketDataFiles.parseCsv(ByteArray(MarketDataFiles.MAX_BYTES + 1) { 'a'.code.toByte() })
        }.hasMessageContaining("bytes")
        val tooMany = (0..MarketDataFiles.MAX_ROWS).joinToString("\n") { "ESTR,2026-10-05,1" }
        assertThatThrownBy { MarketDataFiles.parseCsv(csv(tooMany)) }.hasMessageContaining("rows")
        val maxRows = (1..MarketDataFiles.MAX_ROWS).joinToString("\n") { "ESTR,2026-10-05,1" }
        assertThat(MarketDataFiles.parseCsv(csv(maxRows))).hasSize(MarketDataFiles.MAX_ROWS)
    }

    @Test
    fun `a row validates every field with its row number`() {
        assertThat(MarketDataFiles.row(1, "ESTR", "2026-10-05", "100")).isEqualTo(
            Triple("ESTR", LocalDate.of(2026, 10, 5), BigDecimal("100")),
        )
        assertThatThrownBy { MarketDataFiles.row(3, null, "2026-10-05", "1") }.hasMessage("row 3: index is required")
        assertThatThrownBy { MarketDataFiles.row(3, "A".repeat(65), "2026-10-05", "1") }
            .hasMessage("row 3: index is required")
        assertThatThrownBy { MarketDataFiles.row(3, "estr", "2026-10-05", "1") }
            .hasMessageContaining("is not an index code")
        assertThatThrownBy { MarketDataFiles.row(3, "ESTR", null, "1") }.hasMessage("row 3: date is required")
        assertThatThrownBy { MarketDataFiles.row(3, "ESTR", "05.10.2026", "1") }
            .hasMessage("row 3: date must be ISO yyyy-MM-dd")
        assertThatThrownBy { MarketDataFiles.row(3, "ESTR", "2026-10-05", " ") }.hasMessage("row 3: value is required")
        assertThatThrownBy { MarketDataFiles.row(3, "ESTR", "2026-10-05", "1".repeat(65)) }
            .hasMessage("row 3: value is required")
        assertThatThrownBy { MarketDataFiles.row(3, "ESTR", "2026-10-05", "abc") }
            .hasMessage("row 3: value is not a number")
        assertThatThrownBy { MarketDataFiles.row(3, "ESTR", "2026-10-05", "-100.5") }
            .hasMessageContaining("within ±100")
        assertThatThrownBy { MarketDataFiles.row(3, "ESTR", "2026-10-05", "1.123456789") }
            .hasMessageContaining("within ±100")
        assertThat(MarketDataFiles.row(3, "ESTR", "2026-10-05", "1.12345678").third.scale()).isEqualTo(8)
    }

    @Test
    fun `sha256 is lower-case hex of the bytes`() {
        assertThat(MarketDataFiles.sha256(csv("abc")))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `the export quotes every cell and neutralises formula leads but not negative numbers`() {
        val o = IndexObservation(
            "ESTR",
            LocalDate.of(2026, 10, 5),
            BigDecimal("-0.25"),
            ObservationSource.MANUAL,
            UUID.randomUUID(),
            ObservationStatus.ACTIVE,
        )
        assertThat(MarketDataFiles.toCsv(listOf(o))).isEqualTo(
            "index,date,value,source,sha256\n\"ESTR\",\"2026-10-05\",\"-0.25\",\"MANUAL\",\"${o.sha256}\"\n",
        )
        assertThat(MarketDataFiles.cell("=1+1")).isEqualTo("\"'=1+1\"")
        assertThat(MarketDataFiles.cell("+x")).isEqualTo("\"'+x\"")
        assertThat(MarketDataFiles.cell("-x")).isEqualTo("\"'-x\"")
        assertThat(MarketDataFiles.cell("@x")).isEqualTo("\"'@x\"")
        assertThat(MarketDataFiles.cell("\tx")).isEqualTo("\"'\tx\"")
        assertThat(MarketDataFiles.cell("-3")).isEqualTo("\"-3\"")
        assertThat(MarketDataFiles.cell("-3.5")).isEqualTo("\"-3.5\"")
        assertThat(MarketDataFiles.cell("a\"b")).isEqualTo("\"a\"\"b\"")
        assertThat(MarketDataFiles.cell("")).isEqualTo("\"\"")
        assertThat(MarketDataFiles.toCsv(emptyList())).isEqualTo("index,date,value,source,sha256\n")
    }
}
