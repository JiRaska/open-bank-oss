// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.scheduler

import com.openbank.cardprocessing.application.port.out.PostingBacklog
import com.openbank.cardprocessing.application.usecase.LedgerPostingRedriver
import com.openbank.cardprocessing.application.usecase.RedriveResult
import com.openbank.cardprocessing.infrastructure.scheduler.LedgerPostingRedriveSweep
import com.openbank.cardprocessing.support.capturePostingLogs
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
    private val meterRegistry = SimpleMeterRegistry()
    private val registry = mockk<Instance<MeterRegistry>> {
        every { isResolvable } returns true
        every { get() } returns meterRegistry
    }
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
    fun `a failing re-drive still refreshes the backlog without logging exception data or recording a heartbeat`() {
        val message = "synthetic-private-redrive-message"
        val cause = "synthetic-private-redrive-cause"
        coEvery { redriver.redriveDue() } throws IllegalStateException(message, IllegalArgumentException(cause))
        coEvery { redriver.backlog() } returns PostingBacklog(unposted = 3, exhausted = 1)

        val logs = capturePostingLogs { runBlocking { sweep.sweep() } }

        coVerify(exactly = 1) { redriver.backlog() }
        verify(exactly = 0) { liveness.recordSuccess() }
        assertThat(meterRegistry.find("openbank.card.processing.ledger.posting.unposted").gauge()!!.value())
            .isEqualTo(3.0)
        assertThat(meterRegistry.find("openbank.card.processing.ledger.posting.exhausted").gauge()!!.value())
            .isEqualTo(1.0)
        assertThat(logs).isNotEmpty()
        assertThat(
            logs.any {
                it.text.contains("ledger posting re-drive failed (failureType=IllegalStateException)")
            },
        ).isTrue()
        assertThat(logs.map { it.text }.joinToString()).doesNotContain(message, cause, "books are missing")
        assertThat(logs.mapNotNull { it.thrown }).isEmpty()
    }

    @Test
    fun `a failing backlog read preserves gauges without logging exception data or recording a heartbeat`() {
        val message = "synthetic-private-backlog-message"
        val cause = "synthetic-private-backlog-cause"
        coEvery { redriver.redriveDue() } returns RedriveResult(2, 0)
        coEvery { redriver.backlog() } returns PostingBacklog(unposted = 4, exhausted = 2)
        runBlocking { sweep.sweep() }
        verify(exactly = 1) { liveness.recordSuccess() }
        coEvery { redriver.backlog() } throws IllegalStateException(message, IllegalArgumentException(cause))

        val logs = capturePostingLogs { runBlocking { sweep.sweep() } }

        coVerify(exactly = 2) { redriver.redriveDue() }
        // The initial successful tick recorded one heartbeat; the failing refresh must not advance it.
        verify(exactly = 1) { liveness.recordSuccess() }
        assertThat(
            meterRegistry.find("openbank.card.processing.ledger.posting.unposted").gauge()!!.value(),
        ).isEqualTo(4.0)
        assertThat(
            meterRegistry.find("openbank.card.processing.ledger.posting.exhausted").gauge()!!.value(),
        ).isEqualTo(2.0)
        assertThat(logs).isNotEmpty()
        assertThat(
            logs.any {
                it.text.contains("ledger posting backlog refresh failed (failureType=IllegalStateException)")
            },
        ).isTrue()
        assertThat(logs.any { it.text.contains("re-drove 2 ledger posting(s)") }).isTrue()
        assertThat(logs.map { it.text }.joinToString()).doesNotContain(message, cause)
        assertThat(logs.mapNotNull { it.thrown }).isEmpty()
    }

    @Test
    fun `a backlog cancellation propagates after re-drive without recording success`(): Unit = runBlocking {
        coEvery { redriver.redriveDue() } returns RedriveResult(2, 0)
        coEvery { redriver.backlog() } throws CancellationException("shutdown")

        assertThatThrownBy { runBlocking { sweep.sweep() } }.isInstanceOf(CancellationException::class.java)

        coVerify(exactly = 1) { redriver.redriveDue() }
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
