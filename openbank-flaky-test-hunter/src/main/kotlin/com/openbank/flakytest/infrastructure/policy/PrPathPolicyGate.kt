// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.policy

import com.openbank.flakytest.application.port.out.GitHubProposalPort
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * ADR-0031 D9 phase 3: the policy pre-flight gate that every PR-opening disposition passes
 * through BEFORE `GitHubProposalPort.openProposalPr` may touch GitHub (branch creation
 * included — the gate runs at the workflow-activity seam, so a [PrPathDecision.Denied] vetoes
 * the entire operation, not just one HTTP call). Two layers, both deny-by-default:
 *
 *  1. [AgentCharterGrantVerifier] — in-process re-check of the packaged `agents.yaml` charter
 *     (no network dependency, the local fail-safe).
 *  2. [OpaAgentDecisionClient] — the OPA sidecar decision against the served governance bundle.
 *
 * Deliberate deviation from the agent-service MCP gate (which degrades to ADVISORY on a PDP
 * error): that gate fronts READ tools, where fail-closed would take a read-only assistant
 * offline over a sidecar blip and a wrong allow is a wrong answer. This gate fronts a WRITE
 * path that opens a real pull request under the agent's identity — an allow granted while the
 * PDP is down, slow, or spoofed is an irreversible code change, so ANY PDP connectivity,
 * HTTP, or parse error is a DENY with reason `pdp_unreachable`. The audit trail records both
 * layers' outcomes either way (D5).
 */
@ApplicationScoped
class PrPathPolicyGate @Inject constructor(
    private val config: FlakyTestHunterConfig,
    private val charterVerifier: AgentCharterGrantVerifier,
    private val opa: OpaAgentDecisionClient,
) {

    private val log = Logger.getLogger(PrPathPolicyGate::class.java)

    suspend fun decide(finding: FlakyTestFinding): PrPathDecision {
        if (!config.policyGate().enabled()) {
            log.warn("Policy gate disabled via config — PR path is NOT policy-gated (dev-only escape hatch)")
            return PrPathDecision.Allowed(GitHubProposalPort.prBranchFor(finding.id))
        }
        val branch = GitHubProposalPort.prBranchFor(finding.id)
        when (val charter = charterVerifier.checkPrOpenGrant()) {
            is CharterGrant.Denied -> {
                log.warnf("In-process charter check denied the PR path: %s", charter.reason)
                return PrPathDecision.Denied(charter.reason)
            }
            CharterGrant.Granted -> Unit
        }
        val opaDecision = try {
            opa.queryPrOpenDecision()
        } catch (ex: OpaGateException) {
            log.warnf("OPA sidecar decision failed — failing closed: %s", ex.message)
            return PrPathDecision.Denied(PrPathGateVocabulary.REASON_PDP_UNREACHABLE)
        }
        return when (opaDecision) {
            OpaAgentDecision.Allowed -> PrPathDecision.Allowed(branch)
            is OpaAgentDecision.Denied -> {
                log.warnf("OPA policy denied the PR path: %s", opaDecision.reason)
                PrPathDecision.Denied("${PrPathGateVocabulary.REASON_OPA_DENIED_PREFIX}: ${opaDecision.reason}")
            }
        }
    }
}
