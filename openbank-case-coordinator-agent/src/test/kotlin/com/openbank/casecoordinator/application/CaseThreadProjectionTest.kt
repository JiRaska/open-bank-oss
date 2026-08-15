// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.domain.model.CaseRow
import com.openbank.casecoordinator.domain.model.ContributionRow
import com.openbank.casecoordinator.domain.model.ProposalEventRow
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
        val thread = CaseThreadProjection.project(caseRow, emptyList(), emptyList())

        assertThat(thread.entries).hasSize(1)
        assertThat(thread.entries[0].type).isEqualTo(ThreadEntryType.CASE_OPENED)
        assertThat(thread.entries[0].atEpochMs).isEqualTo(T0)
    }

    @Test
    fun `entries are ordered oldest first across all entry kinds`() {
        val contribution = ContributionRow(
            agentId = "fraud-agent",
            contributedAtEpochMs = T0 + LATER_MS,
            summary = "velocity spike",
            evidenceRefs = listOf("tx-1"),
            draftVersion = 1,
            superseded = false,
            contested = false,
        )
        val proposal = ProposalEventRow(
            proposalId = "prop-1",
            proposalType = "case-synthesis",
            emittedAtEpochMs = T0 + 2 * LATER_MS,
        )

        val thread = CaseThreadProjection.project(caseRow, listOf(contribution), listOf(proposal))

        assertThat(thread.entries.map { it.type }).containsExactly(
            ThreadEntryType.CASE_OPENED,
            ThreadEntryType.CONTRIBUTION,
            ThreadEntryType.PROPOSAL_EMITTED,
        )
        assertThat(thread.entries[2].proposalId).isEqualTo("prop-1")
    }

    @Test
    fun `superseded and contested flags survive the projection`() {
        val forked = ContributionRow(
            agentId = "kyc-agent",
            contributedAtEpochMs = T0 + LATER_MS,
            summary = "stale draft",
            evidenceRefs = emptyList(),
            draftVersion = 0,
            superseded = true,
            contested = true,
        )

        val thread = CaseThreadProjection.project(caseRow, listOf(forked), emptyList())

        val entry = thread.entries.single { it.type == ThreadEntryType.CONTRIBUTION }
        assertThat(entry.superseded).isTrue()
        assertThat(entry.contested).isTrue()
        assertThat(entry.draftVersion).isZero()
    }

    private companion object {
        const val T0 = 1_760_000_000_000L
        const val DEADLINE_MS = 1_200_000L
        const val LATER_MS = 60_000L
        const val CONTESTED_RATE = 0.5
    }
}
