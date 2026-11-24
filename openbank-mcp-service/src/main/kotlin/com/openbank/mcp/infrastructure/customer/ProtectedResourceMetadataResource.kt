// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.libs.security.DpopProofVerifier
import io.vertx.core.http.HttpServerRequest
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * RFC 9728 OAuth 2.0 Protected Resource Metadata for the customer MCP channel (ADR-0385 D1
 * "Discovery"). Served at the root well-known path and at the path-suffixed form RFC 9728 §3.1
 * derives for the customer root (`/customer/mcp`, #16847). 404 while the channel is off: nothing
 * advertises a channel that does not admit anyone. 404 on plain HTTP too: like the channel itself, its
 * discovery is served only on the mTLS listener the edge reaches.
 *
 * Every value comes from the allowlist and the configured issuer — never from the request, so a
 * forged `Host` header cannot redirect a client to another authorization server.
 */
// Literal, not CustomerChannelAdmission.WELL_KNOWN: the openapi-route-conformance gate reads
// @Path arguments as source text and cannot resolve a constant. The IT pins the equality.
@Path("/.well-known/oauth-protected-resource")
@Produces(MediaType.APPLICATION_JSON)
class ProtectedResourceMetadataResource(private val channel: CustomerChannel) {

    @Inject
    lateinit var httpRequest: HttpServerRequest

    data class Metadata(
        val resource: String,
        @get:JsonProperty("authorization_servers") val authorizationServers: List<String>,
        @get:JsonProperty("bearer_methods_supported") val bearerMethodsSupported: List<String>,
        @get:JsonProperty("dpop_signing_alg_values_supported") val dpopSigningAlgValuesSupported: List<String>,
        @get:JsonProperty("dpop_bound_access_tokens_required") val dpopBoundAccessTokensRequired: Boolean,
    )

    @GET
    fun root(): Response = metadata()

    @GET
    @Path("/customer/mcp")
    fun forCustomerMcp(): Response = metadata()

    /** The pre-#16847 suffix, kept so the published contract does not break; same document. */
    @GET
    @Path("/mcp")
    fun forMcp(): Response = metadata()

    private fun metadata(): Response {
        val admission = channel.admission?.takeIf { httpRequest.isSSL }
            ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(
            Metadata(
                resource = admission.allowlist.resource,
                authorizationServers = listOf(admission.issuer),
                bearerMethodsSupported = listOf("header"),
                dpopSigningAlgValuesSupported = DpopProofVerifier.ALLOWED_ALGORITHMS.sorted(),
                dpopBoundAccessTokensRequired = !admission.supportsHostedBearer,
            ),
        ).build()
    }
}
