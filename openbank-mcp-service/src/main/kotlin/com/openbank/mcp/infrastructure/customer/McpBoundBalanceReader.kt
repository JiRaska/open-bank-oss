// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.mcp.infrastructure.customer

import com.openbank.libs.domain.identifiers.Ids
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.libs.web.SyntheticTaintExternalBoundary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.net.URI
import java.util.Base64
import java.util.Optional
import java.util.UUID

@RegisterRestClient(configKey = "mcp-bound-issuer")
@SyntheticTaintExternalBoundary("Keycloak token endpoint (identity provider), not a banking service edge")
@Path("/protocol/openid-connect/token")
interface McpBoundBalanceIssuer {
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    fun issue(
        @FormParam("grant_type") grantType: String,
        @FormParam("client_id") clientId: String,
        @FormParam("subject_token") subjectToken: String,
        @FormParam("resource_dpop_proof") resourceProof: String?,
        @FormParam("account_id") accountId: UUID,
        @FormParam("connection_id") connectionId: UUID?,
        @FormParam("hosted_read_id") hostedReadId: UUID? = null,
    ): JsonNode
}

@RegisterRestClient(configKey = "mcp-bound-balance")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/mcp-balances")
@Produces(MediaType.APPLICATION_JSON)
interface McpBoundBalanceEndpoint {
    @GET
    @Path("/{accountId}")
    fun read(@PathParam("accountId") accountId: UUID, @HeaderParam("Authorization") authorization: String): JsonNode
}

@ApplicationScoped
class McpBoundBalanceReader(
    @RestClient private val issuer: McpBoundBalanceIssuer,
    @RestClient private val balance: McpBoundBalanceEndpoint,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "mcp.customer.bound-balance.enabled", defaultValue = "false") val enabled: Boolean,
    @ConfigProperty(name = "quarkus.rest-client.mcp-bound-issuer.url") private val issuerUrl: URI,
    @ConfigProperty(name = "quarkus.rest-client.mcp-bound-balance.url") private val balanceUrl: URI,
    @ConfigProperty(name = "quarkus.rest-client.mcp-bound-issuer.tls-configuration-name")
    private val issuerTls: Optional<String>,
    @ConfigProperty(name = "quarkus.rest-client.mcp-bound-balance.tls-configuration-name")
    private val balanceTls: Optional<String>,
) {
    fun read(caller: CustomerReadTools.Caller, grant: CustomerGrant): JsonNode {
        refuseUnless(
            issuerUrl.scheme == "https" && balanceUrl.scheme == "https",
            "bound balance delegation requires HTTPS on both hops",
        )
        refuseUnless(
            issuerTls.isPresent && issuerTls == balanceTls,
            "bound balance delegation requires one shared client TLS configuration",
        )
        val key = caller.grantKey
        val connectionId = key?.connectionId
        val answer = if (key != null && key.keyThumbprint == null) {
            refuseUnless(caller.resourceProof == null, "hosted balance request cannot carry a DPoP proof")
            val boundConnection = connectionId ?: throw TokenExchangeRefused("hosted connection binding is unavailable")
            issuer.issue(GRANT_TYPE, CLIENT, caller.rawToken, null, grant.accountId, boundConnection, Ids.randomId())
        } else {
            val proof = caller.resourceProof ?: throw TokenExchangeRefused("verified resource proof is unavailable")
            issuer.issue(GRANT_TYPE, CLIENT, caller.rawToken, proof, grant.accountId, connectionId)
        }
        return readBalance(grant.accountId, validated(answer, caller.subject, grant, connectionId))
    }

    private fun readBalance(accountId: UUID, token: String): JsonNode = try {
        balance.read(accountId, "Bearer $token")
    } catch (e: WebApplicationException) {
        val status = e.response?.status
        if (status != null && status in REFUSAL_STATUSES) throw DownstreamRefused(status)
        throw e
    }

    internal fun validated(answer: JsonNode, subject: String, grant: CustomerGrant, connectionId: UUID?): String {
        val lifetime = answer.path("expires_in")
        val token = answer.path("access_token").asText("")
        refuseUnless(
            lifetime.isIntegralNumber && lifetime.canConvertToLong() && lifetime.asLong() in 1..MAX_LIFETIME_SECONDS,
            "invalid balance-read token response",
        )
        refuseUnless(
            !answer.has("refresh_token") && answer.path("token_type").asText("") == "Bearer",
            "unexpected balance-read token type",
        )
        refuseUnless(
            matchesClaims(decodeClaims(token), subject, grant, connectionId),
            "balance-read token binding does not match",
        )
        return token
    }

    private fun refuseUnless(condition: Boolean, reason: String) {
        if (!condition) throw TokenExchangeRefused(reason)
    }

    @Suppress("TooGenericExceptionCaught") // unreadable token material is refused without logging it
    private fun decodeClaims(token: String): JsonNode = try {
        require(token.length <= MAX_TOKEN_LENGTH)
        val parts = token.split('.')
        require(parts.size == JWT_PARTS)
        requireNotNull(mapper.readTree(Base64.getUrlDecoder().decode(parts[1])))
    } catch (_: Exception) {
        throw TokenExchangeRefused("invalid balance-read token claims")
    }

    private fun matchesClaims(claims: JsonNode, subject: String, grant: CustomerGrant, connectionId: UUID?): Boolean {
        val audience = claims.path("aud")
        val audiences = if (audience.isArray) audience.map { it.asText() } else listOf(audience.asText(""))
        val connection = claims.path(CustomerChannelAdmission.CLAIM_CONNECTION_ID).asText("")
        return audiences == listOf(DownstreamAudience.BALANCE.clientId) &&
            claims.path("scope").asText("") == DownstreamAudience.BALANCE.scope &&
            claims.path("sub").asText("") == subject &&
            claims.path("azp").asText("") == CLIENT &&
            claims.path("account_id").asText("") == grant.accountId.toString() &&
            claims.path("grant_id").asText("") == grant.grantId.toString() &&
            (connectionId == null || connection == connectionId.toString()) &&
            THUMBPRINT.matches(claims.path("cnf").path("x5t#S256").asText(""))
    }

    private companion object {
        const val GRANT_TYPE = "urn:openbank:oauth:grant-type:mcp-read-delegation"
        const val CLIENT = "openbank-mcp-read-delegation"
        const val MAX_LIFETIME_SECONDS = 60L
        const val MAX_TOKEN_LENGTH = 8192
        const val JWT_PARTS = 3
        val REFUSAL_STATUSES = setOf(
            Response.Status.UNAUTHORIZED.statusCode,
            Response.Status.FORBIDDEN.statusCode,
            Response.Status.NOT_FOUND.statusCode,
        )
        val THUMBPRINT = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
