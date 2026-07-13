// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.workflow

import com.openbank.authzaudit.application.port.out.FindingRepository
import com.openbank.authzaudit.application.port.out.GitHubProposalPort
import com.openbank.authzaudit.application.port.out.LlmDiagnosisPort
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.domain.model.FindingStatus
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

    override fun diagnose(finding: AuthzPolicyFinding, contextMetrics: Map<String, Double>): AuthzPolicyFinding =
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

    // An authorization-policy defect is always a human triage decision (ADR-0167 Decision) — a
    // wrong auto-fix on a rego rule or a charter is a live security exposure, not a reviewable
    // convenience. proposeFixDiff always returns null in v1 (see LlmDiagnosisPort), so this falls
    // through to openTicket for every finding; the fixDiff branch stays wired for interface parity
    // with every sibling agent's DiagnoseAndProposeActivityImpl shape, not as a live PR path.
    override fun propose(finding: AuthzPolicyFinding): AuthzPolicyFinding = runOnVertxContext {
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
