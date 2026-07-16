// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.openbank.interest.application.port.out.CapitalizationPosting
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.WithholdingTaxPolicy
import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The ADR-0033 §D capitalization split (`InterestService.capitalize` → ledger). This is a money
 * path: a wrong account, a missing sub-ledger dimension or an unstable idempotency key each
 * misstate real customer balances, so the accounting is asserted here in full, with no HTTP.
 */
class CapitalizationJournalFactoryTest {

    private val accountId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val config = TestLedgerConfig

    private val interestExpenseCzk = UUID.fromString("a0000000-0000-0000-0000-000000004010")
    private val interestExpenseEur = UUID.fromString("a0000000-0000-0000-0000-000000004011")
    private val depositControlCzk = UUID.fromString("a0000000-0000-0000-0000-000000000002")
    private val depositControlEur = UUID.fromString("a0000000-0000-0000-0000-000000002101")
    private val withholdingTaxPayable = UUID.fromString("a0000000-0000-0000-0000-000000002200")

    // --- The three-leg split ------------------------------------------------------------------

    @Test
    fun `a withheld CZK capitalization splits into three balanced legs`() {
        // gross 100 CZK, resident individual -> base 100, tax 15, net 85 (WithholdingTaxPolicy).
        val lines = CapitalizationJournalFactory.buildLines(
            posting(gross = "100.00", tax = "15", net = "85.00"),
            config,
        )

        assertThat(lines).hasSize(3)
        val debit = lines.single { it.side == "DEBIT" }
        assertThat(debit.glAccountId).isEqualTo(interestExpenseCzk)
        assertThat(debit.amount).isEqualByComparingTo("100.00")
        // The bank's expense is the GROSS: the customer's tax is still the bank's interest cost,
        // it is merely paid to the state instead of to the customer.
        assertThat(debit.subAccountId).isNull()

        val deposit = lines.single { it.glAccountId == depositControlCzk }
        assertThat(deposit.side).isEqualTo("CREDIT")
        assertThat(deposit.amount).isEqualByComparingTo("85.00")

        val tax = lines.single { it.glAccountId == withholdingTaxPayable }
        assertThat(tax.side).isEqualTo("CREDIT")
        assertThat(tax.amount).isEqualByComparingTo("15")
        assertThat(tax.subAccountId).isNull()

        assertBalanced(lines)
    }

    @Test
    fun `the deposit leg names the customer sub-ledger and no other leg does`() {
        val lines = CapitalizationJournalFactory.buildLines(
            posting(gross = "100.00", tax = "15", net = "85.00"),
            config,
        )

        // subAccountId is what ties the GL control account out against the per-customer analytical
        // sub-ledger (CNB 563/1991 + 501/2002). The ledger does NOT require it on a control leg, so
        // omitting it would post silently and break the next tie-out run instead of failing here.
        val withSub = lines.filter { it.subAccountId != null }
        assertThat(withSub).hasSize(1)
        assertThat(withSub.single().glAccountId).isEqualTo(depositControlCzk)
        assertThat(withSub.single().subAccountId).isEqualTo(accountId)
        // ...and the ledger rejects the whole entry if a non-deposit-control leg carries one.
        assertThat(lines.filter { it.glAccountId != depositControlCzk }).allMatch { it.subAccountId == null }
    }

    // --- Zero tax ----------------------------------------------------------------------------

    @Test
    fun `a zero-tax capitalization emits two legs, never a zero-amount third`() {
        // Real case, not a contrivance: the statutory whole-CZK DOWN rounding means any gross below
        // 7.00 CZK yields floor(gross) * 0.15 = 0 while the interest is still WITHHELD.
        val result = WithholdingTaxPolicy.compute(BigDecimal("6.99"), "CZK", TaxProfile.FAIL_SAFE_DEFAULT, PERIOD_TO)
        assertThat(result.taxAmount).isEqualByComparingTo(BigDecimal.ZERO)

        val lines = CapitalizationJournalFactory.buildLines(
            posting(gross = "6.99", tax = result.taxAmount.toPlainString(), net = result.netAmount.toPlainString()),
            config,
        )

        // The ledger enforces CHECK (amount > 0) per line, so a zero tax leg would fail the whole
        // entry — taking the customer's legitimate credit down with it.
        assertThat(lines).hasSize(2)
        assertThat(lines).noneMatch { it.amount.signum() == 0 }
        assertThat(lines.map { it.glAccountId }).containsExactlyInAnyOrder(interestExpenseCzk, depositControlCzk)
        assertBalanced(lines)
    }

    @Test
    fun `a not-withheld legal entity is credited gross in two legs`() {
        val lines = CapitalizationJournalFactory.buildLines(
            posting(gross = "100.00", tax = "0", net = "100.00"),
            config,
        )

        assertThat(lines).hasSize(2)
        assertThat(lines.single { it.side == "CREDIT" }.amount).isEqualByComparingTo("100.00")
        assertBalanced(lines)
    }

