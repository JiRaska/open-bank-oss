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
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
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
 * The two start paths differ in exactly the way that matters operationally: the operator path is
 * deliberately collision-proof (millis id, always starts) and the scheduled path is deliberately
 * collision-PRONE (day-scoped id, so Temporal rejects a duplicate sweep). These tests hold both
 * shapes, and hold the `WorkflowExecutionAlreadyStarted` catch to that one exception — swallowing
 * anything wider would turn a Temporal outage into a silently "dispatched" sweep.
 */
class AuthzPolicyAuditorServiceTest {

    // Deliberately NOT covered here: the dedupe (WorkflowExecutionAlreadyStarted) and
    // start-failure-propagates paths. `WorkflowClient.start` is a static on an INTERFACE and
    // mockkStatic does not intercept it. Measured, not assumed - with the static "mocked":
    //   verify(exactly = 0) { stub.runCheck(any()) }  ->  "Calls: 1) runCheck(SCHEDULED)"
    //   every { WorkflowClient.start(..) } throws ..  ->  "Expecting code to raise a throwable"
    // i.e. the real static ran and invoked the Func. A dedupe test written against that mock
    // asserts only the returned id, which is identical whether or not the throw fires, so it goes
    // green while discriminating nothing. Those paths need a Temporal TestWorkflowEnvironment.

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
        // Stub runCheck for EVERY trigger, not just the one the operator-run test passes. The
        // detached tests start with SCHEDULED, and an unstubbed call surfaces as
        // "no answer found for ...runCheck(SCHEDULED)" - which then masquerades as the assertion
        // failure of whichever test happened to run it. GovernanceAuditorServiceTest, the passing
        // sibling, stubs its workflow method unconditionally for the same reason.
        every { stub.runCheck(any()) } returns report()
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
    fun `startDetached scopes the workflow id to the UTC day, so a second pod's cron is a no-op`() {
        val id = runBlocking { service().startDetached(RunTrigger.SCHEDULED) }

        assertThat(id).isEqualTo("authz-policy-auditor-check-scheduled-2026-08-02")
        assertThat(options.captured.workflowId).isEqualTo(id)
        assertThat(options.captured.taskQueue).isEqualTo("authz-policy-auditor-queue")
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
