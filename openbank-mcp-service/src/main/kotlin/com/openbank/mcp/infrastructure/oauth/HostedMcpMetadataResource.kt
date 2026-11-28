// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/.well-known/oauth-protected-resource/customer/hosted-mcp")
@Produces(MediaType.APPLICATION_JSON)
internal class HostedMcpMetadataResource(
    private val runtime: Instance<HostedOAuthRuntime>,
    private val request: HttpServerRequest,
) {
    @GET
    fun metadata(): Response {
        if (!request.isSSL || request.path() != HostedOAuthAdmission.METADATA_PATH) return notFound()
        if (!runtime.isResolvable || runtime.get().enabledVendors.isEmpty()) return notFound()
        return Response.ok(
            mapOf(
                "resource" to HostedOAuthAdmission.RESOURCE,
                "authorization_servers" to listOf(HostedOAuthComplete.ISSUER),
                "bearer_methods_supported" to listOf("header"),
                "scopes_supported" to listOf(HostedOAuthAdmission.OUTER_SCOPE),
            ),
        ).build()
    }

    private fun notFound() = Response.status(Response.Status.NOT_FOUND).build()
}
