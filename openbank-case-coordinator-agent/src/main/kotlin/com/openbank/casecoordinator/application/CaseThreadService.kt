// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.domain.model.CaseSummary
import com.openbank.casecoordinator.domain.model.CaseThread
import com.openbank.casecoordinator.infrastructure.persistence.CaseThreadReadRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class CaseThreadService(private val readRepository: CaseThreadReadRepository) {

    /** Empty list when no cases exist — honest empty state (ADR-0231), never synthetic rows. */
    fun listCases(status: String?, limit: Int): List<CaseSummary> =
        readRepository.listCases(status, limit).map(CaseThreadProjection::toSummary)

    /** Null when the case id is unknown — the resource maps that to 404. */
    fun caseThread(caseId: String): CaseThread? {
        val case = readRepository.findCase(caseId) ?: return null
        return CaseThreadProjection.project(
            case = case,
            contributions = readRepository.listContributions(caseId),
            proposals = readRepository.listProposalEvents(caseId),
            loadedAtEpochMs = System.currentTimeMillis(),
            signalEvidence = readRepository.listSignalEvidence(caseId),
        )
    }
}
