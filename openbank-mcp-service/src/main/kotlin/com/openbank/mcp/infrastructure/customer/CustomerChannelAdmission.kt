// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.openbank.libs.security.DpopProofVerifier
import com.openbank.libs.security.DpopRequest
import com.openbank.libs.security.DpopResult
import com.openbank.mcp.application.port.out.ConsentContext
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
    sealed interface Outcome {
        data class Admitted(val context: ConsentContext, val client: McpClientAllowlist.Client) : Outcome

        /** [status] is the HTTP status; [challenge] the `WWW-Authenticate` value (null for 429/503). */
        data class Refused(val status: Int, val challenge: String?, val reason: String) : Outcome
    }

    /** RFC 9728 metadata URL for [McpClientAllowlist.resource]: well-known prefix inserted before the path. */
    val resourceMetadataUrl: String = metadataUrlFor(allowlist.resource)

    /** True when [jwt]'s `iss` is the customers realm — the ONLY selector of this path (ADR-0383 D1.1). */
    fun isCustomerToken(jwt: JsonWebToken): Boolean = jwt.issuer != null && jwt.issuer == issuer

    /** The 401 for a request that carries no token at all (RFC 9728 §5.1, RFC 6750 §3.1: no error code). */
    fun missingTokenChallenge(): String = "DPoP algs=\"$ALGS\", resource_metadata=\"$resourceMetadataUrl\""

    @Suppress("ReturnCount") // each early return is one fail-closed admission gate, in order
    suspend fun admit(jwt: JsonWebToken, authorization: String?, dpopHeaders: List<String>): Outcome {
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

        val result = verifier.verify(
            DpopRequest(dpopHeaders, HTTP_METHOD, allowlist.resource, presented, jkt),
            clock.instant(),
        )
        return when (result) {
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
    }

    private fun invalidToken(reason: String) =
        Outcome.Refused(STATUS_UNAUTHORIZED, challenge("invalid_token", reason), reason)

    private fun challenge(error: String, description: String) =
        "DPoP error=\"$error\", error_description=\"$description\", algs=\"$ALGS\", " +
            "resource_metadata=\"$resourceMetadataUrl\""

    companion object {
        const val CHANNEL = "mcp-customer"
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