    // --- Non-CZK -----------------------------------------------------------------------------

    @Test
    fun `non-CZK interest routes to the currency's own control and expense accounts`() {
        // §E: only CZK is withheld in v1; EUR interest is DEFERRED_FX, tax 0, credited gross.
        val result = WithholdingTaxPolicy.compute(BigDecimal("50.00"), "EUR", TaxProfile.FAIL_SAFE_DEFAULT, PERIOD_TO)
        assertThat(result.taxAmount).isEqualByComparingTo(BigDecimal.ZERO)

        val lines = CapitalizationJournalFactory.buildLines(
            posting(gross = "50.00", tax = "0", net = "50.00", currency = "EUR"),
            config,
        )

        assertThat(lines).hasSize(2)
        // Both legs must be EUR accounts: the ledger rejects a line whose base currency differs
        // from its GL account's declared currency (422 — the V14 cash-clearing bug).
        assertThat(lines.single { it.side == "DEBIT" }.glAccountId).isEqualTo(interestExpenseEur)
        assertThat(lines.single { it.side == "CREDIT" }.glAccountId).isEqualTo(depositControlEur)
        assertThat(lines.single { it.side == "CREDIT" }.subAccountId).isEqualTo(accountId)
        assertThat(lines).allMatch { it.currencyCode == "EUR" && it.baseCurrencyCode == "EUR" }
        // The CZK-only withholding-tax-payable account must never appear on a foreign entry.
        assertThat(lines.map { it.glAccountId }).doesNotContain(withholdingTaxPayable)
        assertBalanced(lines)
    }

