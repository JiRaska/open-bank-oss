// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The terminal/active split is what the V5 partial unique index and `findActiveByPartyId` are
 * keyed on: mis-classifying one status either strands a party with no re-openable case
 * (terminal read as active) or lets a second active case be opened (active read as terminal).
 */
class KycCaseStatusTest {

    @Test
    fun `only APPROVED REJECTED and EXPIRED are terminal`() {
        assertThat(KycCaseStatus.entries.filter { it.isTerminal })
            .containsExactlyInAnyOrder(
                KycCaseStatus.APPROVED,
                KycCaseStatus.REJECTED,
                KycCaseStatus.EXPIRED,
            )
    }

    @Test
    fun `the in-flight statuses are active`() {
        assertThat(KycCaseStatus.entries.filter { it.isActive })
            .containsExactlyInAnyOrder(
                KycCaseStatus.OPEN,
                KycCaseStatus.DOCUMENTS_REQUIRED,
                KycCaseStatus.UNDER_REVIEW,
            )
    }

    @Test
    fun `active is exactly the negation of terminal for every status`() {
        assertThat(KycCaseStatus.entries.filter { it.isActive == it.isTerminal }).isEmpty()
    }

    @Test
    fun `TERMINAL_NAMES carries the enum names the persistence query binds`() {
        assertThat(KycCaseStatus.TERMINAL_NAMES).containsExactly("APPROVED", "REJECTED", "EXPIRED")
    }

    @Test
    fun `TERMINAL_NAMES has one entry per terminal status and no more`() {
        assertThat(KycCaseStatus.TERMINAL_NAMES)
            .hasSize(KycCaseStatus.entries.count { it.isTerminal })
            .doesNotContain("OPEN", "DOCUMENTS_REQUIRED", "UNDER_REVIEW")
    }
}
