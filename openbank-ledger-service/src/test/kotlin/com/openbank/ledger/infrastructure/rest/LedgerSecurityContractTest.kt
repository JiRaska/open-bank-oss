// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.infrastructure.rest

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
 * Regression guard (K7 / ADR-0018): the general ledger is the book of record; its read endpoints once
 * carried `@PermitAll`. This test locks the declarative access-control contract so a silent revert to
 * `@PermitAll` (or a new unannotated endpoint) fails the build. `@RolesAllowed` / `@PermitAll` are
 * RUNTIME-retained, so the contract is asserted by reflection without booting JAX-RS — end-to-end
 * enforcement is Quarkus OIDC's job.
 */
class LedgerSecurityContractTest {

    private val httpVerbs =
        listOf(GET::class.java, POST::class.java, PUT::class.java, DELETE::class.java, PATCH::class.java)

    private fun Method.isHttpEndpoint() = httpVerbs.any { getAnnotation(it) != null }

    private fun rolesOf(name: String): List<String> {
        val m = LedgerResource::class.java.declaredMethods.single { it.name == name }
        return m.getAnnotation(RolesAllowed::class.java)?.value?.toList() ?: emptyList()
    }

    @Test
    fun `every ledger endpoint is role-gated, never permit-all`() {
        val all = LedgerResource::class.java.declaredMethods.filter { it.isHttpEndpoint() }
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
    fun `reads are gated to service, auditor, viewer, operator and admin`() {
        listOf(
            "listJournals",
            "trialBalance",
            "subLedgerBalances",
            "getJournal",
            "getJournalsByTransaction",
        ).forEach { name ->
            assertThat(rolesOf(name))
                .describedAs("%s read roles (financial-control evidence)", name)
                .containsExactlyInAnyOrder("ROLE_SERVICE", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
        }
    }

    @Test
    fun `posting and reversing a journal is restricted to operator`() {
        listOf("postJournal", "reverseJournal").forEach { name ->
            assertThat(rolesOf(name))
                .describedAs("%s roles (book-of-record write)", name)
                .containsExactlyInAnyOrder("ROLE_OPERATOR")
        }
    }
}
