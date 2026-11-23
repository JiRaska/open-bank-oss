// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import java.net.URI
import java.security.MessageDigest

/**
 * ADR-0385 D3 — the canonical context of one MCP connect. Its SHA-256 is the
 * [DynamicLinkingData.payloadSha256] of an [ScaPurpose.MCP_CLIENT_CONNECT] challenge, computed by
 * the authorization server (Keycloak extension `McpConnectContext.java`) and signed by the device.
 * This is the specification the app renders the connect screen from, so both implementations are
 * held to ONE shared fixture (`openbank-infra/docker/keycloak/extension/src/test/resources/
 * mcp-connect-context.fixture.json`), byte for byte.
 *
 * UTF-8, seven lines joined by `\n`, no trailing newline:
 * `openbank-mcp-connect-context/v1`, then `client_id=`, `redirect_host=` (lower-case, IPv6 in
 * brackets), `resource=`, `scopes=` (distinct, sorted, single-space), `request_uri_sha256=`
 * (lower-case hex of the PAR request_uri) and `expires_at=` (epoch seconds). CR/LF in any value is
 * refused so no field can forge another line.
 */
data class McpConnectContext(
    val clientId: String,
    val redirectHost: String,
    val resource: String,
    val scopes: String,
    val requestUriSha256: String,
    val expiresAt: Long,
) {
    init {
        listOf(clientId, redirectHost, resource, scopes, requestUriSha256).forEach {
            require(it.isNotEmpty()) { "every MCP connect context field is required" }
            require('\n' !in it && '\r' !in it) { "an MCP connect context field may not contain CR or LF" }
        }
        require(SHA256_LOWER_HEX.matches(requestUriSha256)) {
            "request_uri_sha256 must be 64 lower-case hex characters"
        }
        require(expiresAt > 0) { "expires_at must be positive epoch seconds" }
    }

    fun canonical(): String = listOf(
        VERSION,
        "client_id=$clientId",
        "redirect_host=$redirectHost",
        "resource=$resource",
        "scopes=$scopes",
        "request_uri_sha256=$requestUriSha256",
        "expires_at=$expiresAt",
    ).joinToString("\n")

    fun payloadSha256(): String = sha256Hex(canonical())

    companion object {
        const val VERSION = "openbank-mcp-connect-context/v1"
        const val PAR_REQUEST_URI_PREFIX = "urn:ietf:params:oauth:request_uri:"
        private val SHA256_LOWER_HEX = Regex("^[0-9a-f]{64}$")

        fun of(
            clientId: String,
            redirectUri: String,
            resource: String,
            scope: String?,
            requestUri: String,
            expiresAt: Long,
        ): McpConnectContext {
            parRequestId(requestUri)
            return McpConnectContext(
                clientId = clientId,
                redirectHost = redirectHost(redirectUri),
                resource = resource,
                scopes = scope.orEmpty().split(' ').filter { it.isNotEmpty() }.toSortedSet().joinToString(" "),
                requestUriSha256 = sha256Hex(requestUri),
                expiresAt = expiresAt,
            )
        }

        fun parRequestId(requestUri: String): String {
            require(
                requestUri.startsWith(PAR_REQUEST_URI_PREFIX) && requestUri.length > PAR_REQUEST_URI_PREFIX.length,
            ) {
                "not a PAR request_uri"
            }
            return requestUri.removePrefix(PAR_REQUEST_URI_PREFIX)
        }

        /** `mcp-connect:<PAR request id>` — the [ReservedNamespace.MCP_CONNECT] approvalRequestId. */
        fun approvalRequestId(requestUri: String): String =
            ReservedNamespace.MCP_CONNECT.prefix + parRequestId(requestUri)

        private fun redirectHost(redirectUri: String): String {
            val host = runCatching { URI.create(redirectUri).host }.getOrNull()
            require(!host.isNullOrEmpty()) { "redirect URI has no host" }
            return host.lowercase()
        }

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
