// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.cnb

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate

/**
 * Against the REAL ČNB files (downloaded 2026-10-04, committed verbatim: UTF-8 with BOM, LF, no
 * trailing newline) plus the malformed shapes that must fail the whole file.
 */
class CnbPolicyRateParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/cnb/policy-rates/$name")) { "missing fixture $name" }
            .readBytes()
            .toString(Charsets.UTF_8)

    @ParameterizedTest
    @CsvSource(
        "vyvoj_repo_historie.txt,      REPO_2W,  117, 0.0375",
        "vyvoj_diskontni_historie.txt, DISCOUNT,  77, 0.0275",
        "vyvoj_lombard_historie.txt,   LOMBARD,   85, 0.0475",
    )
    fun `parses every row of the real ČNB file, last one effective 2026-06-19`(
        file: String,
        instrument: CnbPolicyInstrument,
        rows: Int,
        lastRate: String,
    ) {
        val text = fixture(file)
        assertThat(text).startsWith("﻿").doesNotEndWith("\n")

        val parsed = CnbPolicyRateParser.parse(text, instrument)

        assertThat(parsed).hasSize(rows)
        assertThat(parsed.last().effectiveFrom).isEqualTo(LocalDate.of(2026, 6, 19))
        assertThat(parsed.last().rate).isEqualByComparingTo(lastRate)
        assertThat(parsed.map { it.effectiveFrom }).isSorted
    }

    @Test
    fun `the first repo row is the oldest published rate as a fraction`() {
        val first = CnbPolicyRateParser.parse(fixture("vyvoj_repo_historie.txt"), CnbPolicyInstrument.REPO_2W).first()
        assertThat(first.effectiveFrom).isEqualTo(LocalDate.of(1995, 12, 8))
        assertThat(first.rate).isEqualByComparingTo("0.113")
    }

    @Test
    fun `CRLF line endings, a trailing newline and blank lines change nothing`() {
        val lf = fixture("vyvoj_lombard_historie.txt")
        val crlf = lf.replace("\n", "\r\n") + "\r\n\r\n"
        val blanks = lf.replace("\n", "\n\n")
        val expected = CnbPolicyRateParser.parse(lf, CnbPolicyInstrument.LOMBARD)
        assertThat(CnbPolicyRateParser.parse(crlf, CnbPolicyInstrument.LOMBARD)).isEqualTo(expected)
        assertThat(CnbPolicyRateParser.parse(blanks, CnbPolicyInstrument.LOMBARD)).isEqualTo(expected)
    }

    @Test
    fun `a file without the BOM parses the same`() {
        val text = fixture("vyvoj_repo_historie.txt")
        assertThat(CnbPolicyRateParser.parse(text.removePrefix("﻿"), CnbPolicyInstrument.REPO_2W))
            .isEqualTo(CnbPolicyRateParser.parse(text, CnbPolicyInstrument.REPO_2W))
    }

    @Test
    fun `a malformed row fails the whole file and every bad row is counted`() {
        val text = "PLATNA_OD|CNB_REPO_SAZBA_V_%\n20250101|3,50\n2025-02-07|3,75\n20250509|3.50\n20260619|3,75"
        assertThatThrownBy { CnbPolicyRateParser.parse(text, CnbPolicyInstrument.REPO_2W) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("2 malformed row(s)")
            .hasMessageContaining("line 3")
            .hasMessageContaining("line 4")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = [
            "20250230|3,75", // no such day: strict calendar
            "20250101|", // missing rate
            "20250101|3,75|x", // extra column
            "20250101|-0,10", // negative
            "20250101|100,01", // more than 100 %
            "20250101 3,75", // wrong separator
        ],
    )
    fun `rejects a malformed data row`(row: String) {
        assertThatThrownBy {
            CnbPolicyRateParser.parse("PLATNA_OD|CNB_REPO_SAZBA_V_%\n$row", CnbPolicyInstrument.REPO_2W)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("malformed")
    }

    @Test
    fun `the wrong file's header is rejected — a discount file is never stored as repo`() {
        assertThatThrownBy {
            CnbPolicyRateParser.parse(fixture("vyvoj_diskontni_historie.txt"), CnbPolicyInstrument.REPO_2W)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("expected header")
    }

    @Test
    fun `an HTML error page is rejected at the header`() {
        assertThatThrownBy {
            CnbPolicyRateParser.parse("<!DOCTYPE html><html><head>404</head></html>", CnbPolicyInstrument.LOMBARD)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("expected header")
    }

    @Test
    fun `an empty file and a header-only file are rejected`() {
        assertThatThrownBy { CnbPolicyRateParser.parse("﻿\n", CnbPolicyInstrument.REPO_2W) }
            .hasMessageContaining("Empty")
        assertThatThrownBy { CnbPolicyRateParser.parse("PLATNA_OD|CNB_REPO_SAZBA_V_%", CnbPolicyInstrument.REPO_2W) }
            .hasMessageContaining("no rows")
    }

    @Test
    fun `a duplicated effective date is rejected rather than resolved by order`() {
        val text = "PLATNA_OD|CNB_REPO_SAZBA_V_%\n20250101|3,50\n20250101|3,75"
        assertThatThrownBy { CnbPolicyRateParser.parse(text, CnbPolicyInstrument.REPO_2W) }
            .hasMessageContaining("duplicated effective dates")
    }

    @Test
    fun `an instrument without a feed cannot be parsed`() {
        assertThatThrownBy { CnbPolicyRateParser.parse("x", CnbPolicyInstrument.MIN_RESERVE_RATIO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
