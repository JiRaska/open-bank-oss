// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

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
 * Regression guard (K7 / ADR-0018): balance-service moves value, so **no HTTP endpoint may be
 * `@PermitAll` or unauthenticated**. `@RolesAllowed` / `@PermitAll` are RUNTIME-retained, so the
 * access-control contract is asserted by reflection without booting the JAX-RS runtime — end-to-end
 * enforcement is Quarkus OIDC's job; this locks the declarative contract so a silent revert to
 * `@PermitAll` (or a new unannotated money endpoint) fails the build.
 */
class BalanceSecurityContractTest {

    private val httpVerbs =
        listOf(GET::class.java, POST::class.java, PUT::class.java, DELETE::class.java, PATCH::class.java)

    private fun Method.isHttpEndpoint() = httpVerbs.any { getAnnotation(it) != null }

    private fun endpoints(type: Class<*>): List<Method> = type.declaredMethods.filter { it.isHttpEndpoint() }

    private fun rolesOf(m: Method): List<String> =
        m.getAnnotation(RolesAllowed::class.java)?.value?.toList() ?: emptyList()

    @Test
    fun `every balance endpoint is role-gated, never permit-all`() {
        val all = endpoints(BalanceResource::class.java) + endpoints(ReconciliationResource::class.java)
        assertThat(all).describedAs("expected to find HTTP endpoints by reflection").isNotEmpty

        all.forEach { m ->
            assertThat(m.getAnnotation(PermitAll::class.java))
                .describedAs("%s must NOT be @PermitAll (K7 — money-path)", m.name)
                .isNull()
            assertThat(m.getAnnotation(RolesAllowed::class.java))
                .describedAs("%s must be @RolesAllowed", m.name)
                .isNotNull()
        }
    }

    @Test
    fun `money-moving writes require the service or operator identity, not viewers`() {
        val writes = listOf("placeHold", "releaseHold", "credit", "debit", "initialize")
        writes.forEach { name ->
            val m = BalanceResource::class.java.declaredMethods.single { it.name == name }
            assertThat(rolesOf(m))
                .describedAs("%s roles", name)
                .containsExactlyInAnyOrder("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
        }
    }

    @Test
    fun `overdraft-limit override is restricted to supervisor and admin`() {
        val m = BalanceResource::class.java.declaredMethods.single { it.name == "setOverdraftLimit" }
        assertThat(rolesOf(m)).containsExactlyInAnyOrder("ROLE_SUPERVISOR", "ROLE_ADMIN")
    }

    @Test
    fun `reconciliation re-run is restricted to operator and admin`() {
        val m = ReconciliationResource::class.java.declaredMethods.single { it.name == "run" }
        assertThat(rolesOf(m)).containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_ADMIN")
    }
}
