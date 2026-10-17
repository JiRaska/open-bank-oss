// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.scheduler

import com.openbank.cardprocessing.application.port.out.PostingBacklog
import com.openbank.cardprocessing.application.usecase.LedgerPostingRedriver
import com.openbank.cardprocessing.application.usecase.RedriveResult
import com.openbank.cardprocessing.infrastructure.scheduler.LedgerPostingRedriveSweep
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.MeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The tick's own control flow: cancellation must propagate (a `runCatching` swallows it), the two
 * halves are independent, and the heartbeat is only recorded when both returned.
 */
class LedgerPostingRedriveSweepTest {

    private val redriver = mockk<LedgerPostingRedriver>()
    private val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
    private val metrics = mockk<DomainMetrics> { every { registerWorkflowLiveness(any(), any()) } returns liveness }
    private val registry = mockk<Instance<MeterRegistry>> { every { isResolvable } returns false }
    private val sweep = LedgerPostingRedriveSweep(redriver, metrics, registry).also {
        it.registerLiveness(mockk(relaxed = true))
    }

    @Test
    fun `cancellation propagates out of the tick`(): Unit = runBlocking {
        coEvery { redriver.redriveDue() } throws CancellationException("shutdown")
        coEvery { redriver.backlog() } returns PostingBacklog(0, 0)

        assertThatThrownBy { runBlocking { sweep.sweep() } }.isInstanceOf(CancellationException::class.java)

        verify(exactly = 0) { liveness.recordSuccess() }
    }

    @Test
    fun `a failing re-drive still refreshes the backlog, but records no heartbeat`(): Unit = runBlocking {
        coEvery { redriver.redriveDue() } throws IllegalStateException("db down")
        coEvery { redriver.backlog() } returns PostingBacklog(unposted = 3, exhausted = 1)

        sweep.sweep()

        coVerify(exactly = 1) { redriver.backlog() }
        verify(exactly = 0) { liveness.recordSuccess() }
    }

    @Test
    fun `a failing backlog read records no heartbeat either`(): Unit = runBlocking {
        coEvery { redriver.redriveDue() } returns RedriveResult(0, 0)
        coEvery { redriver.backlog() } throws IllegalStateException("db down")

        sweep.sweep()

        verify(exactly = 0) { liveness.recordSuccess() }
    }

    @Test
    fun `a clean tick records the heartbeat`(): Unit = runBlocking {
        coEvery { redriver.redriveDue() } returns RedriveResult(2, 0)
        coEvery { redriver.backlog() } returns PostingBacklog(0, 0)

        sweep.sweep()

        verify(exactly = 1) { liveness.recordSuccess() }
        assertThat(true).isTrue()
    }
}
