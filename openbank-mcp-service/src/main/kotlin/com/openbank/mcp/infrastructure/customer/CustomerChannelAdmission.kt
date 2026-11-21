// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.openbank.libs.security.DpopCheck
import com.openbank.libs.security.DpopProofVerifier
import com.openbank.libs.security.DpopRequest
import com.openbank.libs.security.DpopResult
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.McpMetricsPort
import jakarta.json.JsonObject
import jakarta.json.JsonString
import org.eclipse.microprofile.jwt.JsonWebToken
import java.net.URI
import java.time.Clock

/**
 * Admission of one customer-realm call (ADR-0383 D1/D2, ADR-0385 D1). Pure apart from the replay
 * store behind [verifier]: the token has already been validated (signature, issuer, expiry) by the
 * customers OIDC tenant; this decides whether THIS server admits it.
 *
 * Order, cheapest and structural first, the DPoP replay claim last (inside [verifier]):
 * 1. the token's `iss` is the customers issuer — anything else never reaches this path;
 * 2. `aud` names this server;
 * 3. `azp` is a declared AND enabled client of the allowlist (tier read from the allowlist only);
 * 4. the token is sender-constrained: `cnf.jkt` is present and the scheme is `DPoP`;
 * 5. the DPoP proof verifies against the request (`htm`, `htu` = the published resource, `iat`
 *    window, `ath`, `cnf.jkt`, `jti` replay) via the ADR-0320 primitive.
 *
 * Tier H bearer tokens (ADR-0385's recorded exception) are NOT admitted in this slice: every
 * customer token must be DPoP-bound until that exception path is built and reviewed.
 */
