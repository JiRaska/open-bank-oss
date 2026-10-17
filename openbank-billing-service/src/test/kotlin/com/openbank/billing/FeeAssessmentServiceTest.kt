// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.AccountingClassSource
import com.openbank.billing.application.usecase.FeeAssessmentService
import com.openbank.billing.domain.BillableFee
import com.openbank.libs.product.FeeContext
import com.openbank.libs.product.WaiveReason
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit coverage for the fee assessment use case (ADR-0143 phase 2b), against in-memory fakes.
 * The shared waiver engine itself is tested in openbank-libs; here we assert the billing
 * orchestration: per-fee idempotency keys, waiver/charge mapping, and the fail-closed skip.
 */
class FeeAssessmentServiceTest {

    private val pins = InMemoryPins()

    private fun accounts(billing: AccountBilling?) = object : AccountContextPort {
        override suspend fun resolve(accountId: String, currency: String): AccountBilling? = billing
    }

    private fun service(
        billing: AccountBilling?,
        fees: List<BillableFee>,
        revisions: FakeRevisions = FakeRevisions(fees),
    ) = FeeAssessmentService(accounts(billing), revisions, pins)

    @Suppress("TooGenericExceptionThrown")
    private fun serviceWithUnreachableCatalog(billing: AccountBilling?) = FeeAssessmentService(
        accounts(billing),
        FakeRevisions(emptyList(), failure = RuntimeException("product-catalog unreachable")),
        pins,
    )

    private fun fee(
        id: String,
        amount: String,
        currency: String = "CZK",
        waivable: Boolean = false,
        condition: String? = null,
    ) = BillableFee(id, "Fee $id", "MONTHLY", BigDecimal(amount), currency, waivable, condition)

    @Test
    fun `multiple fees get distinct idempotency keys — no collapse (ADR-0143)`(): Unit = runBlocking {
        val billing = AccountBilling("prod-1", FeeContext(balance = BigDecimal("100"), currency = "CZK"))
        val a = service(billing, listOf(fee("f1", "5"), fee("f2", "3"))).assess("cyc1", "acc1", "CZK")
        val keys = a.journalCommands().map { it.idempotencyKey }
        assertThat(keys).containsExactly("fee-cyc1-acc1-f1-CZK", "fee-cyc1-acc1-f2-CZK")
        assertThat(keys.toSet()).hasSize(2)
    }

    @Test
    fun `a satisfied waiver waives to zero and posts no journal`(): Unit = runBlocking {
        val billing = AccountBilling("prod-1", FeeContext(balance = BigDecimal("60000"), currency = "EUR"))
        val a = service(
            billing,
            listOf(fee("f1", "5", currency = "EUR", waivable = true, condition = "Balance > 50 000 EUR")),
        ).assess("c", "acc", "EUR")
        assertThat(a.assessedFees.single().waived).isTrue()
        assertThat(a.assessedFees.single().reason).isEqualTo(WaiveReason.WAIVED_BY_CONDITION)
        assertThat(a.journalCommands()).isEmpty()
    }

    @Test
    fun `a non-waivable fee is always charged`(): Unit = runBlocking {
        val billing = AccountBilling("p", FeeContext(currency = "CZK"))
        val a = service(billing, listOf(fee("f1", "9"))).assess("c", "acc", "CZK")
        assertThat(a.assessedFees.single().reason).isEqualTo(WaiveReason.NOT_WAIVABLE)
        assertThat(a.journalCommands().single().amount).isEqualByComparingTo("9")
    }

    @Test
    fun `unresolved account context skips the assessment and charges nothing`(): Unit = runBlocking {
        val a = service(null, emptyList()).assess("c", "acc", "CZK")
        assertThat(a.skipped).isTrue()
        assertThat(a.skipReason).isEqualTo("ACCOUNT_CONTEXT_UNRESOLVED")
        assertThat(a.assessedFees).isEmpty()
        assertThat(a.journalCommands()).isEmpty()
    }

