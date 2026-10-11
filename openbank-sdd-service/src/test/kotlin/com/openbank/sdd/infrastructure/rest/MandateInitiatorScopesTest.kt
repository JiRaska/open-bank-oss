// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.infrastructure.rest

import com.openbank.sdd.domain.model.MandateInitiatorScope
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.security.Principal
import java.util.Optional

/** ADR-0335 D6: only the declared principal is scoped; the declaration is held to rules.yaml. */
class MandateInitiatorScopesTest {
    private fun identity(name: String, operator: Boolean = false) = mockk<SecurityIdentity>().also {
        every { it.hasRole(any()) } answers { operator && firstArg<String>() == "ROLE_OPERATOR" }
        every { it.principal } returns principalFor(name)
    }

    /** A verified service-account JWT for `service-account-<azp>` names, a plain principal otherwise. */
    private fun principalFor(name: String): Principal = if (name.startsWith("service-account-")) {
        TestJwt(mapOf("azp" to name.removePrefix("service-account-"), "preferred_username" to name, "sub" to "sa"))
    } else {
        Principal { name }
    }

    private class TestJwt(private val claims: Map<String, Any?>) : JsonWebToken {
        override fun getName(): String = claims["preferred_username"] as? String ?: "anonymous"
        override fun getClaimNames(): Set<String> = claims.filterValues { it != null }.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
    }

    @Test
    fun `plain principals merely NAMED like pension or edge are denied`() {
        val scopes = MandateInitiatorScopes(Optional.of("CZ00ZZZPENSION01"))
        listOf("service-account-openbank-pension", "service-account-openbank-edge").forEach { name ->
            val impostor = mockk<SecurityIdentity>().also {
                every { it.principal } returns Principal { name }
                every { it.hasRole(any()) } returns false
            }
            assertThat(scopes.of(impostor)).isEqualTo(MandateInitiatorScope.Denied)
        }
    }

    @Test
    fun `pension is scoped and only declared edge and staff retain general access`() {
        val scopes = MandateInitiatorScopes(Optional.of("CZ00ZZZPENSION01"))
        assertThat(scopes.of(identity("service-account-openbank-pension")))
            .isEqualTo(MandateInitiatorScope.Scoped("service-account-openbank-pension", "CZ00ZZZPENSION01"))
        assertThat(scopes.of(identity("service-account-openbank-edge"))).isEqualTo(MandateInitiatorScope.General)
        assertThat(MandateInitiatorScopes(Optional.empty()).of(identity("service-account-openbank-pension")))
            .isEqualTo(MandateInitiatorScope.Scoped("service-account-openbank-pension", null))
    }

    @Test
    fun `undeclared machines are denied even with operator roles`() {
        val scopes = MandateInitiatorScopes(Optional.of("CZ00ZZZPENSION01"))
        assertThat(scopes.of(identity("service-account-openbank-services", operator = true)))
            .isEqualTo(MandateInitiatorScope.Denied)
        assertThat(scopes.of(identity("service-account-new-caller", operator = true)))
            .isEqualTo(MandateInitiatorScope.Denied)
        assertThat(scopes.of(identity("staff", operator = true))).isEqualTo(MandateInitiatorScope.General)
        assertThat(scopes.of(identity("customer"))).isEqualTo(MandateInitiatorScope.Denied)
    }

    @Test
    fun `the declared initiators match rules yaml scoped_payment_initiators for this service`() {
        val rules = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "openbank-libs/governance/rules.yaml") }.first { it.isFile }

        @Suppress("UNCHECKED_CAST")
        val declared = (
            Yaml().load<Map<String, Any>>(
                rules.readText(),
            )["scoped_payment_initiators"] as List<Map<String, Any>>
            )
            .filter { it["service"] == "openbank-sdd-service" }
        assertThat(declared.associate { it["principal"] as String to it["creditor_identifier_config"] as String })
            .isEqualTo(MandateInitiatorScopes.DECLARED)
        declared.forEach {
            assertThat(it["actions"] as List<*>).containsExactlyInAnyOrder("sdd.create", "sdd.delete")
            assertThat(it["debtor_party_verified"]).isEqualTo(true)
        }
    }
}
