// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import jakarta.annotation.security.RolesAllowed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression guard (K7 / ADR-0018): the general ledger is the book of record. The generic
 * "no endpoint is @PermitAll, every endpoint is @RolesAllowed" check moved to
 * [LedgerAuthzConformanceTest] (issue #467, `openbank-libs-testing`); this class keeps the
 * service-specific assertions — which roles a given endpoint must carry.
 */
class LedgerSecurityContractTest {

    private fun rolesOf(name: String): List<String> {
        val m = LedgerResource::class.java.declaredMethods.single { it.name == name }
        return m.getAnnotation(RolesAllowed::class.java)?.value?.toList() ?: emptyList()
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
                .containsExactlyInAnyOrder("ROLE_API", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
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

    @Test
    fun `booked-change replay is an ops recovery action restricted to operator and admin`() {
        // #860: re-emits historical book-of-record events for a downstream projection catch-up.
        // Ops-only (operator + admin, like other ops triggers) — never service/viewer/auditor.
        assertThat(rolesOf("replayBookedChanges"))
            .describedAs("replayBookedChanges roles (ops recovery — re-emits book-of-record events)")
            .containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_ADMIN")
    }
}