    @Test
    fun `an unreachable product catalog skips the assessment and charges nothing`(): Unit = runBlocking {
        val billing = AccountBilling("prod-1", FeeContext(currency = "CZK"))
        val a = serviceWithUnreachableCatalog(billing).assess("c", "acc", "CZK")
        assertThat(a.skipped).isTrue()
        assertThat(a.skipReason).isEqualTo("PRODUCT_CATALOG_UNREACHABLE")
        assertThat(a.assessedFees).isEmpty()
        assertThat(a.journalCommands()).isEmpty()
    }

    @Test
    fun `the first assessment pins the published revision and prices the fee from it`(): Unit = runBlocking {
        val billing = AccountBilling("prod-1", FeeContext(currency = "CZK"))
        val revisions = FakeRevisions(listOf(fee("f1", "5")))
        val a = service(billing, emptyList(), revisions).assess("c", "acc", "CZK")

        assertThat(pins.pins).containsEntry("acc", FakeRevisions.PUBLISHED_ID)
        assertThat(revisions.termsReads).containsExactly(FakeRevisions.PUBLISHED_ID)
        val fee = a.assessedFees.single()
        assertThat(fee.productRevisionId).isEqualTo(FakeRevisions.PUBLISHED_ID)
        assertThat(fee.accountingClass).isEqualTo("CUSTOMER_DEPOSIT_DEMAND")
        assertThat(a.journalCommands().single().accountingClass).isEqualTo("CUSTOMER_DEPOSIT_DEMAND")
    }

    @Test
    fun `a pinned account keeps its revision when the product is later re-published`(): Unit = runBlocking {
        val billing = AccountBilling("prod-1", FeeContext(currency = "CZK"))
        pins.pins["acc"] = FakeRevisions.PUBLISHED_ID
        val revisions = FakeRevisions(listOf(fee("f1", "5")), published = FakeRevisions.OTHER_ID)

        val a = service(billing, emptyList(), revisions).assess("c", "acc", "CZK")

        // Terms come from the pinned revision, never from "the product as currently published".
        assertThat(revisions.termsReads).containsExactly(FakeRevisions.PUBLISHED_ID)
        assertThat(a.assessedFees.single().productRevisionId).isEqualTo(FakeRevisions.PUBLISHED_ID)
        assertThat(pins.pins["acc"]).isEqualTo(FakeRevisions.PUBLISHED_ID)
    }

    @Test
    fun `a product with no published revision is skipped and nothing is pinned`(): Unit = runBlocking {
        val billing = AccountBilling("prod-1", FeeContext(currency = "CZK"))
        val a = service(billing, emptyList(), FakeRevisions(listOf(fee("f1", "5")), published = null))
            .assess("c", "acc", "CZK")

        assertThat(a.skipped).isTrue()
        assertThat(a.skipReason).isEqualTo("PRODUCT_REVISION_UNRESOLVED")
        assertThat(a.journalCommands()).isEmpty()
        assertThat(pins.pins).isEmpty()
    }

    @Test
    fun `a revision with no accounting class is skipped, never posted to a guessed account`(): Unit = runBlocking {
        val billing = AccountBilling("prod-1", FeeContext(currency = "CZK"))
        val revisions = FakeRevisions(
            listOf(fee("f1", "5")),
            accountingClass = null,
            source = AccountingClassSource.NONE,
        )
        val a = service(billing, emptyList(), revisions).assess("c", "acc", "CZK")

        assertThat(a.skipped).isTrue()
        assertThat(a.skipReason).isEqualTo("ACCOUNTING_CLASS_UNRESOLVED")
        assertThat(a.journalCommands()).isEmpty()
    }

    @Test
    fun `a class derived from a legacy product type still assesses`(): Unit = runBlocking {
        val billing = AccountBilling("prod-1", FeeContext(currency = "CZK"))
        val revisions = FakeRevisions(
            listOf(fee("f1", "5")),
            accountingClass = "CUSTOMER_DEPOSIT_SAVINGS",
            source = AccountingClassSource.DERIVED_FROM_LEGACY_PRODUCT_TYPE,
        )
        val a = service(billing, emptyList(), revisions).assess("c", "acc", "CZK")
        assertThat(a.skipped).isFalse()
        assertThat(a.journalCommands().single().accountingClass).isEqualTo("CUSTOMER_DEPOSIT_SAVINGS")
    }
}
