// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.application.workflow

import com.openbank.finops.application.port.out.AnomalyRepository
import com.openbank.finops.application.port.out.GitHubProposalPort
import com.openbank.finops.application.port.out.LlmDiagnosisPort
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.CostAnomaly
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
    private val anomalyRepository: AnomalyRepository,
) : DiagnoseAndProposeActivity {

    private val log = Logger.getLogger(DiagnoseAndProposeActivityImpl::class.java)

    override fun diagnose(anomaly: CostAnomaly, contextMetrics: Map<String, Double>): CostAnomaly = runOnVertxContext {
        log.infof("Diagnosing anomaly %s (%s)", anomaly.id, anomaly.detector)
        val rootCause = llm.diagnose(anomaly, contextMetrics)
        val diagnosed = anomaly.copy(
            rootCause = rootCause,
            status = AnomalyStatus.DIAGNOSED,
            diagnosedAt = Instant.now(),
        )
        anomalyRepository.save(diagnosed)
    }

    override fun propose(anomaly: CostAnomaly): CostAnomaly = runOnVertxContext {
        log.infof("Proposing IaC fix for anomaly %s (%s)", anomaly.id, anomaly.detector)
        val rootCause = anomaly.rootCause
            ?: error("Cannot propose without a diagnosis for anomaly ${anomaly.id}")
        val iacDiff = llm.proposeIacFix(anomaly, rootCause)
        if (iacDiff == null) {
            log.infof("No IaC fix proposed for anomaly %s; leaving as DIAGNOSED", anomaly.id)
            return@runOnVertxContext anomaly
        }
        val prUrl = githubProposal.openProposalPr(anomaly, iacDiff)
        if (prUrl == null) {
            // NOTHING was created, so the anomaly must NOT read as proposed. It stays DIAGNOSED
            // with no proposalPrUrl, which is what keeps it out of FinOpsWorkflowImpl's
            // `anomaliesProposed` count and out of the HITL queue as a delivered proposal. The
            // predecessor of this branch returned a fabricated `pending-finops-<id>` URL and moved
            // the anomaly to PROPOSED — a no-op that shared its shape with a real result (#5897).
            log.warnf(
                "No proposal PR was created for anomaly %s (%s) — leaving it DIAGNOSED; nothing " +
                    "is awaiting a human.",
                anomaly.id,
                anomaly.detector,
            )
            return@runOnVertxContext anomaly
        }
        val proposed = anomaly.copy(
            proposedIacDiff = iacDiff,
            proposalPrUrl = prUrl,
            status = AnomalyStatus.PROPOSED,
            proposedAt = Instant.now(),
        )
        anomalyRepository.update(proposed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
