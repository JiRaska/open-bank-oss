// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * ADR-0031 D3 (interim, pre-SVID): binds WHICH agent identity an authenticated operator may assert
 * over the /mcp surface to that operator's verified Keycloak roles. Deny-by-default — a role with
 * no binding may assert no agent. This closes the privilege-selection gap where any authenticated
 * operator could set `X-Agent-Id` to a higher-privileged charter (e.g. a ROLE_OPERATOR asserting
 * `compliance-officer`) and inherit its tools. The short-TTL SPIFFE/SVID per run (D3b) later
 * replaces the header entirely; this binding remains a defense-in-depth backstop.
 *
 * Config (`agent.identity.role-bindings`): `ROLE_A=agent1,agent2;ROLE_B=*` — `*` is wildcard (any
 * agent). Parsed once at construction; no runtime YAML parsing.
 */
@ApplicationScoped
class AgentIdentityBinding(
    @ConfigProperty(name = "agent.identity.binding-enforced", defaultValue = "true")
    val enforced: Boolean,
    @ConfigProperty(name = "agent.identity.role-bindings", defaultValue = "ROLE_ADMIN=*")
    rawBindings: String,
) {
    private val bindings: Map<String, Set<String>> = parse(rawBindings)

    /** True if any of the caller's [roles] authorizes asserting [agentId]. */
    fun permits(roles: Set<String>, agentId: String): Boolean =
        roles.any { role -> bindings[role]?.let { it.contains(WILDCARD) || it.contains(agentId) } == true }

    private fun parse(raw: String): Map<String, Set<String>> = raw.split(';').mapNotNull { entry ->
        val parts = entry.split('=', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val role = parts[0].trim()
        val agents = parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (role.isEmpty() || agents.isEmpty()) null else role to agents
    }.toMap()

    private companion object {
        const val WILDCARD = "*"
    }
}
