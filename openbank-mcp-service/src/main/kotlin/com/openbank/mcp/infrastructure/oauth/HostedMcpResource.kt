// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.JsonNode
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Response.Status

/** Separate opaque OAuth public resource. Bank-issued tokens continue to use /customer/mcp. */
@Path("/customer/hosted-mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
internal class HostedMcpResource(
    private val runtime: Instance<HostedOAuthRuntime>,
    private val request: HttpServerRequest,
) {
    @POST
    suspend fun handle(body: JsonNode): Response {
        val active = runtime.takeIf { request.isSSL && request.path() == HostedOAuthAdmission.RESOURCE_PATH }
            ?.takeIf { it.isResolvable }?.get()?.takeIf { it.enabledVendors.isNotEmpty() }
            ?: return Response.status(Status.NOT_FOUND).build()
        val headers = request.headers()
        val authorization = headers.getAll("Authorization")
        val token = HostedOpaqueCredential.extract(authorization)
            ?: return challenge(invalid = authorization.isNotEmpty())
        val versions = headers.getAll("Mcp-Protocol-Version")
        if (versions.size > 1) return Response.status(Status.BAD_REQUEST).build()
        return when (val outcome = active.dispatch.dispatch(token, body, versions.singleOrNull())) {
            is HostedMcpDispatch.Outcome.Forwarded ->
                Response.status(outcome.reply.status).entity(outcome.reply.body).build()
            HostedMcpDispatch.Outcome.Refused.INVALID_TOKEN -> challenge(invalid = true)
            HostedMcpDispatch.Outcome.Refused.INVALID_REQUEST -> Response.status(Status.BAD_REQUEST).build()
            HostedMcpDispatch.Outcome.Refused.OUTSIDE_SCOPE -> Response.status(Status.FORBIDDEN).build()
            HostedMcpDispatch.Outcome.Refused.BANK_UNAVAILABLE -> Response.status(Status.BAD_GATEWAY).build()
        }
    }

    private fun challenge(invalid: Boolean): Response {
        val error = if (invalid) "error=\"invalid_token\", " else ""
        val resource = "resource_metadata=\"${HostedOAuthAdmission.METADATA_URL}\""
        return Response.status(Status.UNAUTHORIZED)
            .header("WWW-Authenticate", "Bearer $error$resource")
            .header("Cache-Control", "no-store")
            .build()
    }
}
