// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application.workflow

import com.openbank.casecoordinator.domain.model.CaseClass
import com.openbank.casecoordinator.domain.model.CaseOutcome
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
    private val persistence = Workflow.newActivityStub(CasePersistenceActivity::class.java, shortOptions)

    private var status = CaseStatus.OPEN
    private val participants = linkedSetOf<String>()
    private val contributions = mutableListOf<Contribution>()
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
                return emitTerminalProposal(start, "case-timeout", terminalSummary(start), contested = false)
            }
            Workflow.await(Duration.ofMillis(remainingMs)) { synthesisRequested || breakerTripped(start) }
            when {
                breakerTripped(start) -> {
                    status = CaseStatus.CONTESTED
                    return emitTerminalProposal(start, "case-contested", terminalSummary(start), contested = true)
                }
                synthesisRequested -> return synthesize(start)
            }
        }
    }

    override fun join(signal: JoinSignal) {
        participants += signal.agentId
    }

    override fun contribute(signal: ContributeSignal) {
        contributions += Contribution(
            agentId = signal.agentId,
            summary = signal.summary,
            evidenceRefs = signal.evidenceRefs,
            contested = signal.contested,
            draftVersion = draftVersion,
        )
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
        return emitTerminalProposal(start, "case-synthesis", text, contested = false)
    }

    private fun emitTerminalProposal(
        start: CaseStart,
        type: String,
        summary: String,
        contested: Boolean,
    ): CaseOutcome {
        persistence.recordContributions(start.caseId, contributions)
        val proposalId = proposals.emitProposal(start.caseId, type, summary, contested)
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
}
