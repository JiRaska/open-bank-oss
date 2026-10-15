// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.policy

import com.openbank.agentpolicy.AgentCharterGrantVerifier
import com.openbank.agentpolicy.AgentPolicyConfig
import com.openbank.agentpolicy.AgentPolicyGate
import com.openbank.agentpolicy.OpaAgentDecisionClient
import com.openbank.agentpolicy.PolicyDecision
import com.openbank.flakytest.application.port.out.GitHubProposalPort
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * ADR-0031 D9 phase 4: flaky-test-hunter's CDI adapter over the shared
 * `openbank-agent-policy` gate (phase 3 moved the two deny-by-default layers — the in-process
 * [AgentCharterGrantVerifier] and the [OpaAgentDecisionClient] sidecar query — into that
 * module, parameterised by [AgentPolicyConfig]). This class owns everything that is
 * service-specific and keeps the phase-3 public surface unchanged:
 *
 *  - the kill switch (`openbank.flaky-test-hunter.policy-gate.enabled`, dev-only escape hatch —
 *    when disabled the gate allows WITHOUT consulting the charter or the sidecar, exactly as
 *    phase 3 did),
 *  - the branch derivation ([GitHubProposalPort.prBranchFor]) that an [PrPathDecision.Allowed]
 *    carries for the audit trail,
 *  - the config mapping (OPA URL + timeout) that would not be portable to another service.
 *
 * The shared [AgentPolicyGate] itself has exactly one behaviour: charter check, then OPA query,
 * any PDP error fails closed with `pdp_unreachable` (see PolicyGate's KDoc — a Denied vetoes the
 * entire PR-opening operation, not just one HTTP call). The agent id / tool / resource / tier
 * binding is `AgentPolicyConfig.flakyTestHunter()` — the same values phase 3 hardcoded.
 */
@ApplicationScoped
class PrPathPolicyGate @Inject constructor(private val config: FlakyTestHunterConfig) {

    private val log = Logger.getLogger(PrPathPolicyGate::class.java)

    private val gate: AgentPolicyGate = run {
        val policyConfig = AgentPolicyConfig.flakyTestHunter()
        val gateConfig = config.policyGate()
        AgentPolicyGate(
            AgentCharterGrantVerifier(policyConfig),
            OpaAgentDecisionClient(
                opaBaseUrl = gateConfig.opaUrl(),
                timeout = gateConfig.timeout(),
                config = policyConfig,
            ),
        )
    }

    suspend fun decide(finding: FlakyTestFinding): PrPathDecision {
        if (!config.policyGate().enabled()) {
            log.warn("Policy gate disabled via config — PR path is NOT policy-gated (dev-only escape hatch)")
            return PrPathDecision.Allowed(GitHubProposalPort.prBranchFor(finding.id))
        }
        val branch = GitHubProposalPort.prBranchFor(finding.id)
        return when (val decision = gate.check(AgentPolicyConfig.flakyTestHunter().defaultInput())) {
            PolicyDecision.Allowed -> PrPathDecision.Allowed(branch)
            is PolicyDecision.Denied -> PrPathDecision.Denied(decision.reason)
        }
    }
}
