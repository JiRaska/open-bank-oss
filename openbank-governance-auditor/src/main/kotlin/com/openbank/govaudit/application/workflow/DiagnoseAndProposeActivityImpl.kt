// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.workflow

import com.openbank.govaudit.application.port.out.FindingRepository
import com.openbank.govaudit.application.port.out.GitHubProposalPort
import com.openbank.govaudit.application.port.out.LlmDiagnosisPort
import com.openbank.govaudit.domain.model.FindingStatus
import com.openbank.govaudit.domain.model.GovernanceFinding
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.time.Instant

@ApplicationScoped
open class DiagnoseAndProposeActivityImpl(
    private val llm: LlmDiagnosisPort,
    private val githubProposal: GitHubProposalPort,
    private val findingRepository: FindingRepository,
) : DiagnoseAndProposeActivity {

    private val log = Logger.getLogger(DiagnoseAndProposeActivityImpl::class.java)

    override fun diagnose(finding: GovernanceFinding, contextMetrics: Map<String, Double>): GovernanceFinding =
        runOnVertxContext {
            log.infof("Diagnosing finding %s (%s) for PR #%d", finding.id, finding.checkType, finding.prNumber)
            val rootCause = llm.diagnose(finding, contextMetrics)
            val diagnosed = finding.copy(
                rootCause = rootCause,
                status = FindingStatus.DIAGNOSED,
                diagnosedAt = Instant.now(),
            )
            findingRepository.save(diagnosed)
        }

    // A governance violation on an already-merged PR is mostly a human-triage incident, not a
    // mechanical diff — so the LLM's fix-diff (openProposalPr) path is the exception, not the
    // default; when it returns null this falls through to a tracking ticket (ADR-0164 Decision).
    override fun propose(finding: GovernanceFinding): GovernanceFinding = runOnVertxContext {
        log.infof(
            "Proposing disposition for finding %s (%s) on PR #%d",
            finding.id,
            finding.checkType,
            finding.prNumber,
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
            // GovernanceAuditorWorkflowImpl's `findingsProposed` count and out of the HITL queue as
            // a delivered proposal. The predecessor of this branch returned a fabricated
            // `pending-governance-<id>` URL and moved the finding to PROPOSED — a no-op that shared
            // its shape with a real result (#5897).
            log.warnf(
                "No proposal was created for finding %s (%s) on PR #%d — leaving it DIAGNOSED; " +
                    "nothing is awaiting a human.",
                finding.id,
                finding.checkType,
                finding.prNumber,
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
