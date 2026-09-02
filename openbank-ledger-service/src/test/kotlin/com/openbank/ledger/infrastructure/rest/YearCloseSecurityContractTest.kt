// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression guard (K7 / ADR-0018), mirroring [LedgerSecurityContractTest]: the year close is
 * statutory book-of-record evidence (ADR-0078 D5). The generic "no endpoint is @PermitAll, every
 * endpoint is @RolesAllowed" check moved to [LedgerAuthzConformanceTest] (issue #467,
 * `openbank-libs-testing`); this class keeps the service-specific role assertions — the two
 * state changes (draft creation, attestation) stay operator-only like posting a journal.
 * Note: this locks the DECLARATIVE access contract; it intentionally does not pin the OpenAPI
 * info.version (the API-contract axis is independent of version.txt, ADR-0048).
 */
class YearCloseSecurityContractTest {

    private fun rolesOf(name: String): List<String> {
        val m = YearCloseResource::class.java.declaredMethods.single { it.name == name }
        return m.getAnnotation(RolesAllowed::class.java)?.value?.toList() ?: emptyList()
    }

    private fun actionOf(resource: Class<*>, name: String): String =
        resource.declaredMethods.single { it.name == name }.getAnnotation(Authorize::class.java).action

    @Test
    fun `reads are gated to service, auditor, viewer, operator and admin`() {
        listOf("trialBalance", "getYearClose").forEach { name ->
            assertThat(rolesOf(name))
                .describedAs("%s read roles (financial-control evidence)", name)
                .containsExactlyInAnyOrder("ROLE_API", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
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

    @Test
    fun `close draft maker action is distinct from service journal posting`() {
        assertThat(actionOf(YearCloseResource::class.java, "createDraft")).isEqualTo("ledger.close.draft")
        assertThat(actionOf(ClosedPeriodResource::class.java, "createDraft")).isEqualTo("ledger.close.draft")
    }
}
