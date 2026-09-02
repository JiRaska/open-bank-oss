// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application.workflow

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.casecoordinator.domain.model.CaseClass
import com.openbank.casecoordinator.domain.model.CaseDeliveryMode
import com.openbank.casecoordinator.domain.model.CaseOutcome
import com.openbank.casecoordinator.domain.model.CaseSignalEvidenceStage
import com.openbank.casecoordinator.domain.model.CaseStart
import com.openbank.casecoordinator.domain.model.CaseStatus
import com.openbank.casecoordinator.domain.model.ContributeSignal
import com.openbank.casecoordinator.domain.model.JoinSignal
import com.openbank.casecoordinator.domain.model.SupersedeSignal
import com.openbank.casecoordinator.domain.model.SynthesisRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The CaseWorkflow itself, executed for real in Temporal's test environment with only the
 * ACTIVITIES mocked (ADR-0244 D5/D7/D9). What has to be true cannot be shown by testing the
 * pieces: pre-emption must leave exactly one synthesis over exactly the final draft, the
 * contested breaker must skip auto-synthesis, and every exit path must emit exactly one
 * HITL proposal — all four are properties of the workflow's control flow, not of any unit.
 *
 * The client converter is the kotlin-aware one the shared TemporalClientProducer builds:
 * stock Jackson cannot construct Kotlin data classes, and every payload here is one.
 */
class CaseWorkflowTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var synthesis: CaseSynthesisActivity
    private lateinit var proposals: CaseProposalActivity
    private lateinit var deliveryProposals: CaseProposalDeliveryActivity
    private lateinit var persistence: CasePersistenceActivity

    companion object {
        private const val TASK_QUEUE = "test-case-workflow"
        private const val THRESHOLD = 0.35
        private const val TTL_MS = 3_600_000L

        internal fun kotlinAwareDataConverter(): DataConverter = DefaultDataConverter
            .newDefaultInstance()
            .withPayloadConverterOverrides(
                JacksonJsonPayloadConverter(
                    JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule(),
                ),
            )
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder().setDataConverter(kotlinAwareDataConverter()).build(),
                )
                .build(),
        )
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(CaseWorkflowImpl::class.java)
        synthesis = mockk(relaxed = true)
        proposals = mockk(relaxed = true)
        deliveryProposals = mockk(relaxed = true)
        persistence = mockk(relaxed = true)
        every { synthesis.synthesize(any(), any(), any()) } returns "CONVERGED: restart the ingest consumer"
        every { deliveryProposals.emitProposalWithDelivery(any(), any(), any(), any(), any()) } returns "proposal-1"
        worker.registerActivitiesImplementations(synthesis, proposals, deliveryProposals, persistence)
        env.start()
    }

    @AfterEach
    fun tearDown() = env.close()

    private fun start(deadlineMs: Long = TTL_MS, deliveryMode: CaseDeliveryMode = CaseDeliveryMode.HITL): CaseWorkflow {
        val start = CaseStart(
            caseId = "case-incident-response-ingest-1",
            caseClass = CaseClass.INCIDENT_RESPONSE,
            subjectRef = "ingest-1",
            openedBy = "case-coordinator",
            dispositionTarget = "hitl-incident-queue",
            deadlineEpochMs = System.currentTimeMillis() + deadlineMs,
            contestedRateThreshold = THRESHOLD,
            maxContributions = 40,
            deliveryMode = deliveryMode,
        )
        val stub = env.workflowClient.newWorkflowStub(
            CaseWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build(),
        )
        WorkflowClient.start(stub::run, start)
        return stub
    }

    private fun result(stub: CaseWorkflow): CaseOutcome =
        WorkflowStub.fromTyped(stub).getResult(CaseOutcome::class.java)

    @Test
    fun `join contribute then synthesis emits exactly one proposal`() {
        val stub = start()
        stub.join(
            JoinSignal(
                "incident-responder",
                "investigator",
                "11111111-1111-1111-1111-111111111111",
                "rollout-shadow-1",
            ),
        )
        stub.contribute(
            ContributeSignal(
                "incident-responder",
                "consumer lag spike after deploy",
                listOf("grafana/x"),
                false,
                "22222222-2222-2222-2222-222222222222",
                "rollout-shadow-1",
            ),
        )
        stub.requestSynthesis(SynthesisRequest("case-coordinator"))

        val outcome = result(stub)

        assertThat(outcome.status).isEqualTo(CaseStatus.SYNTHESIZED)
        assertThat(outcome.proposalId).isEqualTo("proposal-1")
        verify(exactly = 1) { synthesis.synthesize("case-incident-response-ingest-1", "INCIDENT_RESPONSE", any()) }
        verify(exactly = 1) {
            deliveryProposals.emitProposalWithDelivery(
                "case-incident-response-ingest-1",
                "case-synthesis",
                any(),
                false,
                false,
            )
        }
        verify(exactly = 1) {
            persistence.recordSignalEvidence(
                match { evidence ->
                    evidence.size == 2 &&
                        evidence.all { it.stage == CaseSignalEvidenceStage.CONSUMED } &&
                        evidence.map { it.signalId }.toSet() == setOf(
                            "11111111-1111-1111-1111-111111111111",
                            "22222222-2222-2222-2222-222222222222",
                        )
                },
            )
        }
    }

    @Test
    fun `shadow case never requests HITL publication`() {
        val stub = start(deliveryMode = CaseDeliveryMode.SHADOW)
        stub.requestSynthesis(SynthesisRequest("case-coordinator"))

        result(stub)

        verify(exactly = 1) {
            deliveryProposals.emitProposalWithDelivery(any(), "case-synthesis", any(), false, true)
        }
    }

    @Test
    fun `superseded-by-evidence pre-empts the draft and the case still converges once`() {
        val stub = start()
        stub.contribute(ContributeSignal("agent-a", "stale draft: wrong pod", emptyList(), false))
        stub.supersede(SupersedeSignal("case-coordinator", "log-bundle-9", "newer evidence"))
        stub.contribute(ContributeSignal("agent-b", "fresh draft: kafka lag", listOf("log-bundle-9"), false))
        stub.requestSynthesis(SynthesisRequest("case-coordinator"))

        val outcome = result(stub)

        assertThat(outcome.status).isEqualTo(CaseStatus.SYNTHESIZED)
        verify(exactly = 1) {
            synthesis.synthesize(
                any(),
                any(),
                match { list ->
                    list.size == 1 &&
                        list.single().summary == "fresh draft: kafka lag" &&
                        list.single().draftVersion == 1
                },
            )
        }
        verify(exactly = 1) { deliveryProposals.emitProposalWithDelivery(any(), any(), any(), any(), any()) }
        // Both contributions — superseded draft included — are recorded history for the thread view.
        verify(exactly = 1) {
            persistence.recordContributions(any(), match { it.size == 2 })
        }
    }

    @Test
    fun `contested circuit breaker escalates to HITL without auto-synthesis`() {
        val stub = start()
        stub.contribute(ContributeSignal("agent-a", "this diagnosis is wrong", emptyList(), contested = true))

        val outcome = result(stub)

        assertThat(outcome.status).isEqualTo(CaseStatus.CONTESTED)
        verify(exactly = 0) { synthesis.synthesize(any(), any(), any()) }
        verify(exactly = 1) { deliveryProposals.emitProposalWithDelivery(any(), "case-contested", any(), true, false) }
    }

    @Test
    fun `ttl timeout closes the case with exactly one timeout proposal`() {
        val stub = start()

        val outcome = result(stub)

        assertThat(outcome.status).isEqualTo(CaseStatus.CLOSED)
        verify(exactly = 0) { synthesis.synthesize(any(), any(), any()) }
        verify(exactly = 1) { deliveryProposals.emitProposalWithDelivery(any(), "case-timeout", any(), false, false) }
    }

    @Test
    fun `a signal storm still yields exactly one proposal`() {
        val stub = start()
        repeat(5) { i -> stub.join(JoinSignal("agent-$i", "participant")) }
        repeat(7) { i -> stub.contribute(ContributeSignal("agent-${i % 5}", "finding $i", emptyList(), false)) }
        stub.supersede(SupersedeSignal("case-coordinator", "e-1", "preempt"))
        repeat(3) { i -> stub.contribute(ContributeSignal("agent-$i", "final $i", emptyList(), false)) }
        stub.requestSynthesis(SynthesisRequest("case-coordinator"))

        val outcome = result(stub)

        assertThat(outcome.status).isEqualTo(CaseStatus.SYNTHESIZED)
        assertThat(outcome.contributionCount).isEqualTo(10)
        verify(exactly = 1) { deliveryProposals.emitProposalWithDelivery(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { persistence.recordCaseClosed(any(), "SYNTHESIZED", any()) }
    }
}
