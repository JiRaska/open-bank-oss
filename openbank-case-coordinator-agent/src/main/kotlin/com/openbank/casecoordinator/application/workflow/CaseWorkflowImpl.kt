// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application.workflow

import com.openbank.casecoordinator.domain.model.CaseClass
import com.openbank.casecoordinator.domain.model.CaseOutcome
import com.openbank.casecoordinator.domain.model.CaseSignalEvidence
import com.openbank.casecoordinator.domain.model.CaseSignalEvidenceStage
import com.openbank.casecoordinator.domain.model.CaseStart
import com.openbank.casecoordinator.domain.model.CaseState
import com.openbank.casecoordinator.domain.model.CaseStatus
import com.openbank.casecoordinator.domain.model.ContributeSignal
import com.openbank.casecoordinator.domain.model.Contribution
import com.openbank.casecoordinator.domain.model.JoinSignal
import com.openbank.casecoordinator.domain.model.SupersedeSignal
import com.openbank.casecoordinator.domain.model.SynthesisRequest
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Deterministic case state machine. Everything non-deterministic (LLM, DB, proposal I/O) sits
 * behind activity stubs; the workflow body only mutates in-memory state, waits on conditions,
 * and calls activities. Signal handlers never call activities — contributions are persisted in
 * one batch at case end, so a signal storm cannot fan out into per-signal I/O.
 *
 * Exactly one HITL proposal per case (ADR-0244 D7) is a control-flow invariant: every exit path
 * funnels through a single `emitTerminalProposal` call, and `run` returns immediately after.
 */
@Suppress("MagicNumber")
class CaseWorkflowImpl : CaseWorkflow {

