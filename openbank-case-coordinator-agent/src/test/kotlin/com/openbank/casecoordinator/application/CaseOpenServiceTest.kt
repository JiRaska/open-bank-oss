// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.domain.model.CaseClass
import com.openbank.casecoordinator.infrastructure.config.CaseCoordinatorConfig
import com.openbank.libs.temporal.TemporalConfig
import io.mockk.every
import io.mockk.mockk
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowStub
import io.temporal.serviceclient.WorkflowServiceStubs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Case-open authority rules (ADR-0244 D9): capability, per-agent rate limit, dedup mapping.
 * What is tested is the service's OWN logic: who is denied, when the quota bites, how Temporal's
 * already-started answer maps to Duplicate. The server-side dedup guarantee itself needs a real
 * Temporal server and is carried by the workflow-id reuse policy, not by this test.
 */
class CaseOpenServiceTest {

    private val workflowClient = mockk<WorkflowClient>()
    private val temporalConfig = mockk<TemporalConfig>()
    private val gate = mockk<CaseCapabilityGate>()
    private val config = mockk<CaseCoordinatorConfig>()
    private val caseGroup = mockk<CaseCoordinatorConfig.CaseGroup>()
    private val clock = Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC)
    private val untyped = mockk<WorkflowStub>()

    private lateinit var service: CaseOpenService

    companion object {
        private const val COORDINATOR = "case-coordinator"
    }

    @BeforeEach
    fun setUp() {
        every { temporalConfig.enabled() } returns true
        every { temporalConfig.taskQueue() } returns "case-coordinator"
        every { gate.canOpenCase(COORDINATOR) } returns true
        every { config.case() } returns caseGroup
        every { caseGroup.enabledClasses() } returns setOf(CaseClass.INCIDENT_RESPONSE)
        every { caseGroup.maxOpensPerAgentPerHour() } returns 1
        every { caseGroup.maxConcurrent() } returns 15
        every { caseGroup.ttl() } returns java.time.Duration.ofMinutes(20)
        every { caseGroup.contestedRateThreshold() } returns 0.35
        every { caseGroup.maxContributions() } returns 40
        every { workflowClient.options } returns WorkflowClientOptions.newBuilder().setNamespace("openbank").build()
        every { workflowClient.newUntypedWorkflowStub("CaseWorkflow", any()) } returns untyped
        every { untyped.start(any()) } returns WorkflowExecution.getDefaultInstance()
        val stubs = mockk<WorkflowServiceStubs>()
        val blocking = mockk<WorkflowServiceGrpc.WorkflowServiceBlockingStub>()
        every { workflowClient.workflowServiceStubs } returns stubs
        every { stubs.blockingStub() } returns blocking
        every { blocking.listOpenWorkflowExecutions(any()) } returns
            ListOpenWorkflowExecutionsResponse.getDefaultInstance()
        service = CaseOpenService(workflowClient, temporalConfig, gate, config, clock)
    }

    private fun open(agent: String = COORDINATOR, subject: String = "ingest-1"): CaseOpenResult =
        service.open(agent, CaseClass.INCIDENT_RESPONSE, subject, "hitl-incident-queue")

    @Test
    fun `temporal disabled means unavailable without touching the gate`() {
        every { temporalConfig.enabled() } returns false

        assertThat(open()).isEqualTo(CaseOpenResult.Unavailable)
    }

    @Test
    fun `an agent without case-open capability is denied`() {
        every { gate.canOpenCase("fraud-analyst") } returns false

        assertThat(open(agent = "fraud-analyst")).isEqualTo(CaseOpenResult.Denied)
    }

    @Test
    fun `a disabled case class is denied even for the coordinator`() {
        assertThat(
            service.open(COORDINATOR, CaseClass.FRAUD_INVESTIGATION, "case-7", "hitl-fraud-queue"),
        ).isEqualTo(CaseOpenResult.Denied)
    }

    @Test
    fun `the second open within the hour is rate limited`() {
        assertThat(open()).isInstanceOf(CaseOpenResult.Opened::class.java)

        assertThat(open(subject = "ingest-2")).isEqualTo(CaseOpenResult.RateLimited)
    }

    @Test
    fun `an already-running case for the same subject maps to duplicate`() {
        every { untyped.start(any()) } throws WorkflowExecutionAlreadyStarted(
            WorkflowExecution.getDefaultInstance(),
            "CaseWorkflow",
            null,
        )

        assertThat(open()).isEqualTo(CaseOpenResult.Duplicate)
    }

    @Test
    fun `workflow ids are deterministic per class and sanitized subject`() {
        assertThat(service.workflowIdFor(CaseClass.INCIDENT_RESPONSE, "ingest pod/1"))
            .isEqualTo("case-incident-response-ingest-pod-1")
    }
}
