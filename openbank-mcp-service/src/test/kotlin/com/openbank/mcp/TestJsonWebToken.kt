// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Minimal POJO [JsonWebToken] for plain-unit tests (mcp tests carry no Quarkus context — see
 * [McpEndpointTest]). Backed by a claim map; `getSubject()` resolves via `getClaim("sub")` per the
 * MP-JWT default, so an empty map models an anonymous / OIDC-disabled call.
 *
 * [getName] resolves `preferred_username` BEFORE `sub`, mirroring Quarkus' principal-name
 * resolution. That asymmetry is load-bearing: with `getName()` returning `sub`, a fixture cannot
 * express a real token — where the two claims hold different values — and #2938 (a row keyed by
 * the name claim, validated against `sub`) stays invisible to every test.
 */
class TestJsonWebToken(private val claims: Map<String, Any?> = emptyMap()) : JsonWebToken {
    override fun getName(): String =
        claims["preferred_username"]?.toString() ?: claims["sub"]?.toString() ?: "anonymous"

    override fun getClaimNames(): Set<String> = claims.keys

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getClaim(claimName: String): T? = claims[claimName] as? T
}
