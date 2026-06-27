// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import io.quarkus.test.Mock
import jakarta.enterprise.context.RequestScoped
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Minimal CDI alternative for [JsonWebToken] used in IT tests.
 *
 * [PartyResource] injects JsonWebToken to read `sub`, `email`, and `email_verified` claims.
 * In test mode, `quarkus.oidc.enabled=false` so the OIDC extension does not provide the bean.
 * This mock satisfies the CDI requirement; actual user/role enforcement is handled by
 * [@TestSecurity][io.quarkus.test.security.TestSecurity] at the test method level.
 *
 * All claim reads fall back to the defaults used in production code:
 *  - `sub`             → "test-sub" (non-null, prevents 401 in getMyParty)
 *  - `email_verified`  → false  (null → false in registerParty)
 *  - `email`           → null   (falls back to request body `email` field)
 */
@Mock
@RequestScoped
class TestJsonWebToken : JsonWebToken {

    override fun getName(): String = "test-sub"

    override fun getRawToken(): String = "test-token"

    override fun getClaimNames(): Set<String> = setOf("sub")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getClaim(claimName: String): T? = when (claimName) {
        "sub" -> getName() as T?
        else -> null
    }
}
