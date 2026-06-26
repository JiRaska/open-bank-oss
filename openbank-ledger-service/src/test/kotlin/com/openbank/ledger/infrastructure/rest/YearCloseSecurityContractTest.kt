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
 * Regression guard (K7 / ADR-0018), mirroring [LedgerSecurityContractTest]: the year close is
 * statutory book-of-record evidence (ADR-0078 D5), so no endpoint may be `@PermitAll` and the
 * two state changes (draft creation, attestation) stay operator-only like posting a journal.
 * Note: this locks the DECLARATIVE access contract; it intentionally does not pin the OpenAPI
 * info.version (the API-contract axis is independent of version.txt, ADR-0048).
 */
class YearCloseSecurityContractTest {

    private val httpVerbs =
        listOf(GET::class.java, POST::class.java, PUT::class.java, DELETE::class.java, PATCH::class.java)

    private fun Method.isHttpEndpoint() = httpVerbs.any { getAnnotation(it) != null }

    private fun rolesOf(name: String): List<String> {
        val m = YearCloseResource::class.java.declaredMethods.single { it.name == name }
        return m.getAnnotation(RolesAllowed::class.java)?.value?.toList() ?: emptyList()
    }

    @Test
    fun `every year-close endpoint is role-gated, never permit-all`() {
        val all = YearCloseResource::class.java.declaredMethods.filter { it.isHttpEndpoint() }
        assertThat(all).describedAs("expected to find HTTP endpoints by reflection").isNotEmpty

        all.forEach { m ->
            assertThat(m.getAnnotation(PermitAll::class.java))
                .describedAs("%s must NOT be @PermitAll (K7 — statutory close evidence)", m.name)
                .isNull()
            assertThat(m.getAnnotation(RolesAllowed::class.java))
                .describedAs("%s must be @RolesAllowed", m.name)
                .isNotNull()
        }
    }

    @Test
    fun `reads are gated to service, auditor, viewer, operator and admin`() {
        listOf("trialBalance", "getYearClose").forEach { name ->
            assertThat(rolesOf(name))
                .describedAs("%s read roles (financial-control evidence)", name)
                .containsExactlyInAnyOrder("ROLE_SERVICE", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
        }
    }

    @Test
    fun `draft creation and attestation are restricted to operator`() {
        listOf("createDraft", "attest").forEach { name ->
            assertThat(rolesOf(name))
                .describedAs("%s roles (statutory close state change)", name)
                .containsExactlyInAnyOrder("ROLE_OPERATOR")
        }
    }
}
