// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.mcp.infrastructure.customer.CustomerChannel
import io.vertx.core.http.HttpServerRequest
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

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

    @POST
    fun handle(body: JsonNode): Response = if (channel.admission == null || !httpRequest.isSSL) {
        Response.status(Response.Status.NOT_FOUND).build()
    } else {
        endpoint.handleCustomer(body)
    }
}
