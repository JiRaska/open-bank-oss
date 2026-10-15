// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agentpolicy

import org.jboss.logging.Logger

/**
 * ADR-0031 D9 phase 4: the shared, two-layer, deny-by-default [PolicyGate] every agent write
 * path runs before touching the outside world. Generalised verbatim from phase 3's
 * flaky-test-hunter `PrPathPolicyGate`, with the agent identity, tool, resource and tier moved
 * into [AgentPolicyConfig]/[PolicyDecisionInput]:
 *
 *  1. [AgentCharterGrantVerifier] — in-process re-check of the packaged `agents.yaml` charter
 *     (no network dependency, the local fail-safe).
 *  2. [OpaAgentDecisionClient] — the OPA sidecar decision against the served governance bundle.
 *
 * A [PolicyDecision.Denied] from either layer vetoes the ENTIRE operation the gate fronts
 * (branch creation included), and ANY PDP connectivity, HTTP, or parse error fails closed as
 * [AgentPolicyVocabulary.REASON_PDP_UNREACHABLE] — see [PolicyGate] for why write-path gates
 * never degrade to advisory. Audit publishing deliberately stays in the consuming service
 * (phase-3 D5 contract): this module decides, the service records.
 *
 * The kill switch (config flag) also stays in the consuming service: it wraps this gate with
 * its service-specific escape hatch, so the shared gate has exactly one behaviour.
 */
class AgentPolicyGate(
    private val charterVerifier: AgentCharterGrantVerifier,
    private val opa: OpaAgentDecisionClient,
) : PolicyGate {

    private val log = Logger.getLogger(AgentPolicyGate::class.java)

    override suspend fun check(input: PolicyDecisionInput): PolicyDecision {
        when (val charter = charterVerifier.checkGrant(input)) {
            is CharterGrant.Denied -> {
                log.warnf("In-process charter check denied %s on %s: %s", input.tool, input.resource, charter.reason)
                return PolicyDecision.Denied(charter.reason)
            }
            CharterGrant.Granted -> Unit
        }
        val opaDecision = try {
            opa.queryDecision(input)
        } catch (ex: OpaGateException) {
            log.warnf("OPA sidecar decision failed — failing closed: %s", ex.message)
            return PolicyDecision.Denied(AgentPolicyVocabulary.REASON_PDP_UNREACHABLE)
        }
        return when (opaDecision) {
            OpaAgentDecision.Allowed -> PolicyDecision.Allowed
            is OpaAgentDecision.Denied -> {
                log.warnf("OPA policy denied %s on %s: %s", input.tool, input.resource, opaDecision.reason)
                PolicyDecision.Denied("${AgentPolicyVocabulary.REASON_OPA_DENIED_PREFIX}: ${opaDecision.reason}")
            }
        }
    }
}
