// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Response.Status

/** Shared by discovery and authorization, so discovery cannot advertise an unwired vendor. */
internal interface HostedOAuthRuntime {
    val enabledVendors: Set<Vendor>
    val start: HostedOAuthStart
    val complete: HostedOAuthComplete
    val grants: HostedVendorGrants
    val dispatch: HostedMcpDispatch
}

@Path("/.well-known/oauth-authorization-server/oauth/hosted")
@Produces(MediaType.APPLICATION_JSON)
internal class HostedOAuthMetadataResource(
    private val request: HttpServerRequest,
    private val runtime: Instance<HostedOAuthRuntime>,
) {
    @GET
    fun metadata(): Response {
        val enabled = if (request.isSSL && runtime.isResolvable) runtime.get().enabledVendors else emptySet()
        val document = HostedOAuthMetadata.document(enabled) ?: return Response.status(Status.NOT_FOUND).build()
        return Response.ok(document).header("Cache-Control", "no-store").build()
    }
}
