// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.cnb

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class CnbFixingParserTest {

    private val sample = """
        30.05.2026 #104
        země|měna|množství|kód|kurz
        Austrálie|dolar|1|AUD|15,123
        EMU|euro|1|EUR|25,145
        Japonsko|jen|100|JPY|14,621
        USA|dolar|1|USD|22,310
        Velká Británie|libra|1|GBP|29,840
    """.trimIndent()

    @Nested
    inner class Header {
        @Test
        fun `parses the fixing date and sequence`() {
            val fixing = CnbFixingParser.parse(sample)
            assertThat(fixing.date).isEqualTo(LocalDate.of(2026, 5, 30))
            assertThat(fixing.sequence).isEqualTo(104)
        }

        @Test
        fun `tolerates a missing sequence`() {
            val fixing = CnbFixingParser.parse("30.05.2026\nEMU|euro|1|EUR|25,145")
            assertThat(fixing.date).isEqualTo(LocalDate.of(2026, 5, 30))
            assertThat(fixing.sequence).isNull()
        }

        @Test
        fun `rejects an unparseable date header`() {
            assertThatThrownBy { CnbFixingParser.parse("not-a-date #1\nEMU|euro|1|EUR|25,145") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class Rates {
        @Test
        fun `parses all currencies with comma decimals`() {
            val fixing = CnbFixingParser.parse(sample)
            assertThat(fixing.rates).hasSize(5)
            assertThat(fixing.rateFor("EUR")).isEqualByComparingTo("25.145")
            assertThat(fixing.rateFor("USD")).isEqualByComparingTo("22.310")
            assertThat(fixing.rateFor("GBP")).isEqualByComparingTo("29.840")
        }

        @Test
        fun `normalises a per-100 quote to per-unit`() {
            val fixing = CnbFixingParser.parse(sample)
            // JPY quoted as 14,621 per 100 → 0.14621 per unit
            assertThat(fixing.rateFor("JPY")).isEqualByComparingTo("0.14621")
        }

        @Test
        fun `skips the header line and blank lines`() {
            val withBlanks = "\n30.05.2026 #104\n\nzemě|měna|množství|kód|kurz\n\nEMU|euro|1|EUR|25,145\n\n"
            val fixing = CnbFixingParser.parse(withBlanks)
            assertThat(fixing.rates).hasSize(1)
            assertThat(fixing.rateFor("EUR")).isEqualByComparingTo("25.145")
        }

        @Test
        fun `returns null rate for an unquoted currency`() {
            assertThat(CnbFixingParser.parse(sample).rateFor("CHF")).isNull()
        }

        @Test
        fun `rejects a feed with no rate lines`() {
            assertThatThrownBy { CnbFixingParser.parse("30.05.2026 #104\nzemě|měna|množství|kód|kurz") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("no rate lines")
        }
    }

    @Nested
    inner class RateNormalisation {
        @Test
        fun `ratePerUnit divides by the quote amount at 8dp`() {
            val r = CnbFixingRate(code = "HUF", amount = 100, rate = BigDecimal("6,389".replace(',', '.')))
            assertThat(r.ratePerUnit).isEqualByComparingTo("0.06389")
        }

        @Test
        fun `rejects non-positive amount or rate`() {
            assertThatThrownBy { CnbFixingRate("EUR", 0, BigDecimal("25.1")) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { CnbFixingRate("EUR", 1, BigDecimal("-1")) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
