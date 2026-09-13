// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.infrastructure.config.CaseCoordinatorConfig
import jakarta.enterprise.context.ApplicationScoped

/**
 * In-process case capability gate (ADR-0244 D2/D3/D9), deny-by-default. Mirrors the charter's
 * `case_capabilities` in `agents.yaml`: only `case-coordinator` holds `case.open` /
 * `case.coordinate` / `case.synthesize` / `case.preempt`; swarm participants hold join/contribute
 * by being on the chartered roster. This is the fail-safe in-process layer (same role as
 * AgentPolicyGate in agent-service); the OPA bundle adapter that evaluates the same decisions
 * against `case.capabilities` input is Phase 4 scope.
 */
@ApplicationScoped
class CaseCapabilityGate(private val config: CaseCoordinatorConfig) {

    /**
     * Which agent identities each verified role may act as, parsed once from
     * `openbank.case-coordinator.case.identity-bindings`. Deny-by-default and no wildcard: an
     * unparseable entry, an unknown role or an empty agent list all yield "assert nothing".
     */
    private val identityBindings: Map<String, Set<String>> = parseBindings(config.case().identityBindings())

    /**
     * True when a caller holding [roles] is permitted to ACT AS [agentId] (#4834).
     *
     * Every other method on this gate answers "does this agent identity hold this capability".
     * None of them can answer "is the caller that agent", and before this existed nothing did —
     * the identity came from the request body, so the capability decisions ran on a claim. This
     * is the missing half: prove the caller first (the bearer, enforced by `@RolesAllowed`), then
     * decide which identity that proof entitles it to assert. Same separation `McpEndpoint`
     * documents for `X-Agent-Id`.
     *
     * Not a general authorisation primitive and deliberately not modelled as one: it is a
     * role-to-identity binding local to this service's two write endpoints, and ADR-0244 D9's own
     * answer — the same OPA gate every contribution passes — replaces it rather than layering on
     * it. This closes the claim-versus-proof hole in the meantime.
     */
    fun permitsAssertedIdentity(roles: Set<String>, agentId: String): Boolean =
        roles.any { role -> identityBindings[role]?.contains(agentId) == true }

    fun canOpenCase(agentId: String): Boolean = agentId in config.case().openAgents()

    fun canJoinCase(agentId: String): Boolean = agentId in config.case().swarmAgents()

    fun canContribute(agentId: String): Boolean = agentId in config.case().swarmAgents()

    fun canPreempt(agentId: String): Boolean = agentId in config.case().openAgents()

    fun canRequestSynthesis(agentId: String): Boolean = agentId in config.case().openAgents()

    private fun parseBindings(raw: String): Map<String, Set<String>> = raw.split(';').mapNotNull { entry ->
        val parts = entry.split('=', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val role = parts[0].trim()
        val agents = parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (role.isEmpty() || agents.isEmpty()) null else role to agents
    }.toMap()
}
