// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.workflow

import com.openbank.releasesteward.application.port.out.FindingRepository
import com.openbank.releasesteward.application.port.out.GitHubProposalPort
import com.openbank.releasesteward.application.port.out.LlmDiagnosisPort
import com.openbank.releasesteward.domain.model.FindingStatus
import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
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

    override fun diagnose(finding: ReleaseStewardFinding, contextMetrics: Map<String, Double>): ReleaseStewardFinding =
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

    // A release/version-axis drift is usually a human triage decision (which manifest baseline is
    // correct, which side of an admin-ui sync is authoritative, which racing PR re-bumps) — so the
    // LLM's fix-diff (openProposalPr) path is the exception, reserved for the one mechanically
    // fixable case (APP_VERSION_OVERRIDE); every other check type falls through to a tracking
    // ticket (ADR-0165 Decision).
    override fun propose(finding: ReleaseStewardFinding): ReleaseStewardFinding = runOnVertxContext {
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
            // ReleaseStewardWorkflowImpl's `findingsProposed` count and out of the HITL queue as a
            // delivered proposal. The predecessor of this branch returned a fabricated
            // `pending-release-steward-<id>` URL and moved the finding to PROPOSED — a no-op that
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
