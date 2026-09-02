// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.domain.model.CaseRow
import com.openbank.casecoordinator.infrastructure.persistence.CaseThreadReadRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CaseThreadServiceTest {

    private val repository = mockk<CaseThreadReadRepository>()
    private val service = CaseThreadService(repository)

    @Test
    fun `list maps rows to summaries and passes the filter through`() {
        every { repository.listCases("OPEN", LIMIT) } returns listOf(caseRow())

        val cases = service.listCases("OPEN", LIMIT)

        assertThat(cases).hasSize(1)
        assertThat(cases[0].caseId).isEqualTo(WORKFLOW_ID)
        verify { repository.listCases("OPEN", LIMIT) }
    }

    @Test
    fun `list is empty when no cases exist - honest empty state, no synthetic rows`() {
        every { repository.listCases(null, LIMIT) } returns emptyList()

        assertThat(service.listCases(null, LIMIT)).isEmpty()
    }

    @Test
    fun `detail is null for an unknown case id - the resource maps it to 404`() {
        every { repository.findCase("case-nope") } returns null

        assertThat(service.caseThread("case-nope")).isNull()
    }

    @Test
    fun `detail threads contributions and proposals for a known case`() {
        every { repository.findCase(WORKFLOW_ID) } returns caseRow()
        every { repository.listContributions(WORKFLOW_ID) } returns emptyList()
        every { repository.listProposalEvents(WORKFLOW_ID) } returns emptyList()
        every { repository.listSignalEvidence(WORKFLOW_ID) } returns emptyList()

        val thread = service.caseThread(WORKFLOW_ID)

        assertThat(thread).isNotNull
        assertThat(thread!!.caseId).isEqualTo(WORKFLOW_ID)
        assertThat(thread.entries).hasSize(1)
    }

    private fun caseRow(): CaseRow = CaseRow(
        workflowId = WORKFLOW_ID,
        caseClass = "INCIDENT_RESPONSE",
        dispositionTarget = "alert-7",
        status = "OPEN",
        openedAtEpochMs = T0,
        deadlineAtEpochMs = T0 + DEADLINE_MS,
        contestedRate = 0.0,
        contributionCount = 0,
        budgetTokens = 200_000,
        budgetContributions = 40,
    )

    private companion object {
        const val WORKFLOW_ID = "case-incident-response-alert-7"
        const val T0 = 1_760_000_000_000L
        const val DEADLINE_MS = 1_200_000L
        const val LIMIT = 50
    }
}
