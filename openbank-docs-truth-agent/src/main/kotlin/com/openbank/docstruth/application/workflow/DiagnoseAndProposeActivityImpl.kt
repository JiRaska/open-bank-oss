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
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.time.Instant

// Unlike CollectRepoScanActivityImpl (which touches no DB and so stays on plain runBlocking), this
// activity persists through the Postgres-backed FindingRepository (ADR-0166). Reactive Panache
// resolves its session from a Vert.x duplicated context, and Temporal activity methods run on the
// SDK's own worker thread pool — never an event loop — so each call is bridged onto a Vert.x
// context here, matching devops-agent/release-steward.
@ApplicationScoped
open class DiagnoseAndProposeActivityImpl(
    private val llm: LlmDiagnosisPort,
    private val githubProposal: GitHubProposalPort,
    private val findingRepository: FindingRepository,
) : DiagnoseAndProposeActivity {

    private val log = Logger.getLogger(DiagnoseAndProposeActivityImpl::class.java)

    override fun diagnose(finding: DocsTruthFinding, contextMetrics: Map<String, Double>): DocsTruthFinding =
        runOnVertxContext {
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
    override fun propose(finding: DocsTruthFinding): DocsTruthFinding = runOnVertxContext {
        log.infof(
            "Proposing disposition for finding %s (%s) on %s",
            finding.id,
            finding.checkType,
            finding.component,
        )
        val rootCause = finding.rootCause
            ?: error("Cannot propose without a diagnosis for finding ${finding.id}")
        val fixDiff = llm.proposeFixDiff(finding, rootCause)
        val prUrl = fixDiff?.let { githubProposal.openProposalPr(finding, it) }
        val proposalUrl = prUrl ?: githubProposal.openTicket(finding, rootCause)
        val proposed = if (proposalUrl != null) {
            finding.copy(
                proposedFixDiff = if (prUrl != null) fixDiff else finding.proposedFixDiff,
                proposalUrl = proposalUrl,
                status = FindingStatus.PROPOSED,
                proposedAt = Instant.now(),
            )
        } else {
            // NOTHING was created, so the finding must NOT read as proposed. It stays DIAGNOSED
            // with a null proposalUrl and a null proposedAt, which is what keeps it out of
            // DocsTruthWorkflowImpl's `findingsProposed` count and out of the HITL queue as a
            // delivered proposal. The predecessor of this branch returned a fabricated
            // `pending-docs-truth-agent-<id>` URL and moved the finding to PROPOSED — a no-op that
            // shared its shape with a real result (#5897).
            log.warnf(
                "No proposal was created for finding %s (%s) on %s — leaving it DIAGNOSED; " +
                    "nothing is awaiting a human.",
                finding.id,
                finding.checkType,
                finding.component,
            )
            finding.copy(status = FindingStatus.DIAGNOSED, proposalUrl = null, proposedAt = null)
        }
        findingRepository.update(proposed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