    @Test
    fun `an unseeded currency is refused rather than posted to a guessed account`() {
        assertThatThrownBy {
            CapitalizationJournalFactory.buildLines(
                posting(gross = "10.00", tax = "0", net = "10.00", currency = "PLN"),
                config,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("PLN")
    }

    // --- Idempotency ---------------------------------------------------------------------------

    @Test
    fun `the idempotency key is identical across two attempts with different capitalization ids`() {
        // The defect this guards: cap.id defaults to a fresh UUID per construction, so keying the
        // ledger on it would let a crash-then-retry mint a new key and credit the customer TWICE.
        val first = capitalization()
        val second = capitalization()
        assertThat(first.id).isNotEqualTo(second.id)

        val keyOne = CapitalizationJournalFactory.idempotencyKey(postingOf(first))
        val keyTwo = CapitalizationJournalFactory.idempotencyKey(postingOf(second))

        assertThat(keyOne).isEqualTo(keyTwo)
        // Business identity == the V6 unique index on (account_id, product_id, period_to).
        assertThat(keyOne).isEqualTo("interest-capitalization-$accountId-SAVINGS_CZK-2026-01-20")
    }

    @Test
    fun `the transaction id is derived from the same business identity, so a retry reuses it`() {
        val one = CapitalizationJournalFactory.buildRequest(postingOf(capitalization()), config)
        val two = CapitalizationJournalFactory.buildRequest(postingOf(capitalization()), config)

        assertThat(one.transactionId).isEqualTo(two.transactionId)
        assertThat(one.idempotencyKey).isEqualTo(two.idempotencyKey)
    }

    @Test
    fun `a different period is a different capitalization`() {
        val january = CapitalizationJournalFactory.idempotencyKey(posting(gross = "1.00", tax = "0", net = "1.00"))
        val february = CapitalizationJournalFactory.idempotencyKey(
            posting(gross = "1.00", tax = "0", net = "1.00", periodTo = LocalDate.of(2026, 2, 20)),
        )
        assertThat(january).isNotEqualTo(february)
    }

    // --- Guards --------------------------------------------------------------------------------

    @Test
    fun `an unbalanced posting cannot even be constructed, let alone reach the ledger`() {
        // The guard moved from this factory into CapitalizationPosting's own constructor: an
        // unbalanced split can no longer exist as a value, so no future call site can route around
        // the check by building its own request.
        assertThatThrownBy { posting(gross = "100.00", tax = "15", net = "80.00") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("gross=100.00 != net=80.00 + tax=15")
    }

    @Test
    fun `a posting cannot carry an amount the ledger would reject as over-scaled`() {
        // THE finding-1 defect, pinned at the type. InterestService rounded to scale 4 and handed
        // three bare BigDecimals to this port; ledger-service re-wraps every line as
        // Money.of(amount, currencyCode), and Money refuses scale > the currency's minor units.
        // Every money-bearing capitalization therefore 400'd at the boundary — in every currency,
        // under every treatment. CapitalizationPosting carries Money now, so the same mistake is a
        // construction failure here instead of an opaque HTTP 400 in production.
        assertThatThrownBy { Money.of(BigDecimal("100.0000"), "CZK") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Amount scale 4 exceeds currency CZK fraction digits 2")
    }

    @Test
    fun `a mixed-currency posting cannot be constructed`() {
        assertThatThrownBy {
            CapitalizationPosting(
                accountId = accountId,
                productId = "SAVINGS_CZK",
                periodTo = PERIOD_TO,
                gross = Money.of(BigDecimal("100.00"), "CZK"),
                tax = Money.of(BigDecimal("15"), "CZK"),
                net = Money.of(BigDecimal("85.00"), "EUR"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("mixed-currency")
    }

    @Test
    fun `the request dates and author come from the period end and the configured system actor`() {
        val request = CapitalizationJournalFactory.buildRequest(
            posting(gross = "100.00", tax = "15", net = "85.00"),
            config,
        )

        // §38d ties the withholding to the credit date, which is the period end.
        assertThat(request.entryDate).isEqualTo("2026-01-20")
        assertThat(request.valueDate).isEqualTo("2026-01-20")
        assertThat(request.createdBy).isEqualTo(TestLedgerConfig.systemActorId())
    }

    // --- Helpers -------------------------------------------------------------------------------

    /**
     * Asserts what `LedgerService.postJournal` will actually do with these lines.
     *
     * The first block is the part the original version of this helper omitted, and it is why a
     * 100%-of-the-path defect shipped: the ledger re-wraps every line as
     * `Money.of(req.amount, req.currencyCode)` / `Money.of(req.baseAmount, req.baseCurrencyCode)`,
     * and `Money`'s init rejects an over-scaled amount. That is the REAL production class from
     * openbank-libs-domain, not a restatement of its rule — a hand-written `assertBalanced` can only
     * ever check the rules its author remembered, and every amount assertion in this file uses
     * `isEqualByComparingTo`, which ignores scale by construction. So the one property that broke
     * was invisible to both.
     */
    private fun assertBalanced(lines: List<JournalLineRequest>) {
        lines.forEach { l ->
            Money.of(l.amount, l.currencyCode)
            Money.of(l.baseAmount, l.baseCurrencyCode)
        }
        lines.map { it.baseCurrencyCode }.distinct().forEach { ccy ->
            val debits = lines.filter { it.side == "DEBIT" && it.baseCurrencyCode == ccy }
                .fold(BigDecimal.ZERO) { acc, l -> acc + l.baseAmount }
            val credits = lines.filter { it.side == "CREDIT" && it.baseCurrencyCode == ccy }
                .fold(BigDecimal.ZERO) { acc, l -> acc + l.baseAmount }
            assertThat(debits).`as`("debits == credits within %s", ccy).isEqualByComparingTo(credits)
        }
        assertThat(lines.size).`as`("the ledger requires at least 2 lines").isGreaterThanOrEqualTo(2)
        assertThat(lines).`as`("the ledger enforces amount > 0 per line").allMatch { it.amount.signum() > 0 }
    }

    private fun posting(
        gross: String,
        tax: String,
        net: String,
        currency: String = "CZK",
        periodTo: LocalDate = PERIOD_TO,
    ) = CapitalizationPosting(
        accountId = accountId,
        productId = "SAVINGS_CZK",
        periodTo = periodTo,
        gross = Money.of(BigDecimal(gross), currency),
        tax = Money.of(BigDecimal(tax), currency),
        net = Money.of(BigDecimal(net), currency),
    )

    /** A capitalization built the way `InterestService` builds it — a FRESH `id` every time. */
    private fun capitalization() = InterestCapitalization(
        accountId = accountId,
        productId = "SAVINGS_CZK",
        periodFrom = LocalDate.of(2026, 1, 18),
        periodTo = PERIOD_TO,
        totalAccrued = BigDecimal("100.00"),
        capitalizedAmount = BigDecimal("85.00"),
        grossAmount = BigDecimal("100.00"),
        taxAmount = BigDecimal("15"),
        netAmount = BigDecimal("85.00"),
        currency = "CZK",
        createdAt = OffsetDateTime.parse("2026-01-20T00:00:00Z"),
    )

    private fun postingOf(cap: InterestCapitalization) = CapitalizationPosting(
        accountId = cap.accountId,
        productId = cap.productId,
        periodTo = cap.periodTo,
        gross = Money.of(cap.grossAmount, cap.currency),
        tax = Money.of(cap.taxAmount, cap.currency),
        net = Money.of(cap.netAmount, cap.currency),
    )

    private companion object {
        private val PERIOD_TO: LocalDate = LocalDate.of(2026, 1, 20)
    }

    /** The seeded defaults from `V17__interest_capitalization_accounts.sql`. */
    private object TestLedgerConfig : InterestLedgerConfig {
        override fun systemActorId(): UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
        override fun gl(): InterestLedgerConfig.Gl = TestGl

        object TestGl : InterestLedgerConfig.Gl {
            override fun interestExpenseCzk(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004010")
            override fun interestExpenseEur(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004011")
            override fun interestExpenseUsd(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004012")
            override fun interestExpenseGbp(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004013")
            override fun withholdingTaxPayable(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000002200")
        }
    }
}
