// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application.workflow

import com.openbank.casecoordinator.domain.model.CaseClass
import com.openbank.casecoordinator.domain.model.CaseDeliveryMode
import com.openbank.casecoordinator.domain.model.CaseOutcome
import com.openbank.casecoordinator.domain.model.CaseStart
import com.openbank.casecoordinator.domain.model.CaseState
import com.openbank.casecoordinator.domain.model.CaseStatus
import com.openbank.casecoordinator.domain.model.ContributeSignal
import com.openbank.casecoordinator.domain.model.Contribution
import com.openbank.casecoordinator.domain.model.JoinSignal
import com.openbank.casecoordinator.domain.model.SupersedeSignal
import com.openbank.casecoordinator.domain.model.SynthesisRequest
import io.mockk.every
import io.mockk.mockk
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.RetryOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import io.temporal.worker.Worker
import io.temporal.workflow.Workflow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** Proves the evidence marker can replay a history produced before that marker existed. */
class CaseWorkflowReplayTest {
    private lateinit var environment: TestWorkflowEnvironment
    private lateinit var worker: Worker

    @BeforeEach
    fun setUp() {
        environment = replayEnvironment()
        worker = environment.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(PreSignalEvidenceCaseWorkflow::class.java)
        val synthesis = mockk<CaseSynthesisActivity>()
        val proposals = mockk<CaseProposalActivity>(relaxed = true)
        val delivery = mockk<CaseProposalDeliveryActivity>()
        val persistence = mockk<CasePersistenceActivity>(relaxed = true)
        every { synthesis.synthesize(any(), any(), any()) } returns "legacy synthesis"
        every { delivery.emitProposalWithDelivery(any(), any(), any(), any(), any()) } returns "proposal-legacy"
        worker.registerActivitiesImplementations(synthesis, proposals, delivery, persistence)
        environment.start()
    }

    @AfterEach
    fun tearDown() = environment.close()

    @Test
    fun `pre-evidence history replays against the current workflow`() {
        val stub = environment.workflowClient.newWorkflowStub(
            CaseWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build(),
        )
        val execution = WorkflowClient.start(stub::run, caseStart())
        stub.join(JoinSignal("rca-investigator", "investigator"))
        stub.contribute(ContributeSignal("rca-investigator", "legacy finding", emptyList(), false))
        stub.requestSynthesis(SynthesisRequest("case-coordinator"))
        WorkflowStub.fromTyped(stub).getResult(CaseOutcome::class.java)
        val history = environment.workflowClient.fetchHistory(execution.workflowId, execution.runId)

        replayEnvironment().use { replay ->
            WorkflowReplayer.replayWorkflowExecution(history, replay, CaseWorkflowImpl::class.java)
        }
    }

    private fun replayEnvironment(): TestWorkflowEnvironment = TestWorkflowEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setDataConverter(CaseWorkflowTest.kotlinAwareDataConverter())
                    .build(),
            )
            .build(),
    )

    private fun caseStart() = CaseStart(
        caseId = CASE_ID,
        caseClass = CaseClass.INCIDENT_RESPONSE,
        subjectRef = "masked-incident",
        openedBy = "case-coordinator",
        dispositionTarget = "shadow-evaluation",
        deadlineEpochMs = System.currentTimeMillis() + Duration.ofHours(1).toMillis(),
        contestedRateThreshold = 0.35,
        maxContributions = 40,
        deliveryMode = CaseDeliveryMode.SHADOW,
    )

    private companion object {
        const val TASK_QUEUE = "test-pre-signal-evidence-history"
        const val CASE_ID = "case-pre-signal-evidence"
    }
}

/** The production command sequence immediately before ADR-0271 signal evidence was added. */
class PreSignalEvidenceCaseWorkflow : CaseWorkflow {
    private val longOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(10))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
        .build()
    private val shortOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(5))
        .build()
    private val synthesis = Workflow.newActivityStub(CaseSynthesisActivity::class.java, longOptions)
    private val proposals = Workflow.newActivityStub(CaseProposalActivity::class.java, shortOptions)
    private val delivery = Workflow.newActivityStub(CaseProposalDeliveryActivity::class.java, shortOptions)
    private val persistence = Workflow.newActivityStub(CasePersistenceActivity::class.java, shortOptions)
    private val participants = linkedSetOf<String>()
    private val contributions = mutableListOf<Contribution>()
    private var synthesisRequested = false
    private lateinit var start: CaseStart

    override fun run(start: CaseStart): CaseOutcome {
        this.start = start
        persistence.recordCaseOpened(start, Workflow.currentTimeMillis())
        val remainingMs = start.deadlineEpochMs - Workflow.currentTimeMillis()
        Workflow.await(Duration.ofMillis(remainingMs)) { synthesisRequested }
        val summary = synthesis.synthesize(start.caseId, start.caseClass.name, contributions) ?: "pending"
        persistence.recordContributions(start.caseId, contributions)
        val proposalId = if (Workflow.getVersion(DELIVERY_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) ==
            Workflow.DEFAULT_VERSION
        ) {
            proposals.emitProposal(start.caseId, "case-synthesis", summary, false)
        } else {
            delivery.emitProposalWithDelivery(start.caseId, "case-synthesis", summary, false, true)
        }
        persistence.recordCaseClosed(start.caseId, CaseStatus.SYNTHESIZED.name, Workflow.currentTimeMillis())
        return CaseOutcome(start.caseId, CaseStatus.SYNTHESIZED, proposalId, summary, contributions.size)
    }

    override fun join(signal: JoinSignal) {
        participants += signal.agentId
    }

    override fun contribute(signal: ContributeSignal) {
        contributions += Contribution(signal.agentId, signal.summary, signal.evidenceRefs, signal.contested, 0)
    }

    override fun supersede(signal: SupersedeSignal) = Unit

    override fun requestSynthesis(request: SynthesisRequest) {
        synthesisRequested = true
    }

    override fun state() = CaseState(
        start.caseId,
        start.caseClass,
        CaseStatus.OPEN,
        participants.toList(),
        contributions.size,
        contributions.count { it.contested },
        0,
        0,
        start.deadlineEpochMs,
    )

    private companion object {
        const val DELIVERY_CHANGE_ID = "case-proposal-delivery-mode-v1"
    }
}
