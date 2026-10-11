// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.io.File
import java.security.Principal

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
        assertThat(ServiceAccountIdentity.verifiedClientId(id.principal)).isEqualTo("openbank-edge")
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
        assertThat(ServiceAccountIdentity.verifiedClientId(Principal { edge })).isNull()
    }

    @Test
    fun `malformed JWT claims fail closed for a JAX-RS principal`() {
        for (badClaim in listOf("azp", "preferred_username")) {
            val token = mockk<JsonWebToken> {
                every { name } returns edge
                every { subject } returns "5f0c2a8e"
                every { getClaim<Any?>("azp") } returns
                    if (badClaim == "azp") listOf("openbank-edge") else "openbank-edge"
                every { getClaim<Any?>("preferred_username") } returns
                    if (badClaim == "preferred_username") listOf(edge) else edge
            }
            assertThat(ServiceAccountIdentity.verifiedClientId(token as Principal)).isNull()
        }
    }

    @Test
    fun `a blank configured principal admits nobody`() {
        val id = jwt("openbank-edge", edge)
        assertThat(ServiceAccountIdentity.isPrincipal(id, "")).isFalse()
        assertThat(ServiceAccountIdentity.isPrincipal(id, null)).isFalse()
    }

    @Test
    fun `only an interactive user JWT can use a staff role`() {
        assertThat(ServiceAccountIdentity.isHumanStaff(jwt("openbank-admin-ui", "alice"))).isTrue()
        assertThat(ServiceAccountIdentity.isHumanStaff(jwt("openbank-ops-cli", "alice"))).isTrue()
        assertThat(ServiceAccountIdentity.isHumanStaff(jwt("admin-cli", "alice"))).isTrue()
        assertThat(ServiceAccountIdentity.isHumanStaff(jwt("openbank-edge", "alice"))).isFalse()
        assertThat(ServiceAccountIdentity.isHumanStaff(jwt("openbank-admin-ui", "service-account-openbank-admin-ui")))
            .isFalse()
        assertThat(ServiceAccountIdentity.isHumanStaff(jwt("openbank-admin-ui", "alice", sub = null))).isFalse()
        val named = QuarkusSecurityIdentity.builder().setPrincipal(QuarkusPrincipal("alice")).build()
        assertThat(ServiceAccountIdentity.isHumanStaff(named)).isFalse()
    }

    @Test
    fun `staff login clients in the deployed realm cannot mint service account tokens`() {
        val realm = ObjectMapper().readTree(File("../openbank-infra/gitops/components/keycloak/realm-template.json"))
        val clients = realm["clients"].associateBy { it["clientId"].asText() }
        setOf("openbank-admin-ui", "openbank-ops-cli", "admin-cli").forEach { clientId ->
            assertThat(clients[clientId]).describedAs(clientId).isNotNull()
            assertThat(clients.getValue(clientId)["serviceAccountsEnabled"].asBoolean())
                .describedAs(clientId).isFalse()
        }
    }

    private fun claims(vararg pairs: Pair<String, String?>) = QuarkusSecurityIdentity.builder()
        .setPrincipal(
            mockk<JsonWebToken> {
                val m = pairs.toMap()
                every { name } returns (m["preferred_username"] ?: "anon")
                every { subject } returns "5f0c2a8e"
                every { getClaim<Any?>(any<String>()) } answers { m[firstArg<String>()] }
            },
        ).build()

    @Test
    fun `a client_id-only machine token is a machine even with no service-account name`() {
        val id = claims("client_id" to "openbank-batch", "azp" to "openbank-batch")
        assertThat(ServiceAccountIdentity.machineClientId(id.principal)).isEqualTo("openbank-batch")
        // the narrow, client-granting check still refuses it: it does not carry the SA username
        assertThat(ServiceAccountIdentity.verifiedClientId(id)).isNull()
    }

    @Test
    fun `a renamed service-account is still a machine via its client_id note`() {
        val id = claims("client_id" to "openbank-edge", "azp" to "openbank-edge", "preferred_username" to "alice")
        assertThat(ServiceAccountIdentity.machineClientId(id.principal)).isEqualTo("openbank-edge")
    }

    @Test
    fun `a conventional service-account token is a machine`() {
        val id = claims("azp" to "openbank-edge", "preferred_username" to edge)
        assertThat(ServiceAccountIdentity.machineClientId(id.principal)).isEqualTo("openbank-edge")
    }

    @Test
    fun `a token with no username is a machine, never a human`() {
        assertThat(ServiceAccountIdentity.machineClientId(claims("azp" to "x").principal)).isEqualTo("x")
        assertThat(ServiceAccountIdentity.machineClientId(claims().principal)).isEqualTo("")
    }

    @Test
    fun `a human staff login is not a machine`() {
        val id = claims("azp" to "openbank-admin-ui", "preferred_username" to "alice")
        assertThat(ServiceAccountIdentity.machineClientId(id.principal)).isNull()
    }

    @Test
    fun `a non-JWT principal is not classified`() {
        val id = QuarkusSecurityIdentity.builder().setPrincipal(java.security.Principal { edge }).build()
        assertThat(ServiceAccountIdentity.machineClientId(id.principal)).isNull()
    }

    @Test
    fun `an undeterminable identity is never a verified person`() {
        assertThat(ServiceAccountIdentity.isVerifiedPerson(null)).isFalse()
        val nonJwt = QuarkusSecurityIdentity.builder().setPrincipal(java.security.Principal { "alice" }).build()
        assertThat(ServiceAccountIdentity.isVerifiedPerson(nonJwt)).isFalse()
        val unreadable = QuarkusSecurityIdentity.builder().setPrincipal(
            mockk<JsonWebToken> {
                every { name } returns "alice"
                every { subject } returns "5f0c2a8e"
                every { getClaim<Any?>(any<String>()) } throws IllegalStateException("claims unreadable")
            },
        ).build()
        assertThat(ServiceAccountIdentity.isVerifiedPerson(unreadable)).isFalse()
        assertThat(
            ServiceAccountIdentity.isVerifiedPerson(
                claims(
                    "azp" to "openbank-admin-ui",
                    "preferred_username" to "alice",
                ),
            ),
        )
            .isTrue()
    }
}
