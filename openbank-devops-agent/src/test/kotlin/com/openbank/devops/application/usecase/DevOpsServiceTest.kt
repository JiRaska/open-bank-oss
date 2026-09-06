// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.application.usecase

import com.openbank.devops.application.port.out.FindingRepository
import com.openbank.devops.application.workflow.DevOpsAnalysisWorkflow
import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
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
 * Use-case behaviour of [DevOpsService] (ADR-0119) with Temporal and the repository stubbed.
 *
 * The two start paths are deliberately different and that difference is the thing under test: the
 * operator path must be able to run twice in a day (its id carries a millisecond clock), while the
 * scheduled path must dedupe (its id is trigger+UTC day) and must treat Temporal's
 * "already started" rejection as SUCCESS — it is the dedupe working, not a failure to propagate.
 */
class DevOpsServiceTest {

    private val workflowClient = mockk<WorkflowClient>()
    private val temporalConfig = mockk<TemporalConfig>()
    private val repository = mockk<FindingRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-08-02T21:40:11Z"), ZoneOffset.UTC)
    private val stub = mockk<DevOpsAnalysisWorkflow>(relaxed = true)

    private val service = DevOpsService(workflowClient, temporalConfig, repository, clock)

    @BeforeEach
    fun setUp() {
        every { temporalConfig.taskQueue() } returns "openbank-devops"
        every { workflowClient.newWorkflowStub(DevOpsAnalysisWorkflow::class.java, any<WorkflowOptions>()) } returns stub
        mockkStatic(WorkflowClient::class)
    }

    @AfterEach
    fun tearDown() = unmockkStatic(WorkflowClient::class)

    private fun finding(status: FindingStatus) = DevOpsFinding(
        id = "6f1c0b5e-0000-4000-8000-000000000009",
        detector = DetectorId.D5_SSDLC_HYGIENE,
        severity = FindingSeverity.WARNING,
        detectedAt = Instant.parse("2026-08-02T03:00:00Z"),
        title = "5 open fleet-health issues",
        rawMetricValue = BigDecimal("5"),
        threshold = BigDecimal("3"),
        affectedResource = "github/fleet-health-issues",
        status = status,
    )

    @Test
    fun `the operator run waits for the workflow and returns its report`(): Unit = runBlocking {
        val report = mockk<com.openbank.devops.domain.model.DevOpsRunReport>()
        every { stub.runAnalysis(RunTrigger.OPERATOR_MANUAL) } returns report

        assertThat(service.run(RunTrigger.OPERATOR_MANUAL)).isSameAs(report)
    }

    @Test
    fun `the operator run is started on the configured task queue`(): Unit = runBlocking {
        val options = slot<WorkflowOptions>()
        every {
            workflowClient.newWorkflowStub(DevOpsAnalysisWorkflow::class.java, capture(options))
        } returns stub

        service.run(RunTrigger.OPERATOR_MANUAL)

        assertThat(options.captured.taskQueue).isEqualTo("openbank-devops")
        // The operator id is millisecond-based on purpose: a human must be able to force a second
        // run on a day the schedule has already used.
        assertThat(options.captured.workflowId).startsWith("devops-analysis-")
        assertThat(options.captured.workflowId).isNotEqualTo("devops-analysis-operator_manual-2026-08-02")
    }

    @Test
    fun `the detached start uses the day-scoped id derived from the injected clock`(): Unit = runBlocking {
        val options = slot<WorkflowOptions>()
        every {
            workflowClient.newWorkflowStub(DevOpsAnalysisWorkflow::class.java, capture(options))
        } returns stub
        every { WorkflowClient.start(any()) } returns mockk(relaxed = true)

        val id = service.startDetached(RunTrigger.SCHEDULED)

        assertThat(id).isEqualTo("devops-analysis-scheduled-2026-08-02")
        assertThat(options.captured.workflowId).isEqualTo(id)
        assertThat(options.captured.taskQueue).isEqualTo("openbank-devops")
    }

    @Test
    fun `a duplicate start is swallowed and still returns the id`(): Unit = runBlocking {
        // Two pods, or one restarted after the cron fired, compute the same id. Temporal admits the
        // first and rejects the second; the rejection is the dedupe working, so it must not surface
        // as a scheduler failure.
        every { WorkflowClient.start(any()) } throws mockk<WorkflowExecutionAlreadyStarted>(relaxed = true)

        assertThat(service.startDetached(RunTrigger.SCHEDULED)).isEqualTo("devops-analysis-scheduled-2026-08-02")
    }

    @Test
    fun `approve loads the finding and persists it as APPROVED`(): Unit = runBlocking {
        val updated = slot<DevOpsFinding>()
        coEvery { repository.findById("6f1c0b5e-0000-4000-8000-000000000009") } returns finding(FindingStatus.PROPOSED)
        coEvery { repository.update(capture(updated)) } answers { updated.captured }

        val out = service.approve("6f1c0b5e-0000-4000-8000-000000000009")

        assertThat(out?.status).isEqualTo(FindingStatus.APPROVED)
        // Only the status moves — an HITL decision must not rewrite the evidence.
        assertThat(updated.captured.title).isEqualTo("5 open fleet-health issues")
        assertThat(updated.captured.detectedAt).isEqualTo(Instant.parse("2026-08-02T03:00:00Z"))
    }

    @Test
    fun `reject persists REJECTED, not APPROVED`(): Unit = runBlocking {
        coEvery { repository.findById(any()) } returns finding(FindingStatus.PROPOSED)
        coEvery { repository.update(any()) } answers { firstArg() }

        assertThat(service.reject("6f1c0b5e-0000-4000-8000-000000000009")?.status)
            .isEqualTo(FindingStatus.REJECTED)
    }

    @Test
    fun `an unknown id returns null and writes nothing`(): Unit = runBlocking {
        coEvery { repository.findById("nope") } returns null

        assertThat(service.approve("nope")).isNull()
        assertThat(service.reject("nope")).isNull()
        coVerify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `the read use-cases delegate straight to the repository`(): Unit = runBlocking {
        val active = listOf(finding(FindingStatus.OPEN))
        coEvery { repository.findActive() } returns active
        coEvery { repository.findById("x") } returns null

        assertThat(service.getActive()).isEqualTo(active)
        assertThat(service.getById("x")).isNull()
    }
}
