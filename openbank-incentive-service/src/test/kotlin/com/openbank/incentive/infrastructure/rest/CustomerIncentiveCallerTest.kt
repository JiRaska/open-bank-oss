// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.incentive.infrastructure.rest

import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test

/** #12448: the customer-incentive endpoints trust the edge's CLIENT identity, not its principal name. */
class CustomerIncentiveCallerTest {

    private val edge = "service-account-openbank-edge"

    private fun jwt(azp: String?, username: String?, sub: String? = "sa-subject") = QuarkusSecurityIdentity.builder()
        .setPrincipal(TestJwt(mapOf("azp" to azp, "preferred_username" to username, "sub" to sub)))
        .addRole("ROLE_API")
        .build()

    private class TestJwt(private val claims: Map<String, Any?>) : JsonWebToken {
        override fun getName(): String = claims["preferred_username"] as? String ?: "anonymous"
        override fun getClaimNames(): Set<String> = claims.filterValues { it != null }.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
    }

    @Test
    fun `the edge's own service-account token is admitted`() {
        assertThat(isCustomerEdgeCaller(jwt("openbank-edge", edge), edge)).isTrue()
    }

    @Test
    fun `the edge's principal name on a token issued to another client is refused`() {
        assertThat(isCustomerEdgeCaller(jwt("openbank-admin-ui", edge), edge)).isFalse()
    }

    @Test
    fun `a human token issued through the edge client, or a non-JWT principal so named, is refused`() {
        assertThat(isCustomerEdgeCaller(jwt("openbank-edge", "alice"), edge)).isFalse()
        val named = QuarkusSecurityIdentity.builder().setPrincipal(QuarkusPrincipal(edge)).addRole("ROLE_API").build()
        assertThat(isCustomerEdgeCaller(named, edge)).isFalse()
        assertThat(isCustomerEdgeCaller(jwt("openbank-edge", edge, sub = null), edge)).isFalse()
    }

    @Test
    fun `a blank configured principal admits nobody`() {
        assertThat(isCustomerEdgeCaller(jwt("openbank-edge", edge), "")).isFalse()
    }
}
