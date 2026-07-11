// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.usecase

import com.openbank.libs.domain.money.Money
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.infrastructure.client.JournalLineRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class PaymentJournalFactoryTest {

    private val cashClearingCzk = UUID.fromString("a0000000-0000-0000-0000-000000000001")
    private val cashClearingEur = UUID.fromString("a0000000-0000-0000-0000-000000001101")
    private val depositControlCzk = UUID.fromString("a0000000-0000-0000-0000-000000000002")
    private val depositControlEur = UUID.fromString("a0000000-0000-0000-0000-000000002101")
    private val fxPositionCzk = UUID.fromString("a0000000-0000-0000-0000-000000001990")
    private val fxPositionEur = UUID.fromString("a0000000-0000-0000-0000-000000001991")

    // --- Direction (ADR-0039): the same-currency journal must branch on payment direction so the
    // deposit-control leg (and thus the booked-balance projection delta, which is credit-positive)
    // moves the customer the right way. ---

    @Test
    fun `outbound payment debits the payer deposit-control and credits cash-clearing`() {
        // Source-only DEBIT payment (customer pays out). The payer's deposit-control leg MUST be a
        // DEBIT: deposit-control is a credit-normal liability, so a DEBIT decreases the customer's
        // booked balance (projection delta = -amount). Crediting it here (the old shape) would pay
        // the payer instead of charging them.
        val source = UUID.randomUUID()
        val tx = transaction(
            amount = Money.of("1250.50", "CZK"),
            baseAmount = Money.of("1250.50", "CZK"),
            sourceAccountId = source,
            targetAccountId = null,
        )

        val lines = PaymentJournalFactory.buildLines(tx)

        assertThat(lines).hasSize(2)
        val deposit = lines.single { it.glAccountId == depositControlCzk }
        val cash = lines.single { it.glAccountId == cashClearingCzk }
        assertThat(deposit.side).isEqualTo("DEBIT")
        assertThat(deposit.subAccountId).isEqualTo(source)
        assertThat(cash.side).isEqualTo("CREDIT")
        assertThat(cash.subAccountId).isNull()
        assertPerCurrencyBalanced(lines)
    }

    @Test
    fun `incoming credit debits cash-clearing and credits the beneficiary deposit-control`() {
        // Target-only CREDIT payment (customer receives). The beneficiary's deposit-control leg is a
        // CREDIT (projection delta = +amount). This is the original/default shape.
        val target = UUID.randomUUID()
        val tx = transaction(
            amount = Money.of("500.00", "CZK"),
            baseAmount = Money.of("500.00", "CZK"),
            sourceAccountId = null,
            targetAccountId = target,
        )

        val lines = PaymentJournalFactory.buildLines(tx)

        assertThat(lines).hasSize(2)
        val deposit = lines.single { it.glAccountId == depositControlCzk }
        val cash = lines.single { it.glAccountId == cashClearingCzk }
        assertThat(deposit.side).isEqualTo("CREDIT")
        assertThat(deposit.subAccountId).isEqualTo(target)
        assertThat(cash.side).isEqualTo("DEBIT")
        assertThat(cash.subAccountId).isNull()
        assertPerCurrencyBalanced(lines)
    }

    @Test
    fun `internal transfer moves the sub-ledger from source to target on two deposit-control legs`() {
        // Source + target same currency: money moves between two customer pockets. Both legs are
        // deposit-control (no bank cash-clearing leg); the control account nets to zero while the
        // sub-ledger moves amount from source (DEBIT, -amount) to target (CREDIT, +amount).
        val source = UUID.randomUUID()
        val target = UUID.randomUUID()
        val tx = transaction(
            amount = Money.of("300.00", "CZK"),
            baseAmount = Money.of("300.00", "CZK"),
            sourceAccountId = source,
            targetAccountId = target,
        )

        val lines = PaymentJournalFactory.buildLines(tx)

        assertThat(lines).hasSize(2)
        assertThat(lines).allSatisfy { assertThat(it.glAccountId).isEqualTo(depositControlCzk) }
        val debit = lines.single { it.side == "DEBIT" }
        val credit = lines.single { it.side == "CREDIT" }
        assertThat(debit.subAccountId).isEqualTo(source)
        assertThat(credit.subAccountId).isEqualTo(target)
        assertThat(lines).noneSatisfy { assertThat(it.glAccountId).isEqualTo(cashClearingCzk) }
        assertPerCurrencyBalanced(lines)
    }

    @Test
    fun `a payment with neither a source nor a target account is rejected`() {
        val tx = transaction(
            amount = Money.of("10.00", "CZK"),
            baseAmount = Money.of("10.00", "CZK"),
            sourceAccountId = null,
            targetAccountId = null,
        )

        assertThatThrownBy { PaymentJournalFactory.buildLines(tx) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    // --- Non-CZK same-currency cash-clearing leg (issue #747): the cash-clearing account used to
    // be hardcoded to the CZK-only leaf regardless of the payment's currency, which LedgerService
    // rejects (422, currency mismatch between the line and its GL account) for anything non-CZK. ---

    @Test
    fun `outbound payment in EUR credits EUR cash-clearing, not the CZK-only account`() {
        val source = UUID.randomUUID()
        val tx = transaction(
            amount = Money.of("40.00", "EUR"),
            baseAmount = Money.of("40.00", "EUR"),
            sourceAccountId = source,
            targetAccountId = null,
        )

        val lines = PaymentJournalFactory.buildLines(tx)

        assertThat(lines).hasSize(2)
        val cash = lines.single { it.side == "CREDIT" }
        assertThat(cash.glAccountId).isEqualTo(cashClearingEur)
        assertThat(cash.currencyCode).isEqualTo("EUR")
        assertPerCurrencyBalanced(lines)
    }

    @Test
    fun `incoming credit in EUR debits EUR cash-clearing, not the CZK-only account`() {
        val target = UUID.randomUUID()
        val tx = transaction(
            amount = Money.of("40.00", "EUR"),
            baseAmount = Money.of("40.00", "EUR"),
            sourceAccountId = null,
            targetAccountId = target,
        )

        val lines = PaymentJournalFactory.buildLines(tx)

        assertThat(lines).hasSize(2)
        val cash = lines.single { it.side == "DEBIT" }
        assertThat(cash.glAccountId).isEqualTo(cashClearingEur)
        assertThat(cash.currencyCode).isEqualTo("EUR")
        assertPerCurrencyBalanced(lines)
    }

    // --- Cross-currency (ADR-0025): four-legged FX entry, unchanged by the direction fix. ---

    @Test
    fun `cross-currency payment produces a balanced four-legged FX entry`() {
        // Customer's account (sell) is CZK; payment leaves in EUR (buy). 25 CZK / EUR.
        val tx = transaction(
            amount = Money.of("40.00", "EUR"),
            baseAmount = Money.of("1000.00", "CZK"),
            fxRate = BigDecimal("25.00"),
            sourceAccountId = UUID.randomUUID(),
            targetAccountId = null,
        )

        val lines = PaymentJournalFactory.buildLines(tx)

        assertThat(lines).hasSize(4)
        // Sell leg (CZK): DEBIT deposit-control, CREDIT FX position.
        lines[0].matchesLine("DEBIT", "1000.00", "CZK", depositControlCzk)
        lines[1].matchesLine("CREDIT", "1000.00", "CZK", fxPositionCzk)
        // Buy leg (EUR): DEBIT FX position, CREDIT deposit-control.
        lines[2].matchesLine("DEBIT", "40.00", "EUR", fxPositionEur)
        lines[3].matchesLine("CREDIT", "40.00", "EUR", depositControlEur)
        assertThat(lines).allSatisfy { assertThat(it.fxRate).isEqualByComparingTo("25.00") }
        assertThat(lines).allSatisfy { assertThat(it.baseCurrencyCode).isEqualTo(it.currencyCode) }
        assertPerCurrencyBalanced(lines)
    }

    @Test
    fun `cross-currency payment stamps the sub-account on both deposit-control legs only`() {
        val source = UUID.randomUUID()
        val tx = transaction(
            amount = Money.of("40.00", "EUR"),
            baseAmount = Money.of("1000.00", "CZK"),
            fxRate = BigDecimal("25.00"),
            sourceAccountId = source,
            targetAccountId = null,
        )

        val lines = PaymentJournalFactory.buildLines(tx)

        val depositLegs = lines.filter {
            it.glAccountId == depositControlCzk || it.glAccountId == depositControlEur
        }
        val fxLegs = lines.filter {
            it.glAccountId == fxPositionCzk || it.glAccountId == fxPositionEur
        }
        assertThat(depositLegs).hasSize(2)
        assertThat(depositLegs).allSatisfy { assertThat(it.subAccountId).isEqualTo(source) }
        assertThat(fxLegs).hasSize(2)
        assertThat(fxLegs).allSatisfy { assertThat(it.subAccountId).isNull() }
    }

    @Test
    fun `cross-currency payment in an unseeded currency is rejected`() {
        val tx = transaction(
            amount = Money.of("100.00", "PLN"),
            baseAmount = Money.of("600.00", "CZK"),
            fxRate = BigDecimal("6.00"),
            sourceAccountId = UUID.randomUUID(),
            targetAccountId = null,
        )

        assertThatThrownBy { PaymentJournalFactory.buildLines(tx) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("PLN")
    }

    private fun assertPerCurrencyBalanced(lines: List<JournalLineRequest>) {
        lines.groupBy { it.currencyCode }.forEach { (_, group) ->
            val debits = group.filter { it.side == "DEBIT" }.sumOf { it.amount }
            val credits = group.filter { it.side == "CREDIT" }.sumOf { it.amount }
            assertThat(debits).isEqualByComparingTo(credits)
        }
    }

    private fun JournalLineRequest.matchesLine(side: String, amount: String, currency: String, gl: UUID) {
        assertThat(this.side).isEqualTo(side)
        assertThat(this.amount).isEqualByComparingTo(amount)
        assertThat(this.currencyCode).isEqualTo(currency)
        assertThat(this.glAccountId).isEqualTo(gl)
    }

    private fun transaction(
        amount: Money,
        baseAmount: Money,
        fxRate: BigDecimal? = null,
        sourceAccountId: UUID? = UUID.randomUUID(),
        targetAccountId: UUID? = UUID.randomUUID(),
    ) = Transaction(
        id = UUID.randomUUID(),
        referenceNumber = "TXN-1",
        type = TransactionType.TRANSFER,
        sourceAccountId = sourceAccountId,
        targetAccountId = targetAccountId,
        amount = amount,
        fxRate = fxRate,
        baseAmount = baseAmount,
        status = TransactionStatus.PENDING,
        description = "test",
        valueDate = LocalDate.of(2026, 6, 1),
        bookingDate = LocalDate.of(2026, 6, 1),
        initiatedAt = Instant.now(),
        completedAt = null,
        failedAt = null,
        failureReason = null,
        idempotencyKey = "idem-1",
        version = 0L,
    )
}
