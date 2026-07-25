// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import com.openbank.mcp.application.port.out.ConsentContext
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Resolves the acting agent + presented PSD2 consent from the caller's validated OAuth 2.1 access
 * token (ADR-0195). This is the caller-authentication half of MCP phase 2 (BLOCKER #2206): the OPA
 * PDP must be asked about the *real* agent and the *real* consent, not the phase-1 placeholder.
 *
 * Returns `null` when no agent token is present — OIDC is still tenant-disabled, or the call is
 * anonymous — so [McpEndpoint] can fall back to the phase-1 placeholder identity WITHOUT this class
 * carrying that constant. That keeps the fallback (and the #2206 CI guard's `agent:mcp-anonymous`
 * anchor) in one place, `McpEndpoint`.
 *
 * The token's `sub` (shape `agent:<id>`, which the shared `AuthorizeInterceptor` classifies as
 * `AI_AGENT`) is the OPA principal id; the `consent_id` claim names the presented consent.
 * `grantedAccounts` is left empty here on purpose: it is populated by the real read ports via
 * consent-service `POST /consents/{id}/validate` (ADR-0195, the next step) — the only place the
 * tool's required `ConsentScope` and the target account are known, and the only source that honours
 * revoke/expire live. Account scope is never taken from the token.
 */
@ApplicationScoped
class CallerContextResolver(private val jwt: JsonWebToken) {

    /**
     * @return the caller's [ConsentContext] when an `agent:` bearer token is present, else `null`.
     * @throws IllegalStateException when an agent token is present but carries no `consent_id` — a
     *   malformed token must fail closed, never silently degrade to an unscoped read.
     */
    fun resolveOrNull(): ConsentContext? {
        val subject = jwt.subject?.takeIf { it.startsWith(AGENT_PREFIX) } ?: return null
        val consentId = jwt.getClaim<String?>(CLAIM_CONSENT_ID)?.takeIf { it.isNotBlank() }
            ?: error("agent token '$subject' carries no '$CLAIM_CONSENT_ID' claim")
        return ConsentContext(agentId = subject, consentId = consentId, grantedAccounts = emptyList())
    }

    private companion object {
        const val AGENT_PREFIX = "agent:"
        const val CLAIM_CONSENT_ID = "consent_id"
    }
}
