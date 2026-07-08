// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.port.out.ProductCatalogPort
import com.openbank.billing.application.usecase.BillingCycleService
import com.openbank.billing.application.usecase.FeeAssessmentService
import com.openbank.billing.domain.BillableFee
import com.openbank.billing.domain.BillingAssessment
import com.openbank.libs.product.FeeContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit coverage for [BillingCycleService] (ADR-0143 phase 2c): idempotent replay of an
 * already-assessed cycle/account/currency, and per-account isolation in a batch run.
 */
class BillingCycleServiceTest {

    private val billing = AccountBilling("prod-1", FeeContext(balance = BigDecimal("100"), currency = "CZK"))

    private fun feeAssessmentService(fees: List<BillableFee> = listOf(fee("f1", "5"))) = FeeAssessmentService(
        object : AccountContextPort {
            override suspend fun resolve(accountId: String, currency: String): AccountBilling? = billing
        },
        object : ProductCatalogPort {
            override suspend fun billableFees(productId: String, currency: String): List<BillableFee> = fees
        },
    )

    private fun fee(id: String, amount: String) =
        BillableFee(id, "Fee $id", "MONTHLY", BigDecimal(amount), "CZK", false, null)

    @Test
    fun `a second call for the same cycle-account-currency returns the existing assessment, never re-assesses`(): Unit =
        runBlocking {
            val repository = mockk<BillingAssessmentRepository>()
            val existing =
                BillingAssessment("c1", "acc1", "CZK", skipped = false, skipReason = null, assessedFees = emptyList())
            coEvery { repository.findExisting("c1", "acc1", "CZK") } returns existing

            val service = BillingCycleService(feeAssessmentService(), repository)
            val result = service.assessAndPost("c1", "acc1", "CZK")

            assertThat(result).isSameAs(existing)
            coVerify(exactly = 0) { repository.persistWithPostingIntent(any()) }
        }

    @Test
    fun `no existing assessment triggers a fresh assess-and-persist`(): Unit = runBlocking {
        val repository = mockk<BillingAssessmentRepository>()
        coEvery { repository.findExisting("c1", "acc1", "CZK") } returns null
        coEvery { repository.persistWithPostingIntent(any()) } answers { firstArg() }

        val service = BillingCycleService(feeAssessmentService(), repository)
        val result = service.assessAndPost("c1", "acc1", "CZK")

        assertThat(result.assessedFees).hasSize(1)
        coVerify(exactly = 1) { repository.persistWithPostingIntent(any()) }
    }

    @Test
    fun `runCycle continues past one account's failure and reports the count that succeeded`(): Unit = runBlocking {
        val repository = mockk<BillingAssessmentRepository>()
        coEvery { repository.findExisting("c1", "acc-bad", "CZK") } throws RuntimeException("boom")
        coEvery { repository.findExisting("c1", "acc-good", "CZK") } returns null
        coEvery { repository.persistWithPostingIntent(any()) } answers { firstArg() }

        val service = BillingCycleService(feeAssessmentService(), repository)
        val processed = service.runCycle("c1", listOf("acc-bad", "acc-good"), "CZK")

        assertThat(processed).isEqualTo(1)
    }
}
