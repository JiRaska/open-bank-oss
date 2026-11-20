// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import jakarta.enterprise.context.RequestScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Test-only [JsonWebToken] source for [McpCustomerChannelIT]. `@OidcSecurity` claims are annotation
 * constants, and a DPoP-bound token's `cnf.jkt` must be the thumbprint of a key generated at test
 * time — so the claims are set per test here instead. Enabled ONLY by that IT's profile
 * (`getEnabledAlternatives`), never module-wide. Signature/issuer validation is the OIDC tenant's
 * job and not under test; the admission decision is.
 */
@Alternative
class CustomerTokenSource {
    @Produces
    @RequestScoped
    fun token(): JsonWebToken = TestJsonWebToken(claims)

    companion object {
        @Volatile
        var claims: Map<String, Any?> = emptyMap()
    }
}
