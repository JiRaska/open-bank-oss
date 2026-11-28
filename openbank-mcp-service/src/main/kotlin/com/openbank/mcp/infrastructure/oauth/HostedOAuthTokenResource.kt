// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.TokenAuthentication
import com.openbank.mcp.infrastructure.oauth.HostedVendorAuthentication.Credentials
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Response.Status
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

@Path("/oauth/hosted/token")
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Produces(MediaType.APPLICATION_JSON)
internal class HostedOAuthTokenResource(
    private val request: HttpServerRequest,
    private val runtime: Instance<HostedOAuthRuntime>,
) {
    @POST
    suspend fun token(form: MultivaluedMap<String, String>): Response {
        if (!request.isSSL || !runtime.isResolvable) return response(Status.NOT_FOUND).build()
        val active = runtime.get()
        if (active.enabledVendors.isEmpty()) return response(Status.NOT_FOUND).build()
        if (form.values.any { it.size != 1 }) return error("invalid_request")
        val resource = form.getFirst("resource") ?: return error("invalid_target")
        if (resource != HostedOAuthAdmission.RESOURCE) return error("invalid_target")
        val credentials = credentials(form) ?: return error("invalid_client")
        val tokens = when (form.getFirst("grant_type")) {
            "authorization_code" -> redeem(active, form, credentials, resource)
            "refresh_token" -> {
                val refresh = form.getFirst("refresh_token") ?: return error("invalid_request")
                active.grants.refresh(refresh, credentials, resource, form.getFirst("scope"))
            }
            else -> return error("unsupported_grant_type")
        } ?: return error("invalid_grant")
        return response(Status.OK).entity(
            mapOf(
                "access_token" to tokens.accessToken,
                "refresh_token" to tokens.refreshToken,
                "token_type" to "Bearer",
                "expires_in" to tokens.expiresIn,
                "scope" to tokens.scope,
            ),
        ).build()
    }

    private suspend fun redeem(
        active: HostedOAuthRuntime,
        form: MultivaluedMap<String, String>,
        credentials: Credentials,
        resource: String,
    ): HostedVendorTokens? {
        val code = form.getFirst("code") ?: return null
        val verifier = form.getFirst("code_verifier") ?: return null
        val redirect = form.getFirst("redirect_uri") ?: return null
        return active.complete.redeem(HostedOAuthComplete.Redemption(code, verifier, redirect, resource), credentials)
    }

    private fun credentials(form: MultivaluedMap<String, String>): Credentials? {
        val headers = request.headers().getAll("Authorization")
        val clientId = form.getFirst("client_id")
        val assertion = form.getFirst("client_assertion")
        val assertionType = form.getFirst("client_assertion_type")
        val secret = form.getFirst("client_secret")
        val hasAssertion = assertion != null || assertionType != null
        if (headers.isEmpty()) {
            return clientId?.let { formCredentials(it, assertion, assertionType, secret) }
        }
        if (hasAssertion || secret != null) return null
        return headers.singleOrNull()?.let(::basic)?.takeIf { clientId == null || clientId == it.clientId }
    }

    private fun formCredentials(
        clientId: String,
        assertion: String?,
        assertionType: String?,
        secret: String?,
    ): Credentials? {
        if (secret != null) {
            if (assertion != null || assertionType != null) return null
            return Credentials(clientId, TokenAuthentication.CLIENT_SECRET_POST, secret)
        }
        return assertion?.takeIf { assertionType == ASSERTION_TYPE }?.let {
            Credentials(clientId, TokenAuthentication.PRIVATE_KEY_JWT, it)
        }
    }

    private fun basic(header: String): Credentials? {
        if (header.length > MAX_AUTHORIZATION_HEADER_LENGTH) return null
        if (!header.startsWith(BASIC_PREFIX, ignoreCase = true)) return null
        return try {
            val decoded = Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length)).toString(UTF_8)
            val parts = decoded.split(':', limit = 2)
            if (parts.size != 2) return null
            Credentials(
                URLDecoder.decode(parts[0], UTF_8),
                TokenAuthentication.CLIENT_SECRET_BASIC,
                URLDecoder.decode(parts[1], UTF_8),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun error(code: String): Response = response(Status.BAD_REQUEST).entity(mapOf("error" to code)).build()

    private fun response(status: Status): Response.ResponseBuilder =
        Response.status(status).header("Cache-Control", "no-store").header("Pragma", "no-cache")

    private companion object {
        const val MAX_AUTHORIZATION_HEADER_LENGTH = 4096
        const val BASIC_PREFIX = "Basic "
        const val ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
    }
}
