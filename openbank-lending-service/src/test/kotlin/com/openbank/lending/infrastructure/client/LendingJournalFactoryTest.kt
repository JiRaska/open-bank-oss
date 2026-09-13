// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class LendingJournalFactoryTest {

    private val accounts = LendingGlAccounts(
        loansReceivable = UUID.fromString("a0000000-0000-0000-0000-000000001200"),
        fundingClearing = UUID.fromString("a0000000-0000-0000-0000-000000001100"),
        interestIncome = UUID.fromString("a0000000-0000-0000-0000-000000004100"),
        interestReceivable = UUID.fromString("a0000000-0000-0000-0000-000000001300"),
        loanLossExpense = UUID.fromString("a0000000-0000-0000-0000-000000005100"),
        loanLossAllowance = UUID.fromString("a0000000-0000-0000-0000-000000001400"),
    )
    private val partyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val actor = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val date = LocalDate.parse("2026-05-30")

    private fun posting(kind: PostingKind, ref: String, amount: String = "12000.00") =
        LedgerPosting(reference = ref, partyId = partyId, amount = Money.of(amount, "EUR"), kind = kind)

    private fun side(line: JournalLineRequest) = line.side

    @Test
    fun `disbursement debits loans receivable and credits funding clearing`() {
        val lines = LendingJournalFactory.buildLines(
            posting(PostingKind.DISBURSEMENT, "loan:1:disbursement"),
            accounts,
        )
        val debit = lines.single { side(it) == "DEBIT" }
        val credit = lines.single { side(it) == "CREDIT" }
        assertThat(debit.glAccountId).isEqualTo(accounts.loansReceivable)
        assertThat(credit.glAccountId).isEqualTo(accounts.fundingClearing)
    }

    @Test
    fun `principal repayment reverses the disbursement legs`() {
        val lines = LendingJournalFactory.buildLines(
            posting(PostingKind.PRINCIPAL_REPAYMENT, "loan:1:inst:1:principal"),
            accounts,
        )
        assertThat(lines.single { side(it) == "DEBIT" }.glAccountId).isEqualTo(accounts.fundingClearing)
        assertThat(lines.single { side(it) == "CREDIT" }.glAccountId).isEqualTo(accounts.loansReceivable)
    }

    /**
     * A cooling-off unwind must be the disbursement's mirror image (the same shape as SETTLEMENT
     * below), or it re-books the same asset increase instead of clearing it. Regression test for
     * the bug fixed alongside #3931: WITHDRAWAL_UNWIND used to share the DISBURSEMENT pair
     * unconditionally, doubling Loans Receivable on every statutory withdrawal.
     */
    @Test
    fun `withdrawal unwind reverses the disbursement legs, not re-books them`() {
        val lines = LendingJournalFactory.buildLines(
            posting(PostingKind.WITHDRAWAL_UNWIND, "loan:1:withdraw"),
            accounts,
        )
        assertThat(lines.single { side(it) == "DEBIT" }.glAccountId).isEqualTo(accounts.fundingClearing)
        assertThat(lines.single { side(it) == "CREDIT" }.glAccountId).isEqualTo(accounts.loansReceivable)
    }

    @Test
    fun `interest credits interest income`() {
        val lines = LendingJournalFactory.buildLines(
            posting(PostingKind.INTEREST, "loan:1:inst:1:interest"),
            accounts,
        )
        assertThat(lines.single { side(it) == "DEBIT" }.glAccountId).isEqualTo(accounts.fundingClearing)
        assertThat(lines.single { side(it) == "CREDIT" }.glAccountId).isEqualTo(accounts.interestIncome)
    }

    @Test
    fun `interest accrual recognizes income against a receivable, no cash`() {
        val lines = LendingJournalFactory.buildLines(
            posting(PostingKind.INTEREST_ACCRUAL, "loan:1:inst:1:accrual"),
            accounts,
        )
        // Income earned at due date; the contra is a receivable, not cash clearing.
        assertThat(lines.single { side(it) == "DEBIT" }.glAccountId).isEqualTo(accounts.interestReceivable)
        assertThat(lines.single { side(it) == "CREDIT" }.glAccountId).isEqualTo(accounts.interestIncome)
    }

    @Test
    fun `interest settlement clears the receivable when cash arrives`() {
        val lines = LendingJournalFactory.buildLines(
            posting(PostingKind.INTEREST_SETTLEMENT, "loan:1:inst:1:interest"),
            accounts,
        )
        // Cash in; the previously accrued receivable is cleared (income already recognized at accrual).
        assertThat(lines.single { side(it) == "DEBIT" }.glAccountId).isEqualTo(accounts.fundingClearing)
        assertThat(lines.single { side(it) == "CREDIT" }.glAccountId).isEqualTo(accounts.interestReceivable)
    }

    @Test
    fun `write-off debits the loan loss expense`() {
        val lines = LendingJournalFactory.buildLines(
            posting(PostingKind.WRITE_OFF, "loan:1:writeoff"),
            accounts,
        )
        assertThat(lines.single { side(it) == "DEBIT" }.glAccountId).isEqualTo(accounts.loanLossExpense)
        assertThat(lines.single { side(it) == "CREDIT" }.glAccountId).isEqualTo(accounts.loansReceivable)
    }

    @Test
    fun `provisioning increase debits loan loss expense and credits the allowance`() {
        val lines = LendingJournalFactory.buildLines(
            posting(PostingKind.PROVISIONING, "loan:1:provisioning:2026-06", amount = "50.00"),
            accounts,
        )
        assertThat(lines.single { side(it) == "DEBIT" }.glAccountId).isEqualTo(accounts.loanLossExpense)
        assertThat(lines.single { side(it) == "CREDIT" }.glAccountId).isEqualTo(accounts.loanLossAllowance)
        assertThat(lines).allSatisfy { assertThat(it.amount).isEqualByComparingTo(BigDecimal("50.00")) }
    }

    @Test
    fun `provisioning decrease (release) debits the allowance and credits loan loss expense`() {
        val lines = LendingJournalFactory.buildLines(
            posting(PostingKind.PROVISIONING, "loan:1:provisioning:2026-07", amount = "-30.00"),
            accounts,
        )
        assertThat(lines.single { side(it) == "DEBIT" }.glAccountId).isEqualTo(accounts.loanLossAllowance)
        assertThat(lines.single { side(it) == "CREDIT" }.glAccountId).isEqualTo(accounts.loanLossExpense)
        // The ledger line carries the absolute value; the sign only decides the side.
        assertThat(lines).allSatisfy { assertThat(it.amount).isEqualByComparingTo(BigDecimal("30.00")) }
    }

    @Test
    fun `provisioning never touches loans receivable`() {
        val increase = LendingJournalFactory.buildLines(
            posting(PostingKind.PROVISIONING, "loan:1:provisioning:2026-06", amount = "50.00"),
            accounts,
        )
        val decrease = LendingJournalFactory.buildLines(
            posting(PostingKind.PROVISIONING, "loan:1:provisioning:2026-07", amount = "-30.00"),
            accounts,
        )
        (increase + decrease).forEach { assertThat(it.glAccountId).isNotEqualTo(accounts.loansReceivable) }
    }

    @Test
    fun `every posting kind produces a balanced two-legged single-currency entry`() {
        PostingKind.entries.forEach { kind ->
            val lines = LendingJournalFactory.buildLines(posting(kind, "loan:1:$kind"), accounts)
            assertThat(lines).hasSize(2)
            val debits = lines.filter { side(it) == "DEBIT" }.sumOf { it.amount }
            val credits = lines.filter { side(it) == "CREDIT" }.sumOf { it.amount }
            // Debits == credits within the (single) currency — the ledger's per-currency invariant.
            assertThat(debits).isEqualByComparingTo(credits)
            assertThat(lines.map { it.currencyCode }.toSet()).containsExactly("EUR")
            // Single-currency loan: no FX, base mirrors the leg amount/currency.
            lines.forEach {
                assertThat(it.fxRate).isNull()
                assertThat(it.baseCurrencyCode).isEqualTo(it.currencyCode)
                assertThat(it.baseAmount).isEqualByComparingTo(it.amount)
            }
        }
    }

    @Test
    fun `request carries the reference as idempotency key and a deterministic transaction id`() {
        val p = posting(PostingKind.DISBURSEMENT, "loan:42:disbursement")
        val request = LendingJournalFactory.buildRequest(p, accounts, actor, date)

        assertThat(request.idempotencyKey).isEqualTo("loan:42:disbursement")
        // Stable across replays: same reference → same transaction id.
        assertThat(request.transactionId)
            .isEqualTo(UUID.nameUUIDFromBytes("loan:42:disbursement".toByteArray()))
            .isEqualTo(LendingJournalFactory.buildRequest(p, accounts, actor, date).transactionId)
        assertThat(request.entryDate).isEqualTo("2026-05-30")
        assertThat(request.valueDate).isEqualTo("2026-05-30")
        assertThat(request.createdBy).isEqualTo(actor)
        assertThat(request.lines).hasSize(2)
        assertThat(request.description).contains("disbursement")
    }

    private fun List<JournalLineRequest>.sumOf(sel: (JournalLineRequest) -> BigDecimal): BigDecimal =
        fold(BigDecimal.ZERO) { acc, l -> acc + sel(l) }
}
