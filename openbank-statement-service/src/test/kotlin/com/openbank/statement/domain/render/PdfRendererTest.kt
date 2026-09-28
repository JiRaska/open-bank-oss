// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.domain.render

import com.openbank.statement.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PdfRendererTest {

    @Test
    fun `single-pocket document shows holder, IBAN, period, sequence and balances`() {
        val pdf = PdfRenderer.render(Fixtures.model())

        assertThat(pdf).contains("ACCOUNT STATEMENT — CZK")
        assertThat(pdf).contains("Holder: Jan Novak")
        assertThat(pdf).contains("IBAN: CZ6508000000192000145399")
        assertThat(pdf).contains("Period: 2026-01-01 .. 2026-01-31")
        assertThat(pdf).contains("Statement no. (legal): 7")
        assertThat(pdf).contains("Opening balance: 1000.00 CZK")
        assertThat(pdf).contains("Closing balance: 1075.00 CZK")
        assertThat(pdf).contains("+100.00 CZK")
        assertThat(pdf).contains("-25.00 CZK")
    }

    @Test
    fun `consolidated envelope stacks pockets and labels the reference total non-accounting`() {
        val czk = Fixtures.model(currency = "CZK")
        val eur = Fixtures.model(currency = "EUR", opening = "200.00", closing = "275.00")

        val pdf = PdfRenderer.renderConsolidated(
            holderName = "Jan Novak",
            iban = "CZ6508000000192000145399",
            pockets = listOf(czk, eur),
            referenceCurrency = "CZK",
            referenceTotal = BigDecimal("7800.00"),
        )

        assertThat(pdf).contains("CONSOLIDATED ACCOUNT STATEMENT")
        assertThat(pdf).contains("POCKET 1 — CZK")
        assertThat(pdf).contains("POCKET 2 — EUR")
        assertThat(pdf).contains("INFORMATIONAL TOTAL (NOT AN ACCOUNTING FIGURE)")
        assertThat(pdf).contains("Pockets are not netted")
        assertThat(pdf).contains("Grand total (CZK): 7800.00")
    }

    @Test
    fun `rendering is deterministic`() {
        val model = Fixtures.model()
        assertThat(PdfRenderer.render(model)).isEqualTo(PdfRenderer.render(model))
    }

    @Test
    fun `JPY pocket renders at zero decimals, not a fixed scale 2`() {
        val model = Fixtures.model(
            currency = "JPY",
            opening = "1000",
            closing = "1500",
            entries = listOf(
                Fixtures.entry(ref = "TX-1", amount = "500", currency = "JPY"),
            ),
        )

        val pdf = PdfRenderer.render(model)

        assertThat(pdf).contains("Opening balance: 1000 JPY")
        assertThat(pdf).contains("Closing balance: 1500 JPY")
        assertThat(pdf).contains("+500 JPY")
        assertThat(pdf).doesNotContain("1000.00 JPY")
        assertThat(pdf).doesNotContain("500.00 JPY")
    }

    @Test
    fun `KWD pocket renders at three decimals, not a fixed scale 2`() {
        val model = Fixtures.model(
            currency = "KWD",
            opening = "1000.500",
            closing = "1250.750",
            entries = listOf(
                Fixtures.entry(ref = "TX-1", amount = "250.250", currency = "KWD"),
            ),
        )

        val pdf = PdfRenderer.render(model)

        assertThat(pdf).contains("Opening balance: 1000.500 KWD")
        assertThat(pdf).contains("Closing balance: 1250.750 KWD")
        assertThat(pdf).contains("+250.250 KWD")
        assertThat(pdf).doesNotContain("1000.50 KWD")
    }

    @Test
    fun `consolidated reference total renders at the reference currency's own scale`() {
        val czk = Fixtures.model(currency = "CZK")
        val jpy = Fixtures.model(currency = "JPY", opening = "1000", closing = "1500")

        val pdf = PdfRenderer.renderConsolidated(
            holderName = "Jan Novak",
            iban = "CZ6508000000192000145399",
            pockets = listOf(czk, jpy),
            referenceCurrency = "JPY",
            referenceTotal = BigDecimal("184000"),
        )

        assertThat(pdf).contains("Grand total (JPY): 184000")
        assertThat(pdf).doesNotContain("Grand total (JPY): 184000.00")
    }
}
