// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.scheduler

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.application.workflow.PaymentAdmissionConfig
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * With the admission chain OFF the re-drive sweep is main's: an attempt is counted BEFORE it is made,
 * so a payment whose re-drive keeps failing spends its budget and reaches the exhausted state, and
 * `run_checked_at` is neither written nor used for ordering. Only the chain-on sweep counts started
 * runs and orders by `run_checked_at`.
 */
class ScreeningRedriveChainOffTest {

    private val payment = UUID.randomUUID()

    /** A repository holding one RECEIVED payment, counting attempts the way the row does. */
    private fun repository(attempts: IntArray): DomesticPaymentRepository = mockk(relaxed = true) {
        coEvery { findRedrivable(any(), any(), any()) } answers {
            if (attempts[0] < firstArg<Int>()) listOf(payment) else emptyList()
        }
        coEvery { findRedrivableLeastRecentlyChecked(any(), any(), any()) } answers {
            if (attempts[0] < firstArg<Int>()) listOf(payment) else emptyList()
        }
        coEvery { recordRedriveAttempt(payment) } answers { attempts[0]++ }
        coEvery { findHeldToReconcile(any(), any()) } returns emptyList()
    }

    private fun scheduler(repo: DomesticPaymentRepository, chainOn: Boolean) = ScreeningRedriveScheduler().apply {
        paymentRepository = repo
        workflowClient = mockk<WorkflowClient> {
            every { newWorkflowStub(DomesticPaymentWorkflow::class.java, any<WorkflowOptions>()) } throws
                IllegalStateException("temporal unreachable")
        }
        clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC)
        domainMetrics = mockk<DomainMetrics>(relaxed = true)
        enabled = true
        maxAttempts = MAX
        temporalTaskQueue = "openbank-domestic-payments"
        admission = PaymentAdmissionConfig(enabled = chainOn)
    }

    @Test
    fun `chain off, a failing start spends the budget as on main and the payment reaches exhausted`(): Unit =
        runBlocking {
            val attempts = intArrayOf(0)
            val repo = repository(attempts)
            val sweep = scheduler(repo, chainOn = false)

            repeat(MAX + 2) { sweep.sweep() }

            assertThat(attempts[0]).describedAs("counted before every attempt, then exhausted").isEqualTo(MAX)
            coVerify(exactly = 0) { repo.markRunChecked(any(), any()) }
            coVerify(exactly = 0) { repo.findRedrivableLeastRecentlyChecked(any(), any(), any()) }
        }

    @Test
    fun `chain on, a failing start spends nothing and the offer is recorded on the row`(): Unit = runBlocking {
        val attempts = intArrayOf(0)
        val repo = repository(attempts)
        val sweep = scheduler(repo, chainOn = true)

        repeat(MAX + 2) { sweep.sweep() }

        assertThat(attempts[0]).isZero()
        coVerify(atLeast = 1) { repo.markRunChecked(payment, any()) }
        coVerify(exactly = 0) { repo.findRedrivable(any(), any(), any()) }
    }

    private companion object {
        const val MAX = 5
    }
}
