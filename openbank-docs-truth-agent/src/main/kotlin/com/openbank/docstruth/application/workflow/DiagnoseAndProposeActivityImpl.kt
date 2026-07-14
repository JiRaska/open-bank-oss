// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.application.workflow

import com.openbank.docstruth.application.port.out.FindingRepository
import com.openbank.docstruth.application.port.out.GitHubProposalPort
import com.openbank.docstruth.application.port.out.LlmDiagnosisPort
import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.docstruth.domain.model.FindingStatus
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.time.Instant

// See CollectRepoScanActivityImpl for why this doesn't use the finops-agent
// VertxContextSupport.subscribeAndAwait { Dispatchers.Unconfined ... } pattern: Temporal activity
// methods run on the Temporal SDK's own activity worker thread pool, not a Vert.x event loop, and
// today every port here (LlmDiagnosisAdapter, GitHubProposalAdapter, InMemoryFindingRepository) is
// a synchronous stub anyway — there is no genuinely async Mutiny call to bridge onto a Vert.x
// context for. Plain runBlocking is simpler and exactly as correct; if a port later gains a real
// non-blocking Mutiny-backed call, that specific port's adapter (not this activity wrapper) is the
// right place to bridge it.
@ApplicationScoped
open class DiagnoseAndProposeActivityImpl(
    private val llm: LlmDiagnosisPort,
    private val githubProposal: GitHubProposalPort,
    private val findingRepository: FindingRepository,
) : DiagnoseAndProposeActivity {

    private val log = Logger.getLogger(DiagnoseAndProposeActivityImpl::class.java)

    override fun diagnose(finding: DocsTruthFinding, contextMetrics: Map<String, Double>): DocsTruthFinding =
        runBlocking {
            log.infof("Diagnosing finding %s (%s) for %s", finding.id, finding.checkType, finding.component)
            val rootCause = llm.diagnose(finding, contextMetrics)
            val diagnosed = finding.copy(
                rootCause = rootCause,
                status = FindingStatus.DIAGNOSED,
                diagnosedAt = Instant.now(),
            )
            findingRepository.save(diagnosed)
        }

    // An ADR-status-vs-code drift is almost always a human triage decision (whether the ADR's
    // remaining prose, not just its status line, is still accurate) — so the LLM's fix-diff
    // (openProposalPr) path is the rare exception, reserved for the one narrow case where flipping
    // just the Delivery-Status: line is unambiguous; every other finding falls through to a
    // tracking ticket (ADR-0166 Decision).
    override fun propose(finding: DocsTruthFinding): DocsTruthFinding = runBlocking {
        log.infof(
            "Proposing disposition for finding %s (%s) on %s",
            finding.id,
            finding.checkType,
            finding.component,
        )
        val rootCause = finding.rootCause
            ?: error("Cannot propose without a diagnosis for finding ${finding.id}")
        val fixDiff = llm.proposeFixDiff(finding, rootCause)
        val proposed = if (fixDiff != null) {
            val prUrl = githubProposal.openProposalPr(finding, fixDiff)
            finding.copy(
                proposedFixDiff = fixDiff,
                proposalUrl = prUrl,
                status = FindingStatus.PROPOSED,
                proposedAt = Instant.now(),
            )
        } else {
            val ticketUrl = githubProposal.openTicket(finding, rootCause)
            finding.copy(
                proposalUrl = ticketUrl,
                status = FindingStatus.PROPOSED,
                proposedAt = Instant.now(),
            )
        }
        findingRepository.update(proposed)
    }
}
