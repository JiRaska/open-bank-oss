// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.usecase

import com.openbank.govaudit.application.port.out.FindingRepository
import com.openbank.govaudit.application.workflow.GovernanceAuditWorkflow
import com.openbank.govaudit.domain.model.GovernanceAuditReport
import com.openbank.govaudit.domain.model.GovernanceFinding
import com.openbank.govaudit.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.workflow.Functions
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The two start paths differ in exactly the property that matters, and only the OPTIONS carry it:
 * `run` builds a collision-proof id (an operator holding an HTTP connection must never be deduped
 * away), while `startDetached` builds the per-day id that makes a second pod's cron fire a no-op.
 * Asserting on the returned report alone cannot see either, so these capture the
 * [WorkflowOptions] actually handed to Temporal.
 *
 * What is deliberately NOT tested here: the WorkflowExecutionAlreadyStarted (dedupe) path.
 * `WorkflowClient.start` is a static on an INTERFACE and mockkStatic does not intercept it -
 * measured, not assumed: with `every { WorkflowClient.start(..) } throws ..` in place the real
 * static still ran and the Func was invoked for real. A dedupe test written that way asserts only
 * the returned workflow id, which is the same value whether or not the throw fires, so it passes
 * against both worlds and discriminates nothing. Covering that path needs a real Temporal
 * TestWorkflowEnvironment, not a mock.
 */
class GovernanceAuditorServiceTest {

    private val report = mockk<GovernanceAuditReport>()
    private val workflowStub = mockk<GovernanceAuditWorkflow> {
        every { runAudit(any()) } returns report
    }
    private val options = slot<WorkflowOptions>()
    private val workflowClient = mockk<WorkflowClient>()
    private val temporalConfig = mockk<TemporalConfig> {
        every { taskQueue() } returns "governance-auditor-queue"
    }
    private val findingRepository = mockk<FindingRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-07-25T04:30:00Z"), ZoneOffset.UTC)

    private fun service(clock: Clock = this.clock) =
        GovernanceAuditorService(workflowClient, temporalConfig, findingRepository, clock)

    @BeforeEach
    fun mockStaticStart() {
        mockkStatic(WorkflowClient::class)
        every {
            workflowClient.newWorkflowStub(GovernanceAuditWorkflow::class.java, capture(options))
        } returns workflowStub
        every { WorkflowClient.start(any<Functions.Func<GovernanceAuditReport>>()) } returns
            WorkflowExecution.getDefaultInstance()
    }

    @AfterEach
    fun releaseStatic() {
        unmockkStatic(WorkflowClient::class)
    }

    @Test
    fun `run waits for the workflow and returns its report`(): Unit = runBlocking {
        assertThat(service().run(RunTrigger.OPERATOR_MANUAL)).isSameAs(report)
        assertThat(options.captured.taskQueue).isEqualTo("governance-auditor-queue")
    }

    @Test
    fun `run uses an id that can never dedupe, so an operator run is never silently dropped`(): Unit = runBlocking {
        val svc = service()
        svc.run(RunTrigger.OPERATOR_MANUAL)
        val first = options.captured.workflowId
        Thread.sleep(2)
        svc.run(RunTrigger.OPERATOR_MANUAL)
        val second = options.captured.workflowId

        assertThat(first).isNotEqualTo(second)
        // And specifically NOT the per-day scheduled id, which would collide with the cron's run.
        assertThat(first).isNotEqualTo(
            GovernanceAuditorService.scheduledWorkflowId(RunTrigger.OPERATOR_MANUAL, clock.instant()),
        )
    }

    @Test
    fun `startDetached returns the per-day id without waiting for the workflow`(): Unit = runBlocking {
        val workflowId = service().startDetached(RunTrigger.SCHEDULED)

        assertThat(workflowId).isEqualTo("governance-audit-scheduled-2026-07-25")
        assertThat(options.captured.workflowId).isEqualTo(workflowId)
        assertThat(options.captured.taskQueue).isEqualTo("governance-auditor-queue")
    }

    @Test
    fun `startDetached reads the clock, so a second UTC day gets its own id`(): Unit = runBlocking {
        val nextDay = Clock.fixed(Instant.parse("2026-07-26T00:00:01Z"), ZoneOffset.UTC)

        assertThat(service(nextDay).startDetached(RunTrigger.SCHEDULED))
            .isEqualTo("governance-audit-scheduled-2026-07-26")
    }

    @Test
    fun `the finding queries are not wired to each other`(): Unit = runBlocking {
        val active = mockk<GovernanceFinding>()
        val byId = mockk<GovernanceFinding>()
        coEvery { findingRepository.findActive() } returns listOf(active)
        coEvery { findingRepository.findById("f-1") } returns byId
        coEvery { findingRepository.findById("missing") } returns null

        val svc = service()
        assertThat(svc.getActive()).containsExactly(active)
        assertThat(svc.getById("f-1")).isSameAs(byId)
        assertThat(svc.getById("missing")).isNull()
    }
}
