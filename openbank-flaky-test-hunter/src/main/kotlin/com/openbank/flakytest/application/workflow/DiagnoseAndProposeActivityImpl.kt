// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.workflow

import com.openbank.flakytest.application.port.out.FindingRepository
import com.openbank.flakytest.application.port.out.GitHubProposalPort
import com.openbank.flakytest.application.port.out.LlmDiagnosisPort
import com.openbank.flakytest.domain.model.FindingStatus
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.time.Instant

/**
 * ADR-0031 D9 phase-3: every disposition this development agent reaches for a finding —
 * whether it results in a real GitHub PR or falls back to the ticket-only path — is recorded as
 * AI-attributed audit (D5) before returning. `actorType = AI_AGENT` and `policy_decision` mirror
 * the same envelope shape `AgentPolicyGate` uses for MCP tool calls; this activity is the
 * equivalent enforcement point for the Temporal-workflow action surface, since flaky-test-hunter
 * never calls MCP tools directly.
 */
@ApplicationScoped
open class DiagnoseAndProposeActivityImpl(
    private val llm: LlmDiagnosisPort,
    private val githubProposal: GitHubProposalPort,
    private val findingRepository: FindingRepository,
    private val auditPublisher: AuditEventPublisher,
    private val config: FlakyTestHunterConfig,
) : DiagnoseAndProposeActivity {

    private val log = Logger.getLogger(DiagnoseAndProposeActivityImpl::class.java)

    override fun diagnose(finding: FlakyTestFinding, contextMetrics: Map<String, Double>): FlakyTestFinding =
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

    // A flaky or silently-skipped test is a human triage decision except for the narrow phase-3
    // mechanical repair described by LlmDiagnosisPort. A failed or unconfigured PR attempt falls
    // back to the ticket path; it never fabricates a pending-PR URL.
    override fun propose(finding: FlakyTestFinding): FlakyTestFinding = runOnVertxContext {
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
        val proposed = if (prUrl != null) {
            finding.copy(
                proposedFixDiff = fixDiff,
                proposalUrl = prUrl,
                status = FindingStatus.PROPOSED,
                proposedAt = Instant.now(),
            )
        } else {
            finding.copy(
                proposalUrl = githubProposal.openTicket(finding, rootCause),
                status = FindingStatus.PROPOSED,
                proposedAt = Instant.now(),
            )
        }
        auditProposal(finding, fixDiff, proposed.proposalUrl)
        findingRepository.update(proposed)
    }

    /**
     * One AI-attributed [AuditEvent] per disposition, regardless of outcome — a refusal is exactly
     * as auditable as an opened PR (D5, ADR-0031). `policy_decision` is `ALLOW` only when this
     * capability's own fail-closed write path (`GitHubProposalPort.openProposalPr`) actually
     * produced a PR; every other outcome — no mechanical fix, a missing/invalid token, an
     * ineligible finding, or a GitHub API failure — is `DENY` and falls back to the ticket-only
     * path. No human approver is known yet at proposal time (that is [FindingStatus.APPROVED],
     * recorded separately once a human disposes the finding through the HITL queue).
     */
    private suspend fun auditProposal(finding: FlakyTestFinding, fixDiff: String?, proposalUrl: String?) {
        val prOpened = fixDiff != null && proposalUrl != null
        auditPublisher.publish(
            AuditEvent(
                actorId = AGENT_ID,
                actorType = "AI_AGENT",
                operation = if (prOpened) "$AGENT_ID.github.pr_opened" else "$AGENT_ID.github.ticket_opened",
                resourceType = "flaky_test_finding",
                resourceId = finding.id,
                result = if (proposalUrl != null) AuditResult.SUCCESS else AuditResult.DENIED,
                payload = mapOf(
                    "model_id" to config.modelId(),
                    "flaky_test_check_type_impacted" to finding.checkType.name,
                    "policy_decision" to if (prOpened) "ALLOW" else "DENY",
                    "proposal_url" to proposalUrl,
                    "human_approved_by" to null,
                ),
            ),
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private companion object {
        const val AGENT_ID = "flaky-test-hunter"
    }
}
