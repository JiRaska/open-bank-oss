// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.usecase

import com.openbank.libs.temporal.TemporalConfig
import com.openbank.liveness.application.port.out.FindingRepository
import com.openbank.liveness.application.workflow.LivenessCheckWorkflow
import com.openbank.liveness.domain.model.ControlMechanism
import com.openbank.liveness.domain.model.FindingSeverity
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.domain.model.LivenessRunReport
import com.openbank.liveness.domain.model.RunTrigger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import io.temporal.workflow.Functions
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Behaviour of the detached start -- the path the daily schedule uses. The dedupe is what stops a
 * pod restart or a second replica from launching a duplicate fleet-wide sweep and burning the
 * agent's LLM budget twice, so "an already-running workflow is not an error" is the assertion that
 * matters here.
 */
class LivenessSentinelServiceTest {

    private val workflowClient = mockk<WorkflowClient>()
    private val temporalConfig = mockk<TemporalConfig> { every { taskQueue() } returns "liveness-tq" }
    private val repository = mockk<FindingRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-08-02T21:40:11Z"), ZoneOffset.UTC)
    private val stub = mockk<LivenessCheckWorkflow>(relaxed = true)

    private val service = LivenessSentinelService(workflowClient, temporalConfig, repository, clock)

    @BeforeEach
    fun setUp() {
        mockkStatic(WorkflowClient::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(WorkflowClient::class)
    }

    @Test
    fun `startDetached uses the day-and-trigger id, so a second fire the same day collides`(): Unit = runBlocking {
        val options = slot<WorkflowOptions>()
        every {
            workflowClient.newWorkflowStub(LivenessCheckWorkflow::class.java, capture(options))
        } returns stub
        every { WorkflowClient.start(any<Functions.Func<LivenessRunReport>>()) } returns mockk()

        val id = service.startDetached(RunTrigger.SCHEDULED)

        assertThat(id).isEqualTo("liveness-check-scheduled-2026-08-02")
        assertThat(options.captured.workflowId).isEqualTo(id)
        assertThat(options.captured.taskQueue).isEqualTo("liveness-tq")
    }

    @Test
    fun `an operator trigger gets its own id, so the schedule cannot block a human`(): Unit = runBlocking {
        every { workflowClient.newWorkflowStub(LivenessCheckWorkflow::class.java, any<WorkflowOptions>()) } returns stub
        every { WorkflowClient.start(any<Functions.Func<LivenessRunReport>>()) } returns mockk()

        assertThat(service.startDetached(RunTrigger.OPERATOR_MANUAL))
            .isEqualTo("liveness-check-operator_manual-2026-08-02")
    }

    @Test
    fun `an already-running workflow is swallowed and the id still returned`(): Unit = runBlocking {
        every { workflowClient.newWorkflowStub(LivenessCheckWorkflow::class.java, any<WorkflowOptions>()) } returns stub
        every {
            WorkflowClient.start(any<Functions.Func<LivenessRunReport>>())
        } throws WorkflowExecutionAlreadyStarted(
            WorkflowExecution.newBuilder().setWorkflowId("liveness-check-scheduled-2026-08-02").build(),
            "LivenessCheckWorkflow",
            null,
        )

        // Not an error: this IS the dedupe working, and the scheduler must not see a failure.
        assertThat(service.startDetached(RunTrigger.SCHEDULED)).isEqualTo("liveness-check-scheduled-2026-08-02")
    }

    @Test
    fun `getActive and getById read through to the repository`(): Unit = runBlocking {
        val finding = LivenessFinding(
            id = "3f1c6f1e-0000-4000-8000-000000000001",
            mechanism = ControlMechanism.M1_EVENT_CONSUMER_LIVENESS,
            severity = FindingSeverity.WARNING,
            detectedAt = Instant.parse("2026-08-02T03:15:00Z"),
            title = "producer-only topic",
            affectedControl = "openbank.sca.events",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
        )
        coEvery { repository.findActive() } returns listOf(finding)
        coEvery { repository.findById("missing") } returns null

        assertThat(service.getActive()).containsExactly(finding)
        assertThat(service.getById("missing")).isNull()
    }
}
