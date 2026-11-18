// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.web.SyntheticTaintExternalBoundary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.Base64
import java.util.Optional
import kotlin.coroutines.cancellation.CancellationException

/**
 * The downstream services a customer read may reach, each with the ONE read scope mcp-service may
 * ask the customers realm for on its behalf (ADR-0383 D4). The realm side — requester client,
 * audience clients and these scopes — is derived from `mcp-client-allowlist.yaml` `exchange:` by
 * `gen-mcp-realm-clients.py`, and the parity gate holds the two to each other.
 */
enum class DownstreamAudience(val clientId: String, val scope: String) {
    BALANCE("openbank-balance-service", "mcp-customer-balance-read"),
    TRANSACTION("openbank-transaction-service", "mcp-customer-transaction-read"),
}

/**
 * RFC 8693 exchange of the customer's own access token for a token scoped to ONE downstream
 * service and ONE read scope. Never a staff or service token: the customer path has no other
 * credential to fall back on, so any failure here is a refusal ([TokenExchangeRefused]).
 */
interface CustomerTokenExchange {
    fun exchange(subjectToken: String, subject: String, audience: DownstreamAudience): String
}

class TokenExchangeRefused(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The customers-realm token endpoint (`{realm}/protocol/openid-connect/token`). */
@RegisterRestClient(configKey = "customer-token-exchange")
@SyntheticTaintExternalBoundary("Keycloak token endpoint (identity provider), not a banking service edge")
@Path("/protocol/openid-connect/token")
interface CustomerTokenEndpointClient {
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Suppress("LongParameterList") // the RFC 8693 form, one parameter per field
    fun exchange(
        @FormParam("grant_type") grantType: String,
        @FormParam("client_id") clientId: String,
        @FormParam("client_secret") clientSecret: String,
        @FormParam("subject_token") subjectToken: String,
        @FormParam("subject_token_type") subjectTokenType: String,
        @FormParam("requested_token_type") requestedTokenType: String,
        @FormParam("audience") audience: String,
        @FormParam("scope") scope: String,
    ): JsonNode
}

/**
 * Keycloak standard token exchange (V2) against the customers realm. The answer is checked before
 * use, because a misconfigured realm is the likeliest defect and each check below is a way it would
 * otherwise widen what the token can do:
 * - `expires_in` present and at most [MAX_LIFETIME_SECONDS] (ADR-0383 D4: <= 60 s);
 * - the token's `sub` is the customer's — never a different principal;
 * - its `aud` names the requested downstream service.
 * The claims are read without a signature check: the token came from the issuer over the channel
 * mcp-service already trusts for it, and the downstream service verifies the signature itself.
 */
@ApplicationScoped
class KeycloakCustomerTokenExchange @Inject constructor(
    @RestClient private val endpoint: CustomerTokenEndpointClient,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "mcp.customer.exchange.client-id", defaultValue = "openbank-mcp-service")
    private val clientId: String,
    @ConfigProperty(name = "mcp.customer.exchange.client-secret") private val clientSecret: Optional<String>,
) : CustomerTokenExchange {

    @Suppress("TooGenericExceptionCaught") // any endpoint failure is the same refusal
    override fun exchange(subjectToken: String, subject: String, audience: DownstreamAudience): String {
        val secret = clientSecret.filter { it.isNotBlank() }
            .orElseThrow { TokenExchangeRefused("token exchange client secret is not configured") }
        val answer = try {
            endpoint.exchange(
                GRANT_TYPE,
                clientId,
                secret,
                subjectToken,
                ACCESS_TOKEN,
                ACCESS_TOKEN,
                audience.clientId,
                audience.scope,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TokenExchangeRefused("token exchange failed: ${e.javaClass.simpleName}", e)
        }
        return validated(answer, subject, audience)
    }

    internal fun validated(answer: JsonNode, subject: String, audience: DownstreamAudience): String {
        val token = answer.path("access_token").takeIf { it.isTextual }?.asText().orEmpty()
        refuseUnless(token.isNotBlank(), "token exchange returned no access token")
        val lifetime = answer.path("expires_in")
        refuseUnless(
            lifetime.canConvertToLong() && lifetime.asLong() in 1..MAX_LIFETIME_SECONDS,
            "exchanged token lifetime must be 1..$MAX_LIFETIME_SECONDS s",
        )
        val claims = claimsOf(token)
        refuseUnless(claims.path("sub").asText(null) == subject, "exchanged token is not the customer's")
        val aud = claims.path("aud")
        val audiences = if (aud.isArray) aud.map { it.asText() } else listOf(aud.asText(""))
        refuseUnless(audience.clientId in audiences, "exchanged token is not for ${audience.clientId}")
        return token
    }

    private fun refuseUnless(condition: Boolean, reason: String) {
        if (!condition) throw TokenExchangeRefused(reason)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun claimsOf(token: String): JsonNode = try {
        val payload = token.split('.').takeIf { it.size == JWS_PARTS }?.get(1)
            ?: throw TokenExchangeRefused("exchanged token is not a JWS")
        mapper.readTree(Base64.getUrlDecoder().decode(payload))
    } catch (e: TokenExchangeRefused) {
        throw e
    } catch (e: Exception) {
        throw TokenExchangeRefused("exchanged token is unreadable", e)
    }

    companion object {
        const val MAX_LIFETIME_SECONDS = 60L
        private const val JWS_PARTS = 3
        private const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange"
        private const val ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token"
    }
}
