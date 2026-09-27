// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.domain.render

import com.openbank.statement.Fixtures
import com.openbank.statement.domain.model.BalanceAnchor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class Mt940RendererTest {

    @Test
    fun `renders the mandatory MT940 tag blocks`() {
        val mt = Mt940Renderer.render(Fixtures.model())

        assertThat(mt).contains(":20:")
        assertThat(mt).contains(":25:CZ6508000000192000145399")
        assertThat(mt).contains(":28C:7/1")
        assertThat(mt).contains(":60F:")
        assertThat(mt).contains(":62F:")
        assertThat(mt).endsWith("\r\n-")
    }

    @Test
    fun `opening balance uses a C mark, YYMMDD date, currency and comma decimal amount`() {
        val mt = Mt940Renderer.render(Fixtures.model())
        // opening 1000.00 on 2026-01-01 -> C 260101 CZK 1000,00
        assertThat(mt).contains(":60F:C260101CZK1000,00")
        // closing 1075.00 on 2026-01-31
        assertThat(mt).contains(":62F:C260131CZK1075,00")
    }

    @Test
    fun `a negative balance uses a D mark with the absolute amount`() {
        val model = Fixtures.model().copy(
            openingBalance = BalanceAnchor(BigDecimal("-12.50"), "CZK", LocalDate.parse("2026-01-01")),
        )
        val mt = Mt940Renderer.render(model)
        assertThat(mt).contains(":60F:D260101CZK12,50")
    }

    @Test
    fun `statement lines carry value date, C-D mark, comma amount and a description tag`() {
        val mt = Mt940Renderer.render(Fixtures.model())
        // credit TX-1 100.00 value 2026-01-16 booking 0115
        assertThat(mt).contains(":61:2601160115C100,00NTRFTX-1")
        // debit TX-2 25.00
        assertThat(mt).contains(":61:2601160115D25,00NTRFTX-2")
        assertThat(mt).contains(":86:Salary")
    }

    @Test
    fun `rendering is deterministic`() {
        val model = Fixtures.model()
        assertThat(Mt940Renderer.render(model)).isEqualTo(Mt940Renderer.render(model))
    }

    @Test
    fun `JPY amounts render at zero decimals, not a fixed scale 2`() {
        val model = Fixtures.model(
            currency = "JPY",
            opening = "1000",
            closing = "1500",
            entries = listOf(Fixtures.entry(ref = "TX-1", amount = "500", currency = "JPY")),
        )

        val mt = Mt940Renderer.render(model)

        assertThat(mt).contains(":60F:C260101JPY1000")
        assertThat(mt).contains(":62F:C260131JPY1500")
        assertThat(mt).contains(":61:2601160115C500NTRFTX-1")
        assertThat(mt).doesNotContain("1000,00")
        assertThat(mt).doesNotContain("500,00")
    }

    @Test
    fun `KWD amounts render at three decimals with a comma separator, not a fixed scale 2`() {
        val model = Fixtures.model(
            currency = "KWD",
            opening = "1000.500",
            closing = "1250.750",
            entries = listOf(Fixtures.entry(ref = "TX-1", amount = "250.250", currency = "KWD")),
        )

        val mt = Mt940Renderer.render(model)

        assertThat(mt).contains(":60F:C260101KWD1000,500")
        assertThat(mt).contains(":62F:C260131KWD1250,750")
        assertThat(mt).contains(":61:2601160115C250,250NTRFTX-1")
    }
}
