// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.mcp.infrastructure.customer.CustomerChannel
import com.openbank.mcp.infrastructure.oauth.HostedMcpDispatch.Outcome
import com.openbank.mcp.infrastructure.oauth.HostedOAuthRuntime
import com.openbank.mcp.infrastructure.oauth.HostedOpaqueCredential
import io.smallrye.common.annotation.Blocking
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Response.Status
import kotlinx.coroutines.runBlocking

/**
 * The customer MCP channel's own path root (#16847, owner decision 2026-11-21, option b).
 *
 * - Served ONLY on the mTLS listener (8443): a plain-HTTP request (8150) answers 404, so a customer
 *   request can only arrive through the edge hop, and the DPoP `htu` the customers tenant compares
 *   with `absoluteURI()` is necessarily `https://<external Host>/customer/mcp`.
 * - The customer OIDC mechanism selects the customers-realm tenant for the presented OAuth scheme.
 *   The Gateway certificate proves the TLS peer identity and is separate from user authentication.
 * - 404 while the customer channel is off: nothing serves a channel that admits no one.
 *
 * The JSON-RPC handling itself is [McpEndpoint]'s: this resource calls [McpEndpoint.handleCustomer], the
 * only entry that runs the customer branch; the staff `@POST` never does, whatever the raw path.
 */
// Literal, not McpEndpoint.CUSTOMER_ROOT: the openapi-route-conformance gate reads @Path as source text.
@Path("/customer/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class CustomerMcpEndpoint(private val endpoint: McpEndpoint) {

    @Inject
    lateinit var channel: CustomerChannel

    @Inject
    lateinit var httpRequest: HttpServerRequest

    @Inject
    internal lateinit var hosted: Instance<HostedOAuthRuntime>

    @POST
    @Blocking
    fun handle(body: JsonNode): Response {
        val admission = channel.admission?.takeIf { httpRequest.isSSL }
            ?: return Response.status(Status.NOT_FOUND).build()
        val token = HostedOpaqueCredential.extract(httpRequest.headers().getAll("Authorization"))
            ?: return endpoint.handleCustomer(body)
        val challenge = admission.resourceMetadataUrl
        val runtime = hosted.takeIf { it.isResolvable }?.get()?.takeIf { it.enabledVendors.isNotEmpty() }
            ?: return invalidHostedToken(challenge)
        val versions = httpRequest.headers().getAll("Mcp-Protocol-Version")
        if (versions.size > 1) return Response.status(Status.BAD_REQUEST).build()
        val outcome = runBlocking { runtime.dispatch.dispatch(token, body, versions.singleOrNull()) }
        return when (outcome) {
            is Outcome.Forwarded -> Response.status(outcome.reply.status).entity(outcome.reply.body).build()
            Outcome.Refused.INVALID_TOKEN -> invalidHostedToken(challenge)
            Outcome.Refused.INVALID_REQUEST -> Response.status(Status.BAD_REQUEST).build()
            Outcome.Refused.OUTSIDE_SCOPE -> Response.status(Status.FORBIDDEN).build()
            Outcome.Refused.BANK_UNAVAILABLE -> Response.status(Status.BAD_GATEWAY).build()
        }
    }

    private fun invalidHostedToken(metadataUrl: String): Response = Response.status(Status.UNAUTHORIZED)
        .header("WWW-Authenticate", "Bearer error=\"invalid_token\", resource_metadata=\"$metadataUrl\"")
        .header("Cache-Control", "no-store")
        .build()
}
