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
 *  - `email_verified`  → null     (null → false in registerParty)
 *  - `email`           → null     (falls back to request body `email` field)
 *
 * The E2E suites ([com.openbank.party.e2e]) exercise journeys that read these claims
 * directly — verified-email self-registration and the GDPR subject-self branch — so the
 * defaults are [configure]-able per journey and must be [reset] in a `finally`. Every other
 * test class observes the defaults above, byte-identical to the historical frozen behaviour.
 */
@Mock
@RequestScoped
class TestJsonWebToken : JsonWebToken {

    override fun getName(): String = configuredSubject

    override fun getRawToken(): String = "test-token"

    override fun getClaimNames(): Set<String> = setOf("sub", "email", "email_verified")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getClaim(claimName: String): T? = when (claimName) {
        "sub" -> configuredSubject as? T
        "email" -> configuredEmail as? T
        "email_verified" -> configuredEmailVerified as? T
        else -> null
    }

    companion object {
        private const val DEFAULT_SUBJECT = "test-sub"

        private var configuredSubject: String = DEFAULT_SUBJECT
        private var configuredEmail: String? = null
        private var configuredEmailVerified: Boolean? = null

        /** Point the request-scoped principal's claims at the given subject for one journey. */
        fun configure(sub: String, email: String? = null, emailVerified: Boolean? = null) {
            configuredSubject = sub
            configuredEmail = email
            configuredEmailVerified = emailVerified
        }

        /** Restore the frozen defaults so no other test class observes E2E claims. */
        fun reset() {
            configuredSubject = DEFAULT_SUBJECT
            configuredEmail = null
            configuredEmailVerified = null
        }
    }
}
