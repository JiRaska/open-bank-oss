// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
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
}