    private val longOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(10))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
        .build()

    private val shortOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(5))
        .build()

    private val synthesis = Workflow.newActivityStub(CaseSynthesisActivity::class.java, longOptions)
    private val proposals = Workflow.newActivityStub(CaseProposalActivity::class.java, shortOptions)
    private val deliveryProposals = Workflow.newActivityStub(CaseProposalDeliveryActivity::class.java, shortOptions)
    private val persistence = Workflow.newActivityStub(CasePersistenceActivity::class.java, shortOptions)

    private var status = CaseStatus.OPEN
    private val participants = linkedSetOf<String>()
    private val contributions = mutableListOf<Contribution>()
    private val consumedSignals = mutableListOf<CaseSignalEvidence>()
    private var contestedCount = 0
    private var draftVersion = 0
    private var synthesisRequested = false

    private lateinit var caseClass: CaseClass
    private var openedAtEpochMs: Long = 0
    private var deadlineEpochMs: Long = 0

    override fun run(start: CaseStart): CaseOutcome {
        caseClass = start.caseClass
        deadlineEpochMs = start.deadlineEpochMs
        val openedAt = Workflow.currentTimeMillis()
        openedAtEpochMs = openedAt
        persistence.recordCaseOpened(start, openedAt)

        while (true) {
            val remainingMs = start.deadlineEpochMs - Workflow.currentTimeMillis()
            if (remainingMs <= 0) {
                status = CaseStatus.CLOSED
                return emitTerminalProposal(
                    start,
                    eventType = "case-timeout",
                    summary = terminalSummary(start),
                    contested = false,
                )
            }
            Workflow.await(Duration.ofMillis(remainingMs)) { synthesisRequested || breakerTripped(start) }
            when {
                breakerTripped(start) -> {
                    status = CaseStatus.CONTESTED
                    return emitTerminalProposal(
                        start,
                        eventType = "case-contested",
                        summary = terminalSummary(start),
                        contested = true,
                    )
                }
                synthesisRequested -> return synthesize(start)
            }
        }
    }

    override fun join(signal: JoinSignal) {
        participants += signal.agentId
        consumedEvidence(
            Workflow.getInfo().workflowId,
            signal.signalId,
            signal.agentId,
            "case.join",
            signal.rolloutId,
            Workflow.currentTimeMillis(),
        )?.let(consumedSignals::add)
    }

    override fun contribute(signal: ContributeSignal) {
        contributions += Contribution(
            agentId = signal.agentId,
            summary = signal.summary,
            evidenceRefs = signal.evidenceRefs,
            contested = signal.contested,
            draftVersion = draftVersion,
            signalId = signal.signalId,
            rolloutId = signal.rolloutId,
        )
        consumedEvidence(
            Workflow.getInfo().workflowId,
            signal.signalId,
            signal.agentId,
            "case.contribute",
            signal.rolloutId,
            Workflow.currentTimeMillis(),
        )?.let(consumedSignals::add)
        if (signal.contested) contestedCount++
    }

    override fun supersede(signal: SupersedeSignal) {
        draftVersion++
    }

    override fun requestSynthesis(request: SynthesisRequest) {
        synthesisRequested = true
    }

    override fun state(): CaseState = CaseState(
        caseId = Workflow.getInfo().workflowId,
        caseClass = caseClass,
        status = status,
        participants = participants.toList(),
        contributionCount = contributions.size,
        contestedCount = contestedCount,
        draftVersion = draftVersion,
        openedAtEpochMs = openedAtEpochMs,
        deadlineEpochMs = deadlineEpochMs,
    )

    private fun synthesize(start: CaseStart): CaseOutcome {
        status = CaseStatus.CONVERGING
        // D5: only the final draft's contributions feed the judgement; superseded drafts are
        // recorded history, not input.
        val draft = contributions.filter { it.draftVersion == draftVersion }
        val text = synthesis.synthesize(start.caseId, start.caseClass.name, draft)
            ?: "PENDING: synthesis backend unavailable; ${draft.size} contributions on draft v$draftVersion"
        status = CaseStatus.SYNTHESIZED
        return emitTerminalProposal(start, eventType = "case-synthesis", summary = text, contested = false)
    }

    private fun emitTerminalProposal(
        start: CaseStart,
        eventType: String,
        summary: String,
        contested: Boolean,
    ): CaseOutcome {
        if (Workflow.getVersion(SIGNAL_EVIDENCE_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) !=
            Workflow.DEFAULT_VERSION
        ) {
            persistence.recordSignalEvidence(consumedSignals)
        }
        persistence.recordContributions(start.caseId, contributions)
        val proposalId = if (Workflow.getVersion(DELIVERY_MODE_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) ==
            Workflow.DEFAULT_VERSION
        ) {
            proposals.emitProposal(start.caseId, eventType, summary, contested)
        } else {
            deliveryProposals.emitProposalWithDelivery(
                start.caseId,
                eventType,
                summary,
                contested,
                start.deliveryMode.name == "SHADOW",
            )
        }
        persistence.recordCaseClosed(start.caseId, status.name, Workflow.currentTimeMillis())
        return CaseOutcome(
            caseId = start.caseId,
            status = status,
            proposalId = proposalId,
            proposalSummary = summary,
            contributionCount = contributions.size,
        )
    }
    private fun breakerTripped(start: CaseStart): Boolean = contributions.isNotEmpty() &&
        contestedCount.toDouble() / contributions.size > start.contestedRateThreshold

    private fun terminalSummary(start: CaseStart): String = if (status == CaseStatus.CONTESTED) {
        "CONTESTED: $contestedCount of ${contributions.size} contributions contested " +
            "(threshold ${start.contestedRateThreshold}); escalated to HITL without auto-synthesis"
    } else {
        "TIMEOUT: case reached its deadline with ${contributions.size} contributions, " +
            "${participants.size} participants, no synthesis requested"
    }

    private companion object {
        const val DELIVERY_MODE_CHANGE_ID = "case-proposal-delivery-mode-v1"
        const val SIGNAL_EVIDENCE_CHANGE_ID = "case-signal-evidence-v1"
    }
}

private fun consumedEvidence(
    caseId: String,
    signalId: String,
    agentId: String,
    capability: String,
    rolloutId: String,
    observedAtEpochMs: Long,
): CaseSignalEvidence? {
    if (signalId.isBlank()) return null // Legacy histories predate ADR-0271 correlation ids.
    return CaseSignalEvidence(
        signalId = signalId,
        caseId = caseId,
        agentId = agentId,
        capability = capability,
        stage = CaseSignalEvidenceStage.CONSUMED,
        observedAtEpochMs = observedAtEpochMs,
        rolloutId = rolloutId,
    )
}
