// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
