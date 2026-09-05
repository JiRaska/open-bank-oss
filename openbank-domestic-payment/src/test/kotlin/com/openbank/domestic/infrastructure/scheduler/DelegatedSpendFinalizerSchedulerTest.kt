// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.scheduler

import com.openbank.domestic.application.port.`in`.FinalizeAbsentDelegatedSpendUseCase
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration

class DelegatedSpendFinalizerSchedulerTest {
    private val useCase = mockk<FinalizeAbsentDelegatedSpendUseCase>(relaxed = true)
    private val domainMetrics = mockk<DomainMetrics>(relaxed = true)
    private val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)

    @Test
    fun `invalid money path config fails construction`() {
        assertThatThrownBy {
            scheduler(enabled = true, gracePeriod = Duration.ZERO)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            scheduler(enabled = true, batchLimit = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `default off scheduler never finalizes a reservation`(): Unit = runBlocking {
        scheduler(enabled = false).finalizeAbsent()

        coVerify(exactly = 0) { useCase.finalizeBefore(any(), any()) }
    }

    @Test
    fun `enabled scheduler registers and records liveness only after its sweep succeeds`(): Unit = runBlocking {
        every { domainMetrics.registerWorkflowLiveness(any(), any()) } returns liveness
        val scheduler = scheduler(enabled = true)

        scheduler.onStart(mockk())
        scheduler.finalizeAbsent()

        verify(exactly = 1) { liveness.recordSuccess() }
    }

    private fun scheduler(
        enabled: Boolean,
        gracePeriod: Duration = Duration.ofMinutes(DEFAULT_GRACE_MINUTES),
        batchLimit: Int = DEFAULT_BATCH_LIMIT,
    ) = DelegatedSpendFinalizerScheduler(
        useCase = useCase,
        enabled = enabled,
        gracePeriod = gracePeriod,
        batchLimit = batchLimit,
        clock = Clock.systemUTC(),
        domainMetrics = domainMetrics,
    )

    private companion object {
        const val DEFAULT_GRACE_MINUTES = 10L
        const val DEFAULT_BATCH_LIMIT = 100
    }
}
