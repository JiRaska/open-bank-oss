// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

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
