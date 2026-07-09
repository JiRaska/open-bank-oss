// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.usecase.FeeNotFoundException
import com.openbank.billing.application.usecase.FeeNotPostedException
import com.openbank.billing.application.usecase.FeeReversalService
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.PostingStatus
import com.openbank.libs.product.WaiveReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit coverage for [FeeReversalService] (ADR-0143 phase 2e): a wrongly-charged fee is
 * reversible only once it was actually POSTED, reversing it is idempotent (no double-reversal),
 * and a fee that was never posted fails cleanly rather than with a generic error.
 */
class FeeReversalServiceTest {

    private fun fee(status: PostingStatus, feeId: String = "f1") = AssessedFee(
        cycleId = "2026-07",
        accountId = "acc-1",
        feeId = feeId,
        name = "Maintenance",
        currency = "CZK",
        chargedAmount = BigDecimal("150.00"),
        waived = false,
        reason = WaiveReason.NOT_WAIVABLE,
        postingStatus = status,
    )

    @Test
    fun `reversing a POSTED fee commits the reversal intent and returns the updated fee`(): Unit = runBlocking {
        val repository = mockk<BillingAssessmentRepository>()
        val posted = fee(PostingStatus.POSTED)
        val afterReversalIntent = posted.copy(
            postingStatus = PostingStatus.REVERSAL_PENDING,
            reversalReason = "waiver bug",
        )
        coEvery { repository.findFeeByIdempotencyKey("fee-2026-07-acc-1-f1-CZK") } returns posted
        coEvery { repository.persistReversalIntent("fee-2026-07-acc-1-f1-CZK", "waiver bug") } returns
            afterReversalIntent

        val service = FeeReversalService(repository)
        val result = service.reverse("fee-2026-07-acc-1-f1-CZK", "waiver bug")

        assertThat(result.postingStatus).isEqualTo(PostingStatus.REVERSAL_PENDING)
        coVerify(exactly = 1) { repository.persistReversalIntent("fee-2026-07-acc-1-f1-CZK", "waiver bug") }
    }

    @Test
    fun `reversing an already-REVERSAL_PENDING fee is idempotent — no second reversal intent is persisted`(): Unit =
        runBlocking {
            val repository = mockk<BillingAssessmentRepository>()
            val pending = fee(PostingStatus.REVERSAL_PENDING)
            coEvery { repository.findFeeByIdempotencyKey("fee-2026-07-acc-1-f1-CZK") } returns pending

            val service = FeeReversalService(repository)
            val result = service.reverse("fee-2026-07-acc-1-f1-CZK", "second attempt")

            assertThat(result).isSameAs(pending)
            coVerify(exactly = 0) { repository.persistReversalIntent(any(), any()) }
        }

    @Test
    fun `reversing an already-REVERSED fee is idempotent — no second reversal intent is persisted`(): Unit =
        runBlocking {
            val repository = mockk<BillingAssessmentRepository>()
            val reversed = fee(PostingStatus.REVERSED)
            coEvery { repository.findFeeByIdempotencyKey("fee-2026-07-acc-1-f1-CZK") } returns reversed

            val service = FeeReversalService(repository)
            val result = service.reverse("fee-2026-07-acc-1-f1-CZK", "second attempt")

            assertThat(result).isSameAs(reversed)
            coVerify(exactly = 0) { repository.persistReversalIntent(any(), any()) }
        }

    @Test
    fun `reversing a fee that was never posted fails cleanly with FeeNotPostedException`(): Unit = runBlocking {
        val repository = mockk<BillingAssessmentRepository>()
        val pending = fee(PostingStatus.PENDING)
        coEvery { repository.findFeeByIdempotencyKey("fee-2026-07-acc-1-f1-CZK") } returns pending

        val service = FeeReversalService(repository)

        assertThatThrownBy { runBlocking { service.reverse("fee-2026-07-acc-1-f1-CZK", "oops") } }
            .isInstanceOf(FeeNotPostedException::class.java)
            .hasMessageContaining("PENDING")
        coVerify(exactly = 0) { repository.persistReversalIntent(any(), any()) }
    }

    @Test
    fun `reversing a waived (NOT_APPLICABLE) fee fails cleanly with FeeNotPostedException`(): Unit = runBlocking {
        val repository = mockk<BillingAssessmentRepository>()
        val waived = fee(PostingStatus.NOT_APPLICABLE)
        coEvery { repository.findFeeByIdempotencyKey("fee-2026-07-acc-1-f1-CZK") } returns waived

        val service = FeeReversalService(repository)

        assertThatThrownBy { runBlocking { service.reverse("fee-2026-07-acc-1-f1-CZK", "oops") } }
            .isInstanceOf(FeeNotPostedException::class.java)
    }

    @Test
    fun `reversing a fee that was never assessed fails cleanly with FeeNotFoundException`(): Unit = runBlocking {
        val repository = mockk<BillingAssessmentRepository>()
        coEvery { repository.findFeeByIdempotencyKey("fee-no-such-key") } returns null

        val service = FeeReversalService(repository)

        assertThatThrownBy { runBlocking { service.reverse("fee-no-such-key", "oops") } }
            .isInstanceOf(FeeNotFoundException::class.java)
            .hasMessageContaining("fee-no-such-key")
        coVerify(exactly = 0) { repository.persistReversalIntent(any(), any()) }
    }
}
