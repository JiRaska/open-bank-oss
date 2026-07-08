// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.infrastructure.client.BillingLedgerConfig
import com.openbank.billing.infrastructure.client.BillingJournalFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Unit coverage for [BillingJournalFactory] (ADR-0143 step 2/3). Mirrors
 * `openbank-lending-service`'s `LendingJournalFactoryTest`: balance, GL direction, and the
 * idempotency-key/transaction-id derivation — including the multi-fee distinct-key case that
 * motivates the `feeId` dimension in the first place.
 */
class BillingJournalFactoryTest {

    private val accounts = object : BillingLedgerConfig.Gl {
        override fun feeReceivable(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000001400")
        override fun feeIncome(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004001")
    }
    private val systemActorId = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    private val date = LocalDate.parse("2026-07-01")

    private fun command(
        idempotencyKey: String,
        accountId: String = "acc-1",
        feeId: String = "f1",
        amount: String = "150.00",
        currency: String = "CZK",
    ) = FeeJournalCommand(
        idempotencyKey = idempotencyKey,
        cycleId = "2026-07",
        accountId = accountId,
        feeId = feeId,
        amount = BigDecimal(amount),
        currency = currency,
        description = "Fee charge: Maintenance",
    )

    @Test
    fun `debits the customer fee-receivable GL with subAccountId set to the account and credits fee income`() {
        val accountId = UUID.randomUUID().toString()
        val cmd = command("fee-c-$accountId-f1-CZK", accountId = accountId)
        val lines = BillingJournalFactory.buildLines(cmd, accounts)

        val debit = lines.single { it.side == "DEBIT" }
        val credit = lines.single { it.side == "CREDIT" }
        assertThat(debit.glAccountId).isEqualTo(accounts.feeReceivable())
        assertThat(debit.subAccountId).isEqualTo(UUID.fromString(accountId))
        assertThat(credit.glAccountId).isEqualTo(accounts.feeIncome())
        assertThat(credit.subAccountId).isNull()
    }

    @Test
    fun `is balanced and single-currency (no FX in phase 2)`() {
        val lines = BillingJournalFactory.buildLines(command("fee-c-acc-f1-EUR", currency = "EUR"), accounts)
        assertThat(lines).hasSize(2)
        val debit = lines.single { it.side == "DEBIT" }.amount
        val credit = lines.single { it.side == "CREDIT" }.amount
        assertThat(debit).isEqualByComparingTo(credit)
        lines.forEach {
            assertThat(it.baseCurrencyCode).isEqualTo(it.currencyCode)
            assertThat(it.baseAmount).isEqualByComparingTo(it.amount)
        }
    }

    @Test
    fun `request carries the idempotency key and a deterministic transaction id stable across replays`() {
        val cmd = command("fee-2026-07-acc-1-f1-CZK")
        val request = BillingJournalFactory.buildRequest(cmd, accounts, systemActorId, date)

        assertThat(request.idempotencyKey).isEqualTo("fee-2026-07-acc-1-f1-CZK")
        assertThat(request.transactionId)
            .isEqualTo(UUID.nameUUIDFromBytes("fee-2026-07-acc-1-f1-CZK".toByteArray()))
            .isEqualTo(BillingJournalFactory.buildRequest(cmd, accounts, systemActorId, date).transactionId)
        assertThat(request.entryDate).isEqualTo("2026-07-01")
        assertThat(request.valueDate).isEqualTo("2026-07-01")
        assertThat(request.createdBy).isEqualTo(systemActorId)
        assertThat(request.lines).hasSize(2)
    }

    @Test
    fun `a multi-fee product yields distinct idempotency keys and distinct transaction ids per fee (ADR-0143)`() {
        val feeA = command(idempotencyKey = "fee-2026-07-acc-1-maintenance-CZK", feeId = "maintenance", amount = "50")
        val feeB = command(
            idempotencyKey = "fee-2026-07-acc-1-excess-withdrawal-CZK",
            feeId = "excess-withdrawal",
            amount = "25",
        )

        val requestA = BillingJournalFactory.buildRequest(feeA, accounts, systemActorId, date)
        val requestB = BillingJournalFactory.buildRequest(feeB, accounts, systemActorId, date)

        assertThat(requestA.idempotencyKey).isNotEqualTo(requestB.idempotencyKey)
        assertThat(requestA.transactionId).isNotEqualTo(requestB.transactionId)
    }

    @Test
    fun `a non-UUID accountId still yields a stable deterministic subAccountId rather than dropping the tie-out`() {
        val cmd = command("fee-2026-07-not-a-uuid-f1-CZK", accountId = "not-a-uuid")
        val lines = BillingJournalFactory.buildLines(cmd, accounts)
        val debit = lines.single { it.side == "DEBIT" }
        assertThat(debit.subAccountId).isNotNull()
        // Deterministic: same non-UUID accountId always maps to the same synthetic UUID.
        val linesAgain = BillingJournalFactory.buildLines(cmd, accounts)
        assertThat(debit.subAccountId).isEqualTo(linesAgain.single { it.side == "DEBIT" }.subAccountId)
    }
}
