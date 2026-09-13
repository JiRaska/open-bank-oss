// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.domain.model.CaseRow
import com.openbank.casecoordinator.domain.model.CaseSignalEvidenceRow
import com.openbank.casecoordinator.domain.model.ContributionRow
import com.openbank.casecoordinator.domain.model.ProposalEventRow
import com.openbank.casecoordinator.domain.model.RuntimeEvidenceStage
import com.openbank.casecoordinator.domain.model.ThreadEntryType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CaseThreadProjectionTest {

    private val caseRow = CaseRow(
        workflowId = "case-incident-response-alert-7",
        caseClass = "INCIDENT_RESPONSE",
        dispositionTarget = "alert-7",
        status = "CLOSED",
        openedAtEpochMs = T0,
        deadlineAtEpochMs = T0 + DEADLINE_MS,
        contestedRate = CONTESTED_RATE,
        contributionCount = 2,
        budgetTokens = 200_000,
        budgetContributions = 40,
    )

    @Test
    fun `summary maps every list field from the row`() {
        val summary = CaseThreadProjection.toSummary(caseRow)

        assertThat(summary.caseId).isEqualTo("case-incident-response-alert-7")
        assertThat(summary.contributionCount).isEqualTo(2)
        assertThat(summary.contestedRate).isEqualTo(CONTESTED_RATE)
    }

    @Test
    fun `a case with no contributions projects only the opened entry`() {
        val thread = CaseThreadProjection.project(caseRow, emptyList(), emptyList(), LOADED_AT)

        assertThat(thread.entries).hasSize(1)
        assertThat(thread.entries[0].type).isEqualTo(ThreadEntryType.CASE_OPENED)
        assertThat(thread.entries[0].atEpochMs).isEqualTo(T0)
    }

    @Test
    fun `entries are ordered oldest first across all entry kinds`() {
        val contribution = ContributionRow(
            contributionId = "00000000-0000-0000-0000-000000000001",
            agentId = "fraud-agent",
            contributedAtEpochMs = T0 + LATER_MS,
            summary = "velocity spike",
            evidenceRefs = listOf("tx-1"),
            draftVersion = 1,
            superseded = false,
            contested = false,
            tokensUsed = 1200,
        )
        val proposal = ProposalEventRow(
            proposalId = "prop-1",
            proposalType = "case-synthesis",
            status = "SENT",
            emittedAtEpochMs = T0 + 2 * LATER_MS,
        )

        val thread = CaseThreadProjection.project(caseRow, listOf(contribution), listOf(proposal), LOADED_AT)

        assertThat(thread.entries.map { it.type }).containsExactly(
            ThreadEntryType.CASE_OPENED,
            ThreadEntryType.CONTRIBUTION,
            ThreadEntryType.PROPOSAL_EMITTED,
        )
        assertThat(thread.entries[2].proposalId).isEqualTo("prop-1")
    }

    @Test
    fun `a shadow terminal result is not projected as a HITL proposal`() {
        val shadow = ProposalEventRow(
            proposalId = "shadow-1",
            proposalType = "case-synthesis",
            status = "SHADOW",
            emittedAtEpochMs = T0 + LATER_MS,
        )

        val thread = CaseThreadProjection.project(caseRow, emptyList(), listOf(shadow), LOADED_AT)

        val entry = thread.entries.single { it.type == ThreadEntryType.SHADOW_RECORDED }
        assertThat(entry.shadow).isTrue()
        assertThat(thread.entries.map { it.type }).doesNotContain(ThreadEntryType.PROPOSAL_EMITTED)
    }

    @Test
    fun `superseded and contested flags survive the projection`() {
        val forked = ContributionRow(
            contributionId = "00000000-0000-0000-0000-000000000002",
            agentId = "kyc-agent",
            contributedAtEpochMs = T0 + LATER_MS,
            summary = "stale draft",
            evidenceRefs = emptyList(),
            draftVersion = 0,
            superseded = true,
            contested = true,
            tokensUsed = 800,
        )

        val thread = CaseThreadProjection.project(caseRow, listOf(forked), emptyList(), LOADED_AT)

        val entry = thread.entries.single { it.type == ThreadEntryType.CONTRIBUTION }
        assertThat(entry.superseded).isTrue()
        assertThat(entry.contested).isTrue()
        assertThat(entry.draftVersion).isZero()
    }

    @Test
    fun `runtime evidence is derived only from persisted rows`() {
        val contribution = ContributionRow(
            contributionId = "00000000-0000-0000-0000-000000000003",
            agentId = "governance-auditor",
            contributedAtEpochMs = T0 + LATER_MS,
            summary = "policy evidence",
            evidenceRefs = listOf("audit-9"),
            draftVersion = 1,
            superseded = false,
            contested = false,
            tokensUsed = 600,
        )

        val thread = CaseThreadProjection.project(caseRow, listOf(contribution), emptyList(), LOADED_AT)
        val entry = thread.entries.single { it.type == ThreadEntryType.CONTRIBUTION }

        assertThat(entry.runtimeEvidence.evidenceId).isEqualTo(contribution.contributionId)
        assertThat(entry.runtimeEvidence.stage.name).isEqualTo("PERSISTED")
        assertThat(entry.runtimeEvidence.correlationId).isEqualTo(caseRow.workflowId)
        assertThat(thread.retentionPolicy).isEqualTo("not-configured")
        assertThat(thread.coverageStatus).isEqualTo("UNKNOWN_RETENTION")
        assertThat(thread.dataFromEpochMs).isEqualTo(T0)
        assertThat(thread.dataToEpochMs).isEqualTo(T0 + LATER_MS)
        assertThat(thread.lastSuccessfulLoadEpochMs).isEqualTo(LOADED_AT)
        assertThat(thread.budgetTokens).isEqualTo(200_000)
    }

    @Test
    fun `OPA authorization and Temporal stages retain one signal correlation`() {
        val evidence = listOf(
            CaseSignalEvidenceRow(
                signalId = "11111111-1111-1111-1111-111111111111",
                agentId = "rca-investigator",
                capability = "case.contribute",
                stage = "AUTHORIZED",
                observedAtEpochMs = T0 + LATER_MS,
                rolloutId = "shadow-rca-1",
                policyDecisionId = "opa-decision-7",
                policyReason = "allowed by charter and rules matrix",
            ),
            CaseSignalEvidenceRow(
                signalId = "11111111-1111-1111-1111-111111111111",
                agentId = "rca-investigator",
                capability = "case.contribute",
                stage = "CONSUMED",
                observedAtEpochMs = T0 + 2 * LATER_MS,
                rolloutId = "shadow-rca-1",
                policyDecisionId = null,
                policyReason = null,
            ),
        )

        val thread = CaseThreadProjection.project(
            caseRow,
            emptyList(),
            emptyList(),
            LOADED_AT,
            evidence,
        )

        val signalEntries = thread.entries.filter { it.signalId != null }
        assertThat(signalEntries.map { it.type }).containsExactly(
            ThreadEntryType.POLICY_DECISION,
            ThreadEntryType.SIGNAL_CONSUMED,
        )
        assertThat(signalEntries.map { it.runtimeEvidence.stage }).containsExactly(
            RuntimeEvidenceStage.AUTHORIZED,
            RuntimeEvidenceStage.CONSUMED,
        )
        assertThat(signalEntries.map { it.runtimeEvidence.correlationId }).containsOnly(caseRow.workflowId)
        assertThat(signalEntries.first().runtimeEvidence.evidenceId).isEqualTo("opa-decision-7")
    }

    private companion object {
        const val T0 = 1_760_000_000_000L
        const val DEADLINE_MS = 1_200_000L
        const val LATER_MS = 60_000L
        const val LOADED_AT = T0 + 3 * LATER_MS
        const val CONTESTED_RATE = 0.5
    }
}
