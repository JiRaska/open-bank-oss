// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.McpMetricsPort
import com.openbank.mcp.infrastructure.persistence.AgentSessionRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.json.JsonString
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Clock
import java.time.Instant
import java.util.Optional

/**
 * Resolves the acting agent + presented PSD2 consent from the caller's validated OAuth 2.1 access
 * token (ADR-0195). This is the caller-authentication half of MCP phase 2 (BLOCKER #2206): the OPA
 * PDP must be asked about the *real* agent and the *real* consent, not the phase-1 placeholder.
 *
 * Returns `null` when no agent token is present — OIDC is still tenant-disabled, or the call is
 * anonymous. There is no longer any fallback identity behind that `null`: [McpEndpoint] audits the
 * failure as `unknown`/DENIED and answers "Authorization unavailable". The phase-1
 * `agent:mcp-anonymous` placeholder this KDoc used to describe as the fallback was removed by the
 * cutover itself, and a shared fallback must not come back — reintroducing one would collapse every
 * caller onto a single charter's grant, so `check-mcp-stub-ports-vs-caller-auth.sh` now fails on the
 * constant appearing anywhere in this service's code (#2401).
 *
 * The token's `sub` (shape `agent:<id>`, which the shared `AuthorizeInterceptor` classifies as
 * `AI_AGENT`) is the OPA principal id; the `consent_id` claim names the presented consent.
 * `grantedAccounts` is left empty here on purpose: it is populated by the real read ports via
 * consent-service `POST /consents/{id}/validate` (ADR-0195, the next step) — the only place the
 * tool's required `ConsentScope` and the target account are known, and the only source that honours
 * revoke/expire live. Account scope is never taken from the token.
 */
