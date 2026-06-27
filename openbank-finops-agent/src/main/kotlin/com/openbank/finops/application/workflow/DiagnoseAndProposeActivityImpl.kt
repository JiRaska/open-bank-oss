// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
