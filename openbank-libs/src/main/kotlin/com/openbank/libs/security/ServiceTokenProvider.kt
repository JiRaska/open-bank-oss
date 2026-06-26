// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.security

/**
 * Source of bearer tokens for service-to-service calls.
 *
 * The recommended Quarkus production implementation is `quarkus-oidc-client-reactive-filter`:
 * it fetches a token via the OAuth2 Client Credentials grant from Keycloak, caches it until
 * expiry and renews automatically. Applying `@OidcClientFilter` on a `@RegisterRestClient`
 * interface inserts the `Authorization: Bearer …` header per call.
 *
 * This interface exists so libs-level utilities (e.g. test doubles, in-process fakes used
 * for chaos drills) can be swapped in without depending on the Quarkus OIDC client at
 * compile time.
 */
interface ServiceTokenProvider {
    /** Returns a non-empty bearer token suitable for an `Authorization` header. May block. */
    fun getToken(): String

    /** Force an immediate refresh of the cached token, ignoring TTL. */
    fun refresh()
}

/**
 * Static-token provider for local development and tests only.
 *
 * Production must use the OIDC client. Wiring this in production would defeat S2S auth and
 * is logged loudly on startup so it's hard to miss in environment scan.
 */
class StaticServiceTokenProvider(private val token: String) : ServiceTokenProvider {
    init {
        require(token.isNotBlank()) { "static service token must be non-blank" }
        org.jboss.logging.Logger.getLogger(StaticServiceTokenProvider::class.java).warnf(
            "StaticServiceTokenProvider in use — DO NOT deploy this outside dev/test. " +
                "Switch to OidcClientFilter for production.",
        )
    }
    override fun getToken(): String = token
    override fun refresh() { /* no-op */ }
}