@ApplicationScoped
class CallerContextResolver @Inject constructor(
    private val jwt: JsonWebToken,
    @ConfigProperty(name = "mcp.obo.enabled", defaultValue = "false")
    private val oboEnabled: Boolean = false,
    private val sessions: AgentSessionRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * The customers-realm issuer (ADR-0383 D1). A token from it is NEVER resolved here — not as an
     * `agent:` token, not as OBO — whatever its `sub` or claims say; only the customer channel
     * branch admits it. Absent means no customers tenant is configured.
     */
    @ConfigProperty(name = "mcp.customer.issuer")
    private val customerIssuer: Optional<String>,
) {

    /** The validated token, for the customer-channel branch (which reads `iss`, `azp`, `cnf`). */
    val token: JsonWebToken get() = jwt

    /** True when the presented token was issued by the customers realm (ADR-0383 D1). */
    fun presentsCustomerRealmToken(): Boolean = isCustomerRealmToken()

    private fun isCustomerRealmToken(): Boolean {
        val iss = jwt.issuer ?: return false
        return customerIssuer.map { it.isNotBlank() && it == iss }.orElse(false)
    }

    /**
     * @return the caller's [ConsentContext] when an `agent:` bearer token is present — or, with
     *   `mcp.obo.enabled`, when a staff OBO token (ADR-0224) is present — else `null`.
     * @throws IllegalStateException when an agent token is present but carries no `consent_id` — a
     *   malformed token must fail closed, never silently degrade to an unscoped read.
     */
    suspend fun resolveOrNull(): ConsentContext? {
        val subject = jwt.subject ?: return null
        // ADR-0383 D1.1/D1.2: issuer selects the path BEFORE the subject is inspected.
        if (isCustomerRealmToken()) return null
        if (subject.startsWith(AGENT_PREFIX)) {
            // RFC 8707 / ADR-0383 D1: an agent token must be issued FOR this server. Checked here as
            // well as in McpEndpoint (which answers 401 first) so that a caller reaching the
            // resolver by any other route still fails closed (#16622).
            if (MCP_AUDIENCE !in audience()) return null
            val consentId = jwt.getClaim<String?>(CLAIM_CONSENT_ID)?.takeIf { it.isNotBlank() }
                ?: error("agent token '$subject' carries no '$CLAIM_CONSENT_ID' claim")
            return ConsentContext(
                agentId = subject,
                consentId = consentId,
                grantedAccounts = emptyList(),
                actChain = actChain(jwt.getClaim(CLAIM_ACT)),
                sessionId = jwt.getClaim<String?>(CLAIM_SESSION_ID)?.takeIf { it.isNotBlank() },
                channel = McpMetricsPort.McpChannel.AGENT,
            )
        }
        if (!oboEnabled) return null
        return resolveOboHuman(subject)
    }

    /**
     * True when a bearer token is present whose `aud` does not name this server (RFC 8707, ADR-0383
     * D1). Such a token was issued for some other resource; accepting it would be token
     * passthrough. No token at all (anonymous) is NOT foreign — that case keeps its existing
     * deny-without-401 behaviour.
     */
    fun presentsForeignAudience(): Boolean = jwt.subject != null && MCP_AUDIENCE !in audience()

    /**
     * ADR-0224 phase 1b: a staff token minted by RFC 8693 exchange at Keycloak. Recognized by four
     * marks, ALL required (any missing → anonymous, fail-closed): the actor (`azp` = requesting
     * client, standing in for the `act` chain until KC emits it), a session id (`sid`, the D2
     * session-binding anchor), the MCP audience client in `aud`, and at least one bounded realm
     * role. The token carries no consent — consent-scoped tools fail closed downstream; the PDP
     * decides by realm roles exactly as for a REST call (ADR-0034).
     *
     * Suspend (never wrapped in runBlocking here): the endpoint awaits it inside its own flat
     * runBlocking on the JAX-RS worker thread — no nested blocking bridge on the auth path.
     */
    private suspend fun resolveOboHuman(subject: String): ConsentContext? {
        if (!ServiceAccountIdentity.isInteractiveHumanToken(jwt)) return null
        val azp = jwt.getClaim<String?>(CLAIM_AZP)?.takeIf { it.isNotBlank() } ?: return null
        val jti = jwt.getClaim<String?>(CLAIM_JTI)?.takeIf { it.isNotBlank() } ?: return null
        if (MCP_AUDIENCE !in audience()) return null
        val roles = realmRoles()
        if (roles.isEmpty()) return null

        // ADR-0224 D2: the token's `jti` must be bound to a live session of THIS subject (never
        // the SSO `sid` — an exchange mints a fresh one, which is exactly why jti is the binding),
        // and the effective roles are bounded by BOTH the token and the session ceiling. A missing
        // store row or a mismatch is anonymous — revocation takes effect immediately.
        //
        // Matched on `sub`, which is what McpSessionResource now writes. It used to be the
        // REST-layer principal name (Quarkus resolves preferred_username before sub) on BOTH
        // sides — correct as a pair, but keyed on a claim a Keycloak admin can change. After a
        // rename no token could match the live row again: null here, anonymous downstream, and
        // under `mcp.obo.enabled` a fail-closed "Authorization unavailable" while the row still
        // reads live (#3182). `sub` is the identifier Keycloak guarantees is immutable.
        //
        // Both sides move together or the pair breaks in the other direction — that is what
        // #2955 did (reader only) and why E2E #2750 pins a real exchanged token end to end.
        val session = sessions?.findActiveByJti(jti, Instant.now(clock)) ?: return null
        if (session.subject != subject) return null
        // preferred_username stays the HUMAN-READABLE label on the audit trail — `sub` is a UUID
        // and reads as nothing in a log. It is deliberately not what the line above matches on:
        // display may drift with a rename, identity may not.
        val principalName = jwt.getClaim<String?>(CLAIM_PREFERRED_USERNAME)?.takeIf { it.isNotBlank() } ?: subject
        val ceiling = parseCeiling(session.roleCeiling)
        val bounded = roles.filter { it in ceiling }
        if (bounded.isEmpty()) return null

        return ConsentContext(
            agentId = principalName,
            consentId = "",
            grantedAccounts = emptyList(),
            actChain = listOf(azp) + actChain(jwt.getClaim(CLAIM_ACT)),
            sessionId = jti,
            principalType = "HUMAN",
            roles = bounded,
            serviceAccount = false,
            channel = McpMetricsPort.McpChannel.OPERATOR,
        )
    }

    private fun parseCeiling(roleCeiling: String): Set<String> = roleCeiling.removePrefix("[").removeSuffix("]")
        .split(",")
        .map { it.trim().removeSurrounding("\"") }
        .filter { it.isNotBlank() }
        .toSet()

    /** The `aud` claim is a single string or an array depending on the grant and the JWT library — accept every shape. */
    private fun audience(): List<String> = when (val aud = jwt.getClaim<Any?>(CLAIM_AUD)) {
        is String -> listOf(aud)
        is JsonString -> listOf(aud.string)
        // Covers a List, the Set Quarkus OIDC builds, and a jakarta.json JsonArray.
        is Iterable<*> -> aud.mapNotNull { (it as? String) ?: (it as? JsonString)?.string }
        else -> emptyList()
    }

    private fun realmRoles(): List<String> {
        val realmAccess = jwt.getClaim<Map<String, Any?>?>(CLAIM_REALM_ACCESS) ?: return emptyList()
        val roles = realmAccess["roles"] as? List<*> ?: return emptyList()
        return roles.filterIsInstance<String>().filter { it.startsWith(ROLE_PREFIX) }
    }

    /**
     * RFC 8693 `act` nesting → the ordered delegation chain (ADR-0224/0226). The claim is a JSON
     * object whose `sub` is the immediate actor and whose own `act` nests the next one; a bare
     * array of objects is tolerated for issuers that flatten. The walk stops at the first
     * malformed link and at [MAX_ACT_DEPTH]; anything unparseable yields an empty chain (a direct
     * action) rather than failing the call — audit enrichment must not deny.
     */
    private fun actChain(claim: Any?): List<String> = generateSequence(claim as? Map<*, *>) { it["act"] as? Map<*, *> }
        .take(MAX_ACT_DEPTH)
        .takeWhile { it["sub"]?.toString()?.isNotBlank() == true }
        .map { it["sub"].toString() }
        .toList()

    companion object {
        /** The audience client every token on `/mcp` must target (ADR-0224 D1, ADR-0385 D1). */
        const val MCP_AUDIENCE = "openbank-mcp-service"

        private const val AGENT_PREFIX = "agent:"
        private const val CLAIM_CONSENT_ID = "consent_id"
        private const val CLAIM_ACT = "act"
        private const val CLAIM_SESSION_ID = "sid"
        private const val CLAIM_AZP = "azp"
        private const val CLAIM_JTI = "jti"
        private const val CLAIM_AUD = "aud"
        private const val CLAIM_REALM_ACCESS = "realm_access"
        private const val CLAIM_PREFERRED_USERNAME = "preferred_username"
        private const val ROLE_PREFIX = "ROLE_"

        /** Delegation beyond this depth is an issuer bug, not a real chain — stop walking. */
        private const val MAX_ACT_DEPTH = 8
    }
}
