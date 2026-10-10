// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test

/** #12448: the service-account test is bound to the CLIENT (`azp`), never to the principal name. */
class ServiceAccountIdentityTest {

    private val edge = "service-account-openbank-edge"

    private fun jwt(azp: String?, username: String?, sub: String? = "5f0c2a8e") = QuarkusSecurityIdentity.builder()
        .setPrincipal(
            mockk<JsonWebToken> {
                every { name } returns (username ?: "anon")
                every { subject } returns sub
                every { getClaim<Any?>("azp") } returns azp
                every { getClaim<Any?>("preferred_username") } returns username
            },
        ).build()

    @Test
    fun `the client's own service-account token is accepted`() {
        val id = jwt("openbank-edge", edge)
        assertThat(ServiceAccountIdentity.verifiedClientId(id)).isEqualTo("openbank-edge")
        assertThat(ServiceAccountIdentity.verifiedPrincipalName(id)).isEqualTo(edge)
        assertThat(ServiceAccountIdentity.isOneOf(id, setOf(edge))).isTrue()
        assertThat(ServiceAccountIdentity.isPrincipal(id, edge)).isTrue()
        assertThat(ServiceAccountIdentity.isClient(id, setOf("openbank-edge"))).isTrue()
    }

    @Test
    fun `the right username on a token issued to another client is refused`() {
        val id = jwt("openbank-admin-ui", edge)
        assertThat(ServiceAccountIdentity.verifiedClientId(id)).isNull()
        assertThat(ServiceAccountIdentity.isOneOf(id, setOf(edge))).isFalse()
        assertThat(ServiceAccountIdentity.isClient(id, setOf("openbank-edge", "openbank-admin-ui"))).isFalse()
    }

    @Test
    fun `a human logging in through the allowed client is refused`() {
        val id = jwt("openbank-edge", "alice")
        assertThat(ServiceAccountIdentity.isClient(id, setOf("openbank-edge"))).isFalse()
        assertThat(ServiceAccountIdentity.isOneOf(id, setOf(edge, "alice"))).isFalse()
    }

    @Test
    fun `a genuine service-account of a client NOT allowed is refused`() {
        val id = jwt("openbank-kyb", "service-account-openbank-kyb")
        assertThat(ServiceAccountIdentity.verifiedClientId(id)).isEqualTo("openbank-kyb")
        assertThat(ServiceAccountIdentity.isOneOf(id, setOf(edge))).isFalse()
    }

    @Test
    fun `missing sub or azp, and a non-JWT principal with the right name, are refused`() {
        assertThat(ServiceAccountIdentity.isOneOf(jwt("openbank-edge", edge, sub = null), setOf(edge))).isFalse()
        assertThat(ServiceAccountIdentity.isOneOf(jwt("openbank-edge", edge, sub = " "), setOf(edge))).isFalse()
        assertThat(ServiceAccountIdentity.isOneOf(jwt(null, edge), setOf(edge))).isFalse()
        assertThat(ServiceAccountIdentity.isOneOf(jwt("", "service-account-"), setOf("service-account-"))).isFalse()
        val named = QuarkusSecurityIdentity.builder().setPrincipal(QuarkusPrincipal(edge)).build()
        assertThat(ServiceAccountIdentity.isOneOf(named, setOf(edge))).isFalse()
        assertThat(ServiceAccountIdentity.isOneOf(null, setOf(edge))).isFalse()
    }

    @Test
    fun `a blank configured principal admits nobody`() {
        val id = jwt("openbank-edge", edge)
        assertThat(ServiceAccountIdentity.isPrincipal(id, "")).isFalse()
        assertThat(ServiceAccountIdentity.isPrincipal(id, null)).isFalse()
    }
}
