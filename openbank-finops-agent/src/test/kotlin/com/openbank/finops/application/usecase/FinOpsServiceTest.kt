// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.application.usecase

import com.openbank.finops.application.port.out.AnomalyRepository
import com.openbank.finops.application.workflow.FinOpsAnalysisWorkflow
import com.openbank.finops.domain.model.AnomalySeverity
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.DetectorId
import com.openbank.finops.domain.model.FinOpsRunReport
import com.openbank.finops.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The two start paths differ in exactly the way that matters operationally: the operator path
 * builds a millisecond-unique id (never dedupes, always runs) and the scheduled path builds a
 * per-day id (dedupes, so a restart cannot double-spend the LLM budget). Both are asserted on the
 * `WorkflowOptions` actually handed to Temporal, which is the only place the choice is visible.
 */
class FinOpsServiceTest {

    private val workflowClient = mockk<WorkflowClient>()
    private val temporalConfig = mockk<TemporalConfig>()
    private val repository = mockk<AnomalyRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-08-02T03:00:00Z"), ZoneOffset.UTC)

    private fun service(clk: Clock = clock) =
        FinOpsService(workflowClient, temporalConfig, repository, clk)

    private fun report() = FinOpsRunReport(
        runId = "run-1",
        startedAt = Instant.EPOCH,
        completedAt = Instant.EPOCH,
        anomaliesDetected = emptyList(),
        anomaliesProposed = 0,
        estimatedTotalMonthlySavingUsd = BigDecimal.ZERO,
        tokensUsed = 0,
        trigger = RunTrigger.OPERATOR_MANUAL,
    )

    @Test
    fun `run waits for the workflow result and returns the report it produced`(): Unit = runBlocking {
        val options = slot<WorkflowOptions>()
        val stub = mockk<FinOpsAnalysisWorkflow>()
        every { temporalConfig.taskQueue() } returns "finops-task-queue"
        every {
            workflowClient.newWorkflowStub(FinOpsAnalysisWorkflow::class.java, capture(options))
        } returns stub
        every { stub.runAnalysis(RunTrigger.OPERATOR_MANUAL) } returns report()

        val result = service().run(RunTrigger.OPERATOR_MANUAL)

        assertThat(result.runId).isEqualTo("run-1")
        assertThat(options.captured.taskQueue).isEqualTo("finops-task-queue")
    }

    @Test
    fun `the operator run id is millisecond-unique, so an operator is never deduped away`(): Unit = runBlocking {
        val options = mutableListOf<WorkflowOptions>()
        val stub = mockk<FinOpsAnalysisWorkflow>()
        every { temporalConfig.taskQueue() } returns "q"
        every {
            workflowClient.newWorkflowStub(FinOpsAnalysisWorkflow::class.java, capture(options))
        } returns stub
        every { stub.runAnalysis(any()) } returns report()

        val svc = service()
        svc.run(RunTrigger.OPERATOR_MANUAL)
        Thread.sleep(2)
        svc.run(RunTrigger.OPERATOR_MANUAL)

        assertThat(options.map { it.workflowId }).allSatisfy { assertThat(it).startsWith("finops-analysis-") }
        assertThat(options[0].workflowId).isNotEqualTo(options[1].workflowId)
        // Crucially it is NOT the per-day scheduled shape, which would dedupe an operator retry.
        assertThat(options[0].workflowId).doesNotContain("operator_manual")
    }

    @Test
    fun `the scheduled id is derived from the injected clock, not from wall time`() {
        val fromClock = FinOpsService.scheduledWorkflowId(RunTrigger.SCHEDULED, Instant.now(clock))

        assertThat(fromClock).isEqualTo("finops-analysis-scheduled-2026-08-02")
    }

    @Test
    fun `getActive delegates to the repository and returns what it holds`(): Unit = runBlocking {
        val anomaly = CostAnomaly(
            id = "a-1",
            detector = DetectorId.D4_EBS_HEALTH,
            severity = AnomalySeverity.CRITICAL,
            detectedAt = Instant.EPOCH,
            title = "EBS",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
            affectedResource = "ebs",
            status = AnomalyStatus.OPEN,
        )
        coEvery { repository.findActive() } returns listOf(anomaly)

        assertThat(service().getActive()).containsExactly(anomaly)
    }

    @Test
    fun `getById returns null for an unknown id rather than inventing an empty anomaly`(): Unit = runBlocking {
        coEvery { repository.findById("nope") } returns null

        assertThat(service().getById("nope")).isNull()
    }
}
