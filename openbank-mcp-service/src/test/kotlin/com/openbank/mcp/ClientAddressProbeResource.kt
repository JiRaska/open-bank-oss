// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.mcp.infrastructure.mcp.ClientAddressResolver
import io.vertx.core.http.HttpServerRequest
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType

/**
 * Test-only probe (src/test, never shipped): answers the audit-window key McpEndpoint would derive
 * for THIS request — the same [ClientAddressResolver] bean — so
 * the ITs observe what Quarkus' trusted-proxy handling hands the key, through real HTTP.
 */
@Path("/test-probe/client-address-key")
class ClientAddressProbeResource {
    @Inject
    lateinit var resolver: ClientAddressResolver

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    fun key(@Context request: HttpServerRequest): String = resolver.key(request)
}
