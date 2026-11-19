// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.libs.security.DpopProofVerifier
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * RFC 9728 OAuth 2.0 Protected Resource Metadata for the customer MCP channel (ADR-0385 D1
 * "Discovery"). Served at the root well-known path and at the path-suffixed form RFC 9728 §3.1
 * derives for a resource with a path (`/mcp`). 404 while the channel is off: nothing advertises a
 * channel that does not admit anyone.
 *
 * Every value comes from the allowlist and the configured issuer — never from the request, so a
 * forged `Host` header cannot redirect a client to another authorization server.
 */
// Literal, not CustomerChannelAdmission.WELL_KNOWN: the openapi-route-conformance gate reads
// @Path arguments as source text and cannot resolve a constant. The IT pins the equality.
@Path("/.well-known/oauth-protected-resource")
@Produces(MediaType.APPLICATION_JSON)
class ProtectedResourceMetadataResource(private val channel: CustomerChannel) {

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
    @Path("/mcp")
    fun forMcp(): Response = metadata()

    private fun metadata(): Response {
        val admission = channel.admission ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(
            Metadata(
                resource = admission.allowlist.resource,
                authorizationServers = listOf(admission.issuer),
                bearerMethodsSupported = listOf("header"),
                dpopSigningAlgValuesSupported = DpopProofVerifier.ALLOWED_ALGORITHMS.sorted(),
                dpopBoundAccessTokensRequired = true,
            ),
        ).build()
    }
}
