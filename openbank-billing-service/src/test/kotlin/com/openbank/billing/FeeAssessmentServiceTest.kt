// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.ProductCatalogPort
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

    private fun service(billing: AccountBilling?, fees: List<BillableFee>) = FeeAssessmentService(
        object : AccountContextPort {
            override suspend fun resolve(accountId: String, currency: String): AccountBilling? = billing
        },
        object : ProductCatalogPort {
            override suspend fun billableFees(productId: String, currency: String): List<BillableFee> = fees
        },
    )

    private fun serviceWithUnreachableCatalog(billing: AccountBilling?) = FeeAssessmentService(
        object : AccountContextPort {
            override suspend fun resolve(accountId: String, currency: String): AccountBilling? = billing
        },
        object : ProductCatalogPort {
            @Suppress("TooGenericExceptionThrown")
            override suspend fun billableFees(productId: String, currency: String): List<BillableFee> =
                throw RuntimeException("product-catalog unreachable")
        },
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
}
