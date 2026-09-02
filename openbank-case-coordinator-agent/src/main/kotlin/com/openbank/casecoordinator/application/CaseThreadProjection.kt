// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.domain.model.CaseRow
import com.openbank.casecoordinator.domain.model.CaseSignalEvidenceRow
import com.openbank.casecoordinator.domain.model.CaseSummary
import com.openbank.casecoordinator.domain.model.CaseThread
import com.openbank.casecoordinator.domain.model.ContributionRow
import com.openbank.casecoordinator.domain.model.ProposalEventRow
import com.openbank.casecoordinator.domain.model.RuntimeEvidence
import com.openbank.casecoordinator.domain.model.RuntimeEvidenceStage
import com.openbank.casecoordinator.domain.model.ThreadEntry
import com.openbank.casecoordinator.domain.model.ThreadEntryType

/**
 * Pure projection from persistence rows to the ADR-0246 thread view. Separated from the JDBC
 * repository so the thread semantics (ordering, entry shapes, honest emptiness) are unit-testable
 * without a database — the repository only maps ResultSets to rows.
 */
object CaseThreadProjection {

    fun toSummary(row: CaseRow): CaseSummary = CaseSummary(
        caseId = row.workflowId,
        caseClass = row.caseClass,
        dispositionTarget = row.dispositionTarget,
        status = row.status,
        openedAtEpochMs = row.openedAtEpochMs,
        deadlineAtEpochMs = row.deadlineAtEpochMs,
        contestedRate = row.contestedRate,
        contributionCount = row.contributionCount,
    )

    fun project(
        case: CaseRow,
        contributions: List<ContributionRow>,
        proposals: List<ProposalEventRow>,
        loadedAtEpochMs: Long,
        signalEvidence: List<CaseSignalEvidenceRow> = emptyList(),
    ): CaseThread {
        val entries = buildList {
            add(case.openedEntry())
            contributions.forEach { add(it.threadEntry(case.workflowId)) }
            proposals.forEach { add(it.threadEntry(case.workflowId)) }
            signalEvidence.forEach { add(it.threadEntry(case.workflowId)) }
        }.sortedBy { it.atEpochMs }

        return CaseThread(
            caseId = case.workflowId,
            caseClass = case.caseClass,
            dispositionTarget = case.dispositionTarget,
            status = case.status,
            openedAtEpochMs = case.openedAtEpochMs,
            deadlineAtEpochMs = case.deadlineAtEpochMs,
            contestedRate = case.contestedRate,
            budgetTokens = case.budgetTokens,
            budgetContributions = case.budgetContributions,
            observedAtEpochMs = entries.maxOfOrNull { it.atEpochMs } ?: case.openedAtEpochMs,
            dataFromEpochMs = entries.minOfOrNull { it.atEpochMs } ?: case.openedAtEpochMs,
            dataToEpochMs = entries.maxOfOrNull { it.atEpochMs } ?: case.openedAtEpochMs,
            lastSuccessfulLoadEpochMs = loadedAtEpochMs,
            coverageStatus = COVERAGE_STATUS,
            historySource = HISTORY_SOURCE,
            retentionPolicy = RETENTION_POLICY,
            entries = entries,
        )
    }

    private fun CaseRow.openedEntry(): ThreadEntry = ThreadEntry(
        type = ThreadEntryType.CASE_OPENED,
        atEpochMs = openedAtEpochMs,
        summary = dispositionTarget,
        runtimeEvidence = RuntimeEvidence(
            evidenceId = workflowId,
            source = HISTORY_SOURCE,
            stage = RuntimeEvidenceStage.RECORDED,
            observedAtEpochMs = openedAtEpochMs,
            correlationId = workflowId,
            detail = "Persisted case workflow record",
        ),
    )

    private fun ContributionRow.threadEntry(workflowId: String): ThreadEntry = ThreadEntry(
        type = ThreadEntryType.CONTRIBUTION,
        atEpochMs = contributedAtEpochMs,
        actor = agentId,
        summary = summary,
        evidenceRefs = evidenceRefs,
        draftVersion = draftVersion,
        superseded = superseded,
        contested = contested,
        tokensUsed = tokensUsed,
        runtimeEvidence = RuntimeEvidence(
            evidenceId = contributionId,
            source = HISTORY_SOURCE,
            stage = RuntimeEvidenceStage.PERSISTED,
            observedAtEpochMs = contributedAtEpochMs,
            correlationId = workflowId,
            detail = "Contribution persisted in the durable case read model",
        ),
    )

    private fun ProposalEventRow.threadEntry(workflowId: String): ThreadEntry = ThreadEntry(
        type = if (status == "SHADOW") ThreadEntryType.SHADOW_RECORDED else ThreadEntryType.PROPOSAL_EMITTED,
        atEpochMs = emittedAtEpochMs,
        proposalId = proposalId,
        proposalType = proposalType,
        shadow = status == "SHADOW",
        runtimeEvidence = RuntimeEvidence(
            evidenceId = proposalId,
            source = OUTBOX_SOURCE,
            stage = status.toEvidenceStage(),
            observedAtEpochMs = emittedAtEpochMs,
            correlationId = workflowId,
            detail = "Proposal outbox status: $status",
        ),
    )

    private fun String.toEvidenceStage(): RuntimeEvidenceStage = when (this) {
        "SENT" -> RuntimeEvidenceStage.PUBLISHED_TO_BROKER
        "FAILED", "DEAD" -> RuntimeEvidenceStage.PUBLISH_FAILED
        "SHADOW" -> RuntimeEvidenceStage.SHADOW_RECORDED
        else -> RuntimeEvidenceStage.EMITTED
    }

    private fun CaseSignalEvidenceRow.threadEntry(workflowId: String): ThreadEntry = ThreadEntry(
        type = when (stage) {
            "AUTHORIZED", "DENIED" -> ThreadEntryType.POLICY_DECISION
            "INVOKED" -> ThreadEntryType.SIGNAL_INVOKED
            "CONSUMED" -> ThreadEntryType.SIGNAL_CONSUMED
            else -> ThreadEntryType.CONTRIBUTION_PERSISTED
        },
        atEpochMs = observedAtEpochMs,
        actor = agentId,
        summary = policyReason,
        signalId = signalId,
        capability = capability,
        rolloutId = rolloutId,
        runtimeEvidence = RuntimeEvidence(
            evidenceId = policyDecisionId?.takeIf { it.isNotBlank() } ?: "$signalId:$stage",
            source = SIGNAL_EVIDENCE_SOURCE,
            stage = RuntimeEvidenceStage.valueOf(stage),
            observedAtEpochMs = observedAtEpochMs,
            correlationId = workflowId,
            detail = "$capability signal $signalId",
        ),
    )

    private const val HISTORY_SOURCE = "case-coordinator-postgres-read-model"
    private const val OUTBOX_SOURCE = "case-coordinator-transactional-outbox"
    private const val SIGNAL_EVIDENCE_SOURCE = "case-coordinator-signal-evidence"
    private const val RETENTION_POLICY = "not-configured"
    private const val COVERAGE_STATUS = "UNKNOWN_RETENTION"
}
