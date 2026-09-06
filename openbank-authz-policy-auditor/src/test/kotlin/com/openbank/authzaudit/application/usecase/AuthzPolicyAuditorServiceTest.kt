// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.usecase

import com.openbank.authzaudit.application.port.out.FindingRepository
import com.openbank.authzaudit.application.workflow.AuthzPolicyAuditorWorkflow
import com.openbank.authzaudit.domain.model.AuthzPolicyCheckType
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.domain.model.AuthzPolicyReport
import com.openbank.authzaudit.domain.model.FindingSeverity
import com.openbank.authzaudit.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import io.temporal.workflow.Functions
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The two start paths differ in exactly the way that matters operationally: the operator path is
 * deliberately collision-proof (millis id, always starts) and the scheduled path is deliberately
 * collision-PRONE (day-scoped id, so Temporal rejects a duplicate sweep). These tests hold both
 * shapes, and hold the `WorkflowExecutionAlreadyStarted` catch to that one exception — swallowing
 * anything wider would turn a Temporal outage into a silently "dispatched" sweep.
 */
class AuthzPolicyAuditorServiceTest {

    private val workflowClient = mockk<WorkflowClient>()
    private val temporalConfig = mockk<TemporalConfig>()
    private val repository = mockk<FindingRepository>()
    private val stub = mockk<AuthzPolicyAuditorWorkflow>()
    private val options = slot<WorkflowOptions>()

    // 2026-08-02 21:40:11Z is 23:40 in Prague — a local-zone day truncation would name the id
    // after a different day than the one ScheduledWorkflowIdTest pins.
    private val clock = Clock.fixed(Instant.parse("2026-08-02T21:40:11Z"), ZoneOffset.UTC)

    private fun service() = AuthzPolicyAuditorService(workflowClient, temporalConfig, repository, clock)

    @BeforeEach
    fun setUp() {
        every { temporalConfig.taskQueue() } returns "authz-policy-auditor-queue"
        every {
            workflowClient.newWorkflowStub(AuthzPolicyAuditorWorkflow::class.java, capture(options))
        } returns stub
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun report() = AuthzPolicyReport(
        runId = "run-1",
        startedAt = Instant.parse("2026-08-02T21:40:00Z"),
        completedAt = Instant.parse("2026-08-02T21:41:00Z"),
        regoFilesScanned = 12,
        findingsDetected = emptyList(),
        findingsProposed = 0,
        tokensUsed = 0,
        trigger = RunTrigger.OPERATOR_MANUAL,
    )

    @Test
    fun `the operator run waits for the report and uses a collision-proof id on the configured queue`() {
        every { stub.runCheck(RunTrigger.OPERATOR_MANUAL) } returns report()

        val result = runBlocking { service().run(RunTrigger.OPERATOR_MANUAL) }

        assertThat(result).isEqualTo(report())
        assertThat(result.regoFilesScanned).isEqualTo(12)
        assertThat(options.captured.taskQueue).isEqualTo("authz-policy-auditor-queue")
        // A day-scoped id here would make a second operator run of the same day impossible.
        assertThat(options.captured.workflowId).matches("authz-policy-auditor-check-\\d{10,}")
        verify(exactly = 1) { stub.runCheck(RunTrigger.OPERATOR_MANUAL) }
    }

    @Test
    fun `startDetached returns the day-scoped id and does not wait for the workflow`() {
        mockkStatic(WorkflowClient::class)
        every {
            WorkflowClient.start(any<Functions.Func<AuthzPolicyReport>>())
        } returns WorkflowExecution.newBuilder().setWorkflowId("x").setRunId("r").build()

        val id = runBlocking { service().startDetached(RunTrigger.SCHEDULED) }

        assertThat(id).isEqualTo("authz-policy-auditor-check-scheduled-2026-08-02")
        assertThat(options.captured.workflowId).isEqualTo(id)
        // The point of the detached path: the caller never blocks on runCheck.
        verify(exactly = 0) { stub.runCheck(any()) }
        verify(exactly = 1) { WorkflowClient.start(any<Functions.Func<AuthzPolicyReport>>()) }
    }

    @Test
    fun `a duplicate start is swallowed and the id is still returned`() {
        mockkStatic(WorkflowClient::class)
        every { WorkflowClient.start(any<Functions.Func<AuthzPolicyReport>>()) } throws
            WorkflowExecutionAlreadyStarted(
                WorkflowExecution.newBuilder().setWorkflowId("dup").setRunId("r").build(),
                AuthzPolicyAuditorWorkflow::class.java.simpleName,
                null,
            )

        val id = runBlocking { service().startDetached(RunTrigger.SCHEDULED) }

        assertThat(id).isEqualTo("authz-policy-auditor-check-scheduled-2026-08-02")
    }

    @Test
    fun `a start failure that is not a duplicate propagates instead of reading as dispatched`() {
        mockkStatic(WorkflowClient::class)
        every { WorkflowClient.start(any<Functions.Func<AuthzPolicyReport>>()) } throws
            IllegalStateException("temporal frontend unreachable")

        assertThatThrownBy { runBlocking { service().startDetached(RunTrigger.SCHEDULED) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("temporal frontend unreachable")
    }

    @Test
    fun `finding reads go to the repository with the id the caller asked for`() {
        val finding = AuthzPolicyFinding(
            id = "f-1",
            checkType = AuthzPolicyCheckType.REST_BYPASSES_AGENTS_ALLOW,
            severity = FindingSeverity.CRITICAL,
            detectedAt = Instant.parse("2026-08-02T00:00:00Z"),
            title = "rest.rego calls charter_allowed directly",
            component = "rest.rego",
            filePath = "openbank-infra/opa/policies/rest.rego",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
        )
        coEvery { repository.findActive() } returns listOf(finding)
        coEvery { repository.findById("f-1") } returns finding
        coEvery { repository.findById("missing") } returns null

        val service = service()
        assertThat(runBlocking { service.getActive() }).containsExactly(finding)
        assertThat(runBlocking { service.getById("f-1") }).isEqualTo(finding)
        assertThat(runBlocking { service.getById("missing") }).isNull()
        coVerify(exactly = 1) { repository.findById("missing") }
    }
}