class CustomerChannelAdmission(
    val issuer: String,
    val allowlist: McpClientAllowlist,
    private val verifier: DpopProofVerifier,
    private val clock: Clock,
) {
    /**
     * The three ADR-0383 D5.3 budgets. Each returns the refusal reason, or null when within budget,
     * and spends one slot when called. NONE is spent before the proof is shown to be signed by the
     * key the token is bound to (review of #16721): an unauthenticated flood, a header-less flood
     * or a junk proof carrying a victim's PUBLIC key therefore costs no one's budget. Raw volume
     * before that point is the edge's per-IP limit (D5.1), because nothing stateful happens yet.
     *
     * Order, after the stateless check: [proofKey] (the verified `cnf.jkt`), then [client] (a coarse
     * per-client cap, minute-only), then the replay claim, then [subject].
     */
    interface Throttles {
        fun proofKey(thumbprint: String): String?

        fun client(clientId: String): String?

        fun subject(subject: String, clientId: String): String?
    }

    sealed interface Outcome {
        /** [keyThumbprint] is the token's verified `cnf.jkt`: the third member of the D3 grant-lookup key. */
        data class Admitted(
            val context: ConsentContext,
            val client: McpClientAllowlist.Client,
            val keyThumbprint: String,
        ) : Outcome

        /** [status] is the HTTP status; [challenge] the `WWW-Authenticate` value (null for 429/503). */
        data class Refused(
            val status: Int,
            val challenge: String?,
            val reason: String,
            val throttled: Boolean = false,
        ) : Outcome
    }

    /** RFC 9728 metadata URL for [McpClientAllowlist.resource]: well-known prefix inserted before the path. */
    val resourceMetadataUrl: String = metadataUrlFor(allowlist.resource)

    /** True when [jwt]'s `iss` is the customers realm — the ONLY selector of this path (ADR-0383 D1.1). */
    fun isCustomerToken(jwt: JsonWebToken): Boolean = jwt.issuer != null && jwt.issuer == issuer

    /** The 401 for a request that carries no token at all (RFC 9728 §5.1, RFC 6750 §3.1: no error code). */
    fun missingTokenChallenge(): String = "DPoP algs=\"$ALGS\", resource_metadata=\"$resourceMetadataUrl\""

    /**
     * @param throttles the D5.3 budgets; see [Throttles] for which runs before and which after the
     *   DPoP check, and why.
     */
    @Suppress("ReturnCount") // each early return is one fail-closed admission gate, in order
    suspend fun admit(
        jwt: JsonWebToken,
        authorization: String?,
        dpopHeaders: List<String>,
        throttles: Throttles,
    ): Outcome {
        if (!isCustomerToken(jwt)) return invalidToken("token is not from the customers realm")
        val subject = jwt.subject?.takeIf { it.isNotBlank() } ?: return invalidToken("token has no subject")
        if (allowlist.audience !in audience(jwt)) return invalidToken("token audience does not include this server")
        val azp = jwt.getClaim<Any?>(CLAIM_AZP)?.let(::stringValue)
        val client = allowlist.admitted(azp) ?: return invalidToken("client is not admitted")
        val jkt = confirmationJkt(jwt) ?: return invalidToken("token is not DPoP-bound")
        val presented = dpopToken(authorization) ?: return invalidToken("DPoP authorization scheme required")
        // The proof's `ath` must be over the token Quarkus validated, not a different header value.
        val raw = jwt.rawToken
        if (raw != null && raw != presented) return invalidToken("presented token does not match the validated token")

        return admitProof(ProofRequest(subject, client, presented, jkt, dpopHeaders), throttles)
    }

    /** Everything the proof steps need, in one parameter. */
    private class ProofRequest(
        val subject: String,
        val client: McpClientAllowlist.Client,
        val presented: String,
        val jkt: String,
        val dpopHeaders: List<String>,
    )

    /**
     * Steps 2-5 of admission: the stateless proof check, the replay peek, the bound-proof budgets,
     * the replay claim and the subject window — in that order, see [Throttles].
     */
    @Suppress("ReturnCount") // each early return is one fail-closed step, in order
    private suspend fun admitProof(req: ProofRequest, throttles: Throttles): Outcome {
        val subject = req.subject
        val client = req.client
        val presented = req.presented
        val jkt = req.jkt
        val dpopHeaders = req.dpopHeaders
        val now = clock.instant()
        val checked = verifier.check(DpopRequest(dpopHeaders, HTTP_METHOD, allowlist.resource, presented, jkt), now)
        if (checked is DpopCheck.Rejected) return outcomeOf(checked.result, subject, client, jkt)
        val bound = checked as DpopCheck.Bound
        // A captured proof replayed on the victim's token passes check(); refuse it before any budget,
        // so replays cannot drain the victim's proof-key bucket. claim() below stays the authority.
        if (verifier.isReplayed(bound, now)) return outcomeOf(DpopResult.REPLAYED, subject, client, jkt)
        // Only a proof signed by the token's own key reaches here; budgets keyed on it are the holder's.
        (throttles.proofKey(bound.jkt) ?: throttles.client(client.clientId))?.let { return throttled(it) }
        val result = verifier.claim(bound, now)
        if (result == DpopResult.VALID) {
            throttles.subject(subject, client.clientId)?.let { return throttled(it) }
        }
        return outcomeOf(result, subject, client, jkt)
    }

    private fun throttled(reason: String) = Outcome.Refused(STATUS_TOO_MANY, null, reason, throttled = true)

    private fun outcomeOf(
        result: DpopResult,
        subject: String,
        client: McpClientAllowlist.Client,
        jkt: String,
    ): Outcome = when (result) {
        DpopResult.VALID -> Outcome.Admitted(
            ConsentContext(
                agentId = subject,
                consentId = "",
                grantedAccounts = emptyList(),
                actChain = listOf(client.clientId),
                sessionId = null,
                principalType = PRINCIPAL_HUMAN,
                roles = emptyList(),
                serviceAccount = false,
                channel = CHANNEL,
            ),
            client,
            jkt,
        )
        DpopResult.JKT_MISMATCH -> invalidToken("proof key is not the token's key")
        DpopResult.RATE_LIMITED -> Outcome.Refused(STATUS_TOO_MANY, null, "dpop proof quota exceeded")
        DpopResult.REPLAY_STORE_UNAVAILABLE -> Outcome.Refused(
            STATUS_UNAVAILABLE,
            null,
            "dpop replay store unavailable",
        )
        else -> Outcome.Refused(
            STATUS_UNAUTHORIZED,
            challenge("invalid_dpop_proof", "dpop proof rejected"),
            "dpop proof rejected: $result",
        )
    }

    private fun invalidToken(reason: String) =
        Outcome.Refused(STATUS_UNAUTHORIZED, challenge("invalid_token", reason), reason)

    private fun challenge(error: String, description: String) =
        "DPoP error=\"$error\", error_description=\"$description\", algs=\"$ALGS\", " +
            "resource_metadata=\"$resourceMetadataUrl\""

    companion object {
        val CHANNEL = McpMetricsPort.McpChannel.CUSTOMER

        /**
         * The channel as the PDP sees it. `mcp_rest_ext.rego` matches the literal string
         * `"mcp-customer"`; [CHANNEL] is the metrics enum and would serialise as `CUSTOMER`, which
         * the rule never matches, so every customer tool call would fall through to the default.
         */
        const val PDP_CHANNEL = "mcp-customer"
        private const val PRINCIPAL_HUMAN = "HUMAN"
        private const val HTTP_METHOD = "POST"
        private const val CLAIM_AZP = "azp"
        private const val CLAIM_AUD = "aud"
        private const val CLAIM_CNF = "cnf"
        private const val STATUS_UNAUTHORIZED = 401
        private const val STATUS_TOO_MANY = 429
        private const val STATUS_UNAVAILABLE = 503
        private const val DPOP_SCHEME = "DPoP "
        const val WELL_KNOWN = "/.well-known/oauth-protected-resource"
        val ALGS: String = DpopProofVerifier.ALLOWED_ALGORITHMS.sorted().joinToString(" ")

        /** The limiter key of one (subject, client) pair on this channel. */
        fun throttleKey(subject: String, clientId: String): String = "customer:$subject|$clientId"

        /** The limiter key of one DPoP proof key (unverified thumbprint). */
        fun proofKeyThrottleKey(thumbprint: String): String = "customer-dpop-jkt:$thumbprint"

        /** The limiter key of one client's shared pre-verify cap. */
        fun clientThrottleKey(clientId: String): String = "customer-client:$clientId"

        fun metadataUrlFor(resource: String): String {
            val uri = URI(resource)
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath?.takeIf { it != "/" } ?: ""
            return "${uri.scheme}://${uri.host}$port$WELL_KNOWN$path"
        }

        /** `Authorization: DPoP <token>` only (RFC 9449 §7.1); the scheme is case-insensitive. */
        fun dpopToken(authorization: String?): String? {
            val value = authorization?.trim() ?: return null
            if (!value.regionMatches(0, DPOP_SCHEME, 0, DPOP_SCHEME.length, ignoreCase = true)) return null
            return value.substring(DPOP_SCHEME.length).trim().takeIf { it.isNotEmpty() && ' ' !in it }
        }

        private fun stringValue(v: Any?): String? = when (v) {
            is String -> v
            is JsonString -> v.string
            else -> null
        }?.takeIf { it.isNotBlank() }

        private fun audience(jwt: JsonWebToken): List<String> = when (val aud = jwt.getClaim<Any?>(CLAIM_AUD)) {
            is String -> listOf(aud)
            is JsonString -> listOf(aud.string)
            is Iterable<*> -> aud.mapNotNull(::stringValue)
            else -> emptyList()
        }

        private fun confirmationJkt(jwt: JsonWebToken): String? = when (val cnf = jwt.getClaim<Any?>(CLAIM_CNF)) {
            is JsonObject -> stringValue(cnf["jkt"])
            is Map<*, *> -> stringValue(cnf["jkt"])
            else -> null
        }
    }
}
