// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

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
import java.util.UUID

/**
 * Regression guard (K7 / ADR-0018): account-service exposes account/balance/pocket data; the read
 * endpoints once carried `@PermitAll`. This test locks the declarative access-control contract so a
 * silent revert to `@PermitAll` (or a new unannotated endpoint) fails the build. `@RolesAllowed` /
 * `@PermitAll` are RUNTIME-retained, so the contract is asserted by reflection without booting
 * JAX-RS — end-to-end enforcement is Quarkus OIDC's job.
 */
class AccountSecurityContractTest {

    private val httpVerbs =
        listOf(GET::class.java, POST::class.java, PUT::class.java, DELETE::class.java, PATCH::class.java)

    private fun Method.isHttpEndpoint() = httpVerbs.any { getAnnotation(it) != null }

    private fun rolesOf(name: String): List<String> {
        val m = AccountResource::class.java.declaredMethods.single { it.name == name }
        return m.getAnnotation(RolesAllowed::class.java)?.value?.toList() ?: emptyList()
    }

    @Test
    fun `every account endpoint is role-gated, never permit-all`() {
        val all = AccountResource::class.java.declaredMethods.filter { it.isHttpEndpoint() }
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
    fun `reads are gated to service, viewer, operator and admin`() {
        listOf(
            "getAccount",
            "getAccountByIban",
            "listAccounts",
            "listActiveAccounts",
            "searchAccounts",
            "getBalance",
            "listPockets",
            "resolvePocket",
        ).forEach { name ->
            assertThat(rolesOf(name))
                .describedAs("%s read roles", name)
                .containsExactlyInAnyOrder("ROLE_API", "ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
        }
    }

    @Test
    fun `mutations are restricted to operator and admin`() {
        listOf(
            "openAccount",
            "addPocket",
            "closePocket",
            "closeAccount",
            "freezeAccount",
            "unfreezeAccount",
            "updateSavingsGoal",
            "clearSavingsGoal",
        ).forEach { name ->
            assertThat(rolesOf(name))
                .describedAs("%s mutation roles", name)
                .containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_ADMIN")
        }
    }

    // ── customer-mediated ownership guard (IDOR defense-in-depth, finding A1) ─
    // When the edge tags a read with X-Customer-Party-Id, the account must belong to that party.
    // Operator/service reads (no header) are never a violation. End-to-end behaviour is covered by
    // AccountApiIT; here we lock the pure decision.

    @Test
    fun `customer-scoped read of a non-owned account is a violation`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        assertThat(AccountResource.isCustomerOwnershipViolation(owner, other)).isTrue()
    }

    @Test
    fun `customer-scoped read of the caller's own account is allowed`() {
        val owner = UUID.randomUUID()
        assertThat(AccountResource.isCustomerOwnershipViolation(owner, owner)).isFalse()
    }

    @Test
    fun `operator read with no customer-party header is never a violation`() {
        assertThat(AccountResource.isCustomerOwnershipViolation(UUID.randomUUID(), null)).isFalse()
    }
}
