// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.mcp

import jakarta.json.JsonString

/**
 * The calling agent, from the validated access token (RFC 8707 / ADR-0383 D1, same rule as
 * mcp-service's CallerContextResolver): the identity is the JWT `sub` — never the security
 * principal's display name — and the token must have been issued FOR this broker. A token minted
 * for any other resource server is refused, so a token stolen from (or legitimately held for) a
 * sibling service cannot be replayed here.
 */
object AgentCaller {
    const val AUDIENCE = "openbank-fetch-broker"
    private const val AGENT_PREFIX = "agent:"

    sealed interface Resolution {
        data class Agent(val agentId: String) : Resolution

        /** No subject at all, or a subject that is not an agent. */
        data object NotAnAgent : Resolution

        data object ForeignAudience : Resolution
    }

    fun resolve(subject: String?, audClaim: Any?): Resolution = when {
        subject.isNullOrBlank() -> Resolution.NotAnAgent
        AUDIENCE !in audiences(audClaim) -> Resolution.ForeignAudience
        !subject.startsWith(AGENT_PREFIX) || subject.length == AGENT_PREFIX.length -> Resolution.NotAnAgent
        else -> Resolution.Agent(subject)
    }

    fun audiences(aud: Any?): List<String> = when (aud) {
        is String -> listOf(aud)
        is JsonString -> listOf(aud.string)
        // A List, the Set Quarkus OIDC builds, or a jakarta.json JsonArray.
        is Iterable<*> -> aud.mapNotNull { (it as? String) ?: (it as? JsonString)?.string }
        else -> emptyList()
    }
}
