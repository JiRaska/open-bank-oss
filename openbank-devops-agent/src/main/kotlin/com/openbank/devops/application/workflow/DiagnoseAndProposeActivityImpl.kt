// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.application.port.out.FindingRepository
import com.openbank.devops.application.port.out.LlmDiagnosisPort
import com.openbank.devops.application.port.out.RemediationProposalPort
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.FindingStatus
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Diagnose a finding (LLM) and, when the workflow decides to act, draft a durable remediation
 * PR/runbook/ticket for the HITL queue (ADR-0119). The agent NEVER merges or applies anything —
 * `gh.pr.merge`, `*.write`, `secrets.read.raw` are denied in the charter (agents.yaml); the
 * proposal is reviewed and merged by a human, exactly the finops-agent regime (ADR-0112).
 */
@ApplicationScoped
open class DiagnoseAndProposeActivityImpl(
    private val llm: LlmDiagnosisPort,
    private val remediationProposal: RemediationProposalPort,
    private val findingRepository: FindingRepository,
) : DiagnoseAndProposeActivity {

    private val log = Logger.getLogger(DiagnoseAndProposeActivityImpl::class.java)

    override fun diagnose(finding: DevOpsFinding, contextSignals: Map<String, Double>): DevOpsFinding =
        runOnVertxContext {
            log.infof("Diagnosing finding %s (%s)", finding.id, finding.detector)
            val rootCause = llm.diagnose(finding, contextSignals)
            val diagnosed = finding.copy(
                rootCause = rootCause,
                status = FindingStatus.DIAGNOSED,
                diagnosedAt = Instant.now(),
            )
            findingRepository.save(diagnosed)
        }

    override fun propose(finding: DevOpsFinding): DevOpsFinding = runOnVertxContext {
        log.infof("Proposing remediation for finding %s (%s)", finding.id, finding.detector)
        val rootCause = finding.rootCause
            ?: error("Cannot propose without a diagnosis for finding ${finding.id}")
        val remediation = llm.proposeRemediation(finding, rootCause)
        if (remediation == null) {
            log.infof("No remediation proposed for finding %s; leaving as DIAGNOSED", finding.id)
            return@runOnVertxContext finding
        }
        val prUrl = remediationProposal.openProposalPr(finding, remediation)
        if (prUrl == null) {
            // No PR (token not seeded / API failed). Keep the proposal TEXT for the dashboard, but
            // stay DIAGNOSED — there is nothing for a human to approve yet.
            log.infof("No proposal PR opened for finding %s; recording remediation text, staying DIAGNOSED", finding.id)
            return@runOnVertxContext findingRepository.update(finding.copy(proposedRemediation = remediation))
        }
        val proposed = finding.copy(
            proposedRemediation = remediation,
            proposalPrUrl = prUrl,
            status = FindingStatus.PROPOSED,
            proposedAt = Instant.now(),
        )
        findingRepository.update(proposed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
