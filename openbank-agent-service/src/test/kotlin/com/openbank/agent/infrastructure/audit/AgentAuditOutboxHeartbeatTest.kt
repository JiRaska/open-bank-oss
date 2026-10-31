// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The agent audit outbox poller had no heartbeat. Pins three decisions: an idle poll counts, a
 * poll with a failed send does not, and a transport-disabled dispatcher registers nothing (a
 * no-op must not be able to report success).
 */
class AgentAuditOutboxHeartbeatTest {

    private val outbox = mockk<AgentAuditOutbox>()
    private val emitterInstance = mockk<Instance<Emitter<Record<String, String>>>>()
    private val registry = SimpleMeterRegistry()

    private fun dispatcher(enabled: Boolean): AgentAuditOutboxDispatcher {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return AgentAuditOutboxDispatcher(outbox, emitterInstance, enabled, java.time.Duration.ofSeconds(5)).also {
            it.metrics = DomainMetrics().apply { registryInstance = instance }
            it.registerHeartbeat()
        }
    }

    private fun successRecorded(): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "agent-audit-outbox-dispatch")
        .gauge()
        ?.value()

    @Test
    fun `an empty poll records the heartbeat`(): Unit = runBlocking {
        every { outbox.claim(25) } returns emptyList()
        val dispatcher = dispatcher(enabled = true)
        assertThat(successRecorded()).isEqualTo(0.0)

        dispatcher.dispatch()

        assertThat(successRecorded()).isEqualTo(1.0)
    }

    @Test
    fun `a poll with a rejected send does not record the heartbeat`(): Unit = runBlocking {
        val emitter = mockk<Emitter<Record<String, String>>>()
        every { outbox.claim(25) } returns listOf(AgentAuditOutbox.Claimed(UUID.randomUUID(), "{}"))
        every { emitterInstance.get() } returns emitter
        every { emitter.send(any()) } returns CompletableFuture.failedFuture(IllegalStateException("broker down"))
        every { outbox.failed(any(), any()) } returns 1
        val dispatcher = dispatcher(enabled = true)

        dispatcher.dispatch()

        assertThat(successRecorded()).isEqualTo(0.0)
    }

    @Test
    fun `a disabled dispatcher registers no heartbeat at all`(): Unit = runBlocking {
        val dispatcher = dispatcher(enabled = false)

        dispatcher.dispatch()

        assertThat(successRecorded()).isNull()
    }
}
