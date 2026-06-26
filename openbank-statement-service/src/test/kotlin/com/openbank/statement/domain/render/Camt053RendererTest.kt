// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.domain.render

import com.openbank.statement.Fixtures
import com.openbank.statement.domain.model.BalanceAnchor
import com.openbank.statement.domain.model.CreditDebit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class Camt053RendererTest {

    @Test
    fun `renders a camt-053-001-08 statement with sequences, account and OPBD-CLBD balances`() {
        val xml = Camt053Renderer.render(Fixtures.model())

        assertThat(xml).startsWith("""<?xml version="1.0" encoding="UTF-8"?>""")
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:camt.053.001.08")
        assertThat(xml).contains("<LglSeqNb>7</LglSeqNb>")
        assertThat(xml).contains("<ElctrncSeqNb>7</ElctrncSeqNb>")
        assertThat(xml).contains("<IBAN>CZ6508000000192000145399</IBAN>")
        assertThat(xml).contains("<Ccy>CZK</Ccy>")
        assertThat(xml).contains("<Cd>OPBD</Cd>")
        assertThat(xml).contains("<Cd>CLBD</Cd>")
    }

    @Test
    fun `each entry carries amount, credit-debit indicator, booking and value dates`() {
        val xml = Camt053Renderer.render(Fixtures.model())

        assertThat(xml).contains("<Amt Ccy=\"CZK\">100.00</Amt>")
        assertThat(xml).contains("<CdtDbtInd>CRDT</CdtDbtInd>")
        assertThat(xml).contains("<CdtDbtInd>DBIT</CdtDbtInd>")
        assertThat(xml).contains("<BookgDt><Dt>2026-01-15</Dt></BookgDt>")
        assertThat(xml).contains("<ValDt><Dt>2026-01-16</Dt></ValDt>")
        assertThat(xml).contains("<AcctSvcrRef>TX-1</AcctSvcrRef>")
    }

    @Test
    fun `a negative closing balance is rendered as a DBIT balance with a positive amount`() {
        val model = Fixtures.model().copy(
            closingBalance = BalanceAnchor(BigDecimal("-50.00"), "CZK", LocalDate.parse("2026-01-31")),
        )
        val xml = Camt053Renderer.render(model)

        // The CLBD block must show DBIT with the absolute amount.
        val clbd = xml.substringAfter("<Cd>CLBD</Cd>")
        assertThat(clbd).contains("<Amt Ccy=\"CZK\">50.00</Amt>")
        assertThat(clbd).contains("<CdtDbtInd>DBIT</CdtDbtInd>")
    }

    @Test
    fun `rendering is deterministic - timestamps come from the model, not the clock`() {
        val model = Fixtures.model()
        assertThat(Camt053Renderer.render(model)).isEqualTo(Camt053Renderer.render(model))
        // CreDtTm is derived from closedAt (2026-02-01T02:30:00Z), never System time.
        assertThat(Camt053Renderer.render(model)).contains("<CreDtTm>2026-02-01T02:30:00Z</CreDtTm>")
    }

    @Test
    fun `special characters in free text are XML-escaped`() {
        val model = Fixtures.model(
            entries = listOf(Fixtures.entry(description = "A & B <test>", cd = CreditDebit.CRDT)),
        )
        val xml = Camt053Renderer.render(model)
        assertThat(xml).contains("A &amp; B &lt;test&gt;")
        assertThat(xml).doesNotContain("A & B <test>")
    }
}
