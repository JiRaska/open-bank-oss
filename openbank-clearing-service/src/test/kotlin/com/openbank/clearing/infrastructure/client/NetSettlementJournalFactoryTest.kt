// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.client

import com.openbank.clearing.application.port.out.NetSettlementPosting
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0281: the net-settlement journal must be balanced, per-currency (ledger rejects a line
 * whose currency doesn't match its GL account), and idempotent on the deterministic batch key.
 */
class NetSettlementJournalFactoryTest {

    private fun posting(currency: String = "EUR", amount: String = "100.50") = NetSettlementPosting(
        batchId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        batchReference = "CYCLE-SEPA_SCT-20260904-1234",
        cycleId = "CYCLE-SEPA_SCT-20260904-1234",
        idempotencyKey = "clearing-net-settlement-11111111-1111-1111-1111-111111111111",
        currency = currency,
        settlementAmount = BigDecimal(amount),
        valueDate = LocalDate.parse("2026-09-04"),
    )

    @Test
    fun `the journal is a balanced DEBIT cash-clearing CREDIT scheme-settlement pair`() {
        val request = NetSettlementJournalFactory.build(posting())

        assertThat(request.lines).hasSize(2)
        val debit = request.lines.single { it.side == "DEBIT" }
        val credit = request.lines.single { it.side == "CREDIT" }
        assertThat(debit.amount).isEqualByComparingTo("100.50")
        assertThat(credit.amount).isEqualByComparingTo("100.50")
        assertThat(debit.currencyCode).isEqualTo("EUR")
        assertThat(credit.currencyCode).isEqualTo("EUR")
        // DEBIT clears the cash-clearing obligation; CREDIT drains the scheme-settlement asset.
        assertThat(debit.glAccountId).isEqualTo(NetSettlementJournalFactory.glPairFor("EUR").first)
        assertThat(credit.glAccountId).isEqualTo(NetSettlementJournalFactory.glPairFor("EUR").second)
    }

    @Test
    fun `the idempotency key and transaction identity come from the posting`() {
        val request = NetSettlementJournalFactory.build(posting())

        assertThat(request.idempotencyKey)
            .isEqualTo("clearing-net-settlement-11111111-1111-1111-1111-111111111111")
        assertThat(request.transactionId).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        assertThat(request.entryDate).isEqualTo("2026-09-04")
        assertThat(request.valueDate).isEqualTo("2026-09-04")
    }

    @Test
    fun `every seeded currency resolves a distinct GL pair`() {
        val pairs = listOf("CZK", "EUR", "USD", "GBP").map { NetSettlementJournalFactory.glPairFor(it) }
        assertThat(pairs).allSatisfy { pair ->
            assertThat(pair.first).isNotNull()
            assertThat(pair.second).isNotNull()
        }
        assertThat(pairs.map { it.first }).doesNotHaveDuplicates()
        assertThat(pairs.map { it.second }).doesNotHaveDuplicates()
    }

    @Test
    fun `an unseeded currency is refused rather than posted against a wrong account`() {
        assertThatThrownBy { NetSettlementJournalFactory.build(posting(currency = "CHF")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("CHF")
    }

    @Test
    fun `a zero or negative amount cannot exist as a posting`() {
        assertThatThrownBy { posting(amount = "0.00") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { posting(amount = "-10.00") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
