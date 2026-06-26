// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearing.infrastructure.rest

import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Regression guard (K7 / ADR-0018): clearing aggregates payments into settlement. The class once
 * carried a blanket `@PermitAll`; this test locks the declarative access-control contract so it can
 * never silently return. `@RolesAllowed` / `@PermitAll` are RUNTIME-retained, so the contract is
 * asserted by reflection without booting JAX-RS — end-to-end enforcement is Quarkus OIDC's job.
 */
class ClearingSecurityContractTest {

    private val httpVerbs =
        listOf(GET::class.java, POST::class.java, PUT::class.java, DELETE::class.java, PATCH::class.java)

    private fun Method.isHttpEndpoint() = httpVerbs.any { getAnnotation(it) != null }

    private fun rolesOf(name: String): List<String> {
        val m = ClearingResource::class.java.declaredMethods.single { it.name == name }
        return m.getAnnotation(RolesAllowed::class.java)?.value?.toList() ?: emptyList()
    }

    @Test
    fun `class is not blanket permit-all`() {
        assertThat(ClearingResource::class.java.getAnnotation(PermitAll::class.java))
            .describedAs("ClearingResource must NOT be class-level @PermitAll (K7 — money-path)")
            .isNull()
    }

    @Test
    fun `every clearing endpoint is role-gated, never permit-all`() {
        val all = ClearingResource::class.java.declaredMethods.filter { it.isHttpEndpoint() }
        assertThat(all).describedAs("expected to find HTTP endpoints by reflection").isNotEmpty

        all.forEach { m ->
            assertThat(m.getAnnotation(PermitAll::class.java))
                .describedAs("%s must NOT be @PermitAll (K7)", m.name)
                .isNull()
            assertThat(m.getAnnotation(RolesAllowed::class.java))
                .describedAs("%s must be @RolesAllowed", m.name)
                .isNotNull()
        }
    }

    @Test
    fun `settlement and cycle triggering are restricted to payment-ops and admin`() {
        listOf("settleBatch", "triggerCycle").forEach { name ->
            assertThat(rolesOf(name))
                .describedAs("%s roles (high blast radius)", name)
                .containsExactlyInAnyOrder("ROLE_PAYMENTS", "ROLE_ADMIN")
        }
    }
}
