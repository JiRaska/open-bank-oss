// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.AuthorizationRequest
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.CookieParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Response.Status
import jakarta.ws.rs.core.UriInfo

@Path("/oauth/hosted")
internal class HostedOAuthResource(
    private val request: HttpServerRequest,
    private val runtime: Instance<HostedOAuthRuntime>,
) {
    @GET
    @Path("/authorize")
    suspend fun authorize(@Context uri: UriInfo, @CookieParam(BINDING_COOKIE) binding: String?): Response {
        val active = activeRuntime() ?: return response(Status.NOT_FOUND).build()
        val query = uri.queryParameters
        val pending = active.start.authorize(
            AuthorizationRequest(
                query["client_id"]?.singleOrNull(),
                query["redirect_uri"]?.singleOrNull(),
                query["response_type"]?.singleOrNull(),
                query["code_challenge"]?.singleOrNull(),
                query["code_challenge_method"]?.singleOrNull(),
                query["state"]?.singleOrNull(),
                query["scope"]?.singleOrNull(),
                query["resource"]?.singleOrNull(),
            ),
            binding,
        ) ?: return response(Status.BAD_REQUEST).build()
        val cookie = "$BINDING_COOKIE=${pending.browserBinding}; Max-Age=$BINDING_TTL_SECONDS; " +
            "Path=/; Secure; HttpOnly; SameSite=Lax"
        return response(Status.SEE_OTHER)
            .location(pending.bankAuthorize)
            .header("Set-Cookie", cookie)
            .build()
    }

    @GET
    @Path("/callback")
    suspend fun callback(@Context uri: UriInfo, @CookieParam(BINDING_COOKIE) binding: String?): Response {
        val active = activeRuntime() ?: return response(Status.NOT_FOUND).build()
        val query = uri.queryParameters
        val state = query["state"]?.singleOrNull() ?: return response(Status.BAD_REQUEST).build()
        val code = query["code"]?.singleOrNull() ?: return response(Status.BAD_REQUEST).build()
        if (binding == null || query.containsKey("error")) return response(Status.BAD_REQUEST).build()
        val redirect = active.complete.callback(state, binding, code) ?: return response(Status.BAD_REQUEST).build()
        return response(Status.SEE_OTHER).location(redirect).build()
    }

    private fun activeRuntime(): HostedOAuthRuntime? =
        if (request.isSSL && runtime.isResolvable) runtime.get().takeIf { it.enabledVendors.isNotEmpty() } else null

    private fun response(status: Status): Response.ResponseBuilder =
        Response.status(status).header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")

    private companion object {
        const val BINDING_COOKIE = "__Host-openbank-hosted-oauth"
        const val BINDING_TTL_SECONDS = 600
    }
}
