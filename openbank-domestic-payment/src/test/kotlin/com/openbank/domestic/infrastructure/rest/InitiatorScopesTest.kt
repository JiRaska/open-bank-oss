// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.domain.model.InitiatorScope
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.security.Principal
import java.util.Optional
import java.util.UUID

/** ADR-0335 D5: who may pay from what, resolved from the authenticated principal; held to rules.yaml. */
class InitiatorScopesTest {
    private val payout = UUID.randomUUID()

    private fun identity(name: String, vararg roles: String) = mockk<SecurityIdentity>().also { id ->
        every { id.principal } returns Principal { name }
        every { id.hasRole(any()) } answers { firstArg<String>() in roles }
    }

    @Test
    fun `pension is scoped to its configured payout account, and to nothing when unconfigured`() {
        assertThat(
            InitiatorScopes(
                Optional.of(payout.toString()),
            ).of(identity("service-account-openbank-pension", "ROLE_API")),
        )
            .isEqualTo(InitiatorScope.Scoped("service-account-openbank-pension", payout))
        val unconfigured = InitiatorScopes(
            Optional.empty(),
        ).of(identity("service-account-openbank-pension", "ROLE_API"))
        assertThat(unconfigured.permits(payout)).isFalse()
    }

    @Test
    fun `a ROLE_API-only machine without a declaration may pay from nothing`() {
        listOf("service-account-openbank-services", "service-account-openbank-billing").forEach {
            assertThat(InitiatorScopes(Optional.of(payout.toString())).of(identity(it, "ROLE_API")))
                .isEqualTo(InitiatorScope.Undeclared)
        }
    }

    @Test
    fun `staff and the operator-role edge keep general scope`() {
        assertThat(
            InitiatorScopes(Optional.empty()).of(identity("u-op", "ROLE_OPERATOR")),
        ).isEqualTo(InitiatorScope.General)
        assertThat(InitiatorScopes(Optional.empty()).of(identity("service-account-openbank-edge", "ROLE_OPERATOR")))
            .isEqualTo(InitiatorScope.General)
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
            .filter { it["service"] == "openbank-domestic-payment" }
        assertThat(declared.associate { it["principal"] as String to it["debtor_account_config"] as String })
            .isEqualTo(InitiatorScopes.DECLARED)
        declared.forEach { assertThat(it["actions"] as List<*>).containsExactly("domestic-payment.create") }
    }
}
