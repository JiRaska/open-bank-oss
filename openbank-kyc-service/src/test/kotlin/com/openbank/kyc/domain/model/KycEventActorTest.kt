// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The four-eyes reviewer reaches the audit trail (#3994).
 *
 * Red against `origin/main`: `KycEvents.lifecycle` emitted no `actorId` key at all, so
 * `AuditConsumer` had nothing to read and stored NULL — 117 rows recording a ČNB AML/KYC §8
 * four-eyes decision with no eyes, even though `KycCase.reviewedBy` had held the reviewer's
 * identity, taken from the authenticated security context, the whole time.
 *
 * Every assertion is on an exact value. `assertThat(actorId).isNotNull()` would pass against the
 * `SYSTEM` id in the reviewed case and against the reviewer in the unreviewed one — i.e. against
 * both of the two failures this pair exists to separate.
 */
class KycEventActorTest {

    private val at: Instant = Instant.parse("2026-06-11T08:00:00Z")

    private fun case(reviewedBy: String?) = KycCase(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        partyId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        status = KycCaseStatus.APPROVED,
        riskLevel = RiskLevel.MEDIUM,
        assignedTo = null,
        checks = emptyList(),
        notes = null,
        reviewedBy = reviewedBy,
        reviewedAt = null,
        expiresAt = null,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun `an approved case names the reviewer who approved it`() {
        val envelope = KycEvents.caseApproved(case("analyst.novak@openbank.cz"), at).envelope

        assertThat(envelope["actorId"]).isEqualTo("analyst.novak@openbank.cz")
        assertThat(envelope["actorType"]).isEqualTo("REVIEWER")
    }

    @Test
    fun `a case nobody has reviewed says so, rather than borrowing an identity`() {
        // KYC_CASE_OPENED is opened by the PARTY_CREATED consumer — no human is involved, and
        // inventing one would be worse than the NULL it replaces.
        val envelope = KycEvents.caseOpened(case(reviewedBy = null), at).envelope

        assertThat(envelope["actorId"]).isEqualTo("system:kyc-service:case-lifecycle")
        assertThat(envelope["actorType"]).isEqualTo("SYSTEM")
    }

    @Test
    fun `a blank reviewer is treated as no reviewer, not as an actor named empty string`() {
        val envelope = KycEvents.caseApproved(case(reviewedBy = "  "), at).envelope

        assertThat(envelope["actorId"]).isEqualTo("system:kyc-service:case-lifecycle")
        assertThat(envelope["actorType"]).isEqualTo("SYSTEM")
    }

    @Test
    fun `the two states stay distinguishable - a reviewer is never a system id`() {
        val reviewed = KycEvents.caseRejected(case("analyst.novak@openbank.cz"), at).envelope
        val unreviewed = KycEvents.caseRejected(case(null), at).envelope

        assertThat(reviewed["actorId"] as String).doesNotStartWith("system:")
        assertThat(unreviewed["actorId"] as String).startsWith("system:")
    }
}
