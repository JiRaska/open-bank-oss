// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.legal

import com.openbank.libs.domain.identifiers.CaseId
import com.openbank.libs.domain.identifiers.PartyId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.util.UUID

class LegalEffectRevisionTest {
    private val identity = LegalEffectIdentity(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        CaseId.of("00000000-0000-0000-0000-000000000002"),
        PartyId.of("00000000-0000-0000-0000-000000000003"),
    )
    private val original = LegalEffectRevision(identity, 1, LegalEffectDisposition.ACTIVE, "a".repeat(64))

    @Test
    fun `release received before original attachment keeps its tombstone`() {
        val release = original.copy(revision = 2, disposition = LegalEffectDisposition.RELEASED)
        assertThat(LegalEffectRevisionGuard.evaluate(null, release)).isEqualTo(LegalEffectRevisionOutcome.APPLY)
        assertThat(LegalEffectRevisionGuard.evaluate(release, original)).isEqualTo(LegalEffectRevisionOutcome.STALE)
        assertThat(LegalEffectRevisionGuard.evaluate(release, release)).isEqualTo(LegalEffectRevisionOutcome.DUPLICATE)
    }

    @Test
    fun `an old release cannot undo a later amendment`() {
        val release = original.copy(revision = 2, disposition = LegalEffectDisposition.RELEASED)
        val amendment = original.copy(revision = 3, contentSha256 = "b".repeat(64))
        assertThat(LegalEffectRevisionGuard.evaluate(release, amendment)).isEqualTo(LegalEffectRevisionOutcome.APPLY)
        assertThat(LegalEffectRevisionGuard.evaluate(amendment, release)).isEqualTo(LegalEffectRevisionOutcome.STALE)
    }

    @Test
    fun `same revision with different contents or disposition is a conflict`() {
        assertThat(LegalEffectRevisionGuard.evaluate(original, original.copy(contentSha256 = "b".repeat(64))))
            .isEqualTo(LegalEffectRevisionOutcome.CONFLICT)
        val conflictingRelease = original.copy(disposition = LegalEffectDisposition.RELEASED)
        assertThat(LegalEffectRevisionGuard.evaluate(original, conflictingRelease))
            .isEqualTo(LegalEffectRevisionOutcome.CONFLICT)
        assertThat(LegalEffectRevisionGuard.evaluate(original, original))
            .isEqualTo(LegalEffectRevisionOutcome.DUPLICATE)
    }

    @Test
    fun `higher revision cannot move an effect to a different person or case`() {
        val otherParty = identity.copy(subjectPartyId = PartyId.of("00000000-0000-0000-0000-000000000004"))
        val otherCase = identity.copy(caseId = CaseId.of("00000000-0000-0000-0000-000000000005"))
        val otherEffect = identity.copy(effectId = UUID.fromString("00000000-0000-0000-0000-000000000006"))
        listOf(otherParty, otherCase, otherEffect).forEach {
            assertThat(LegalEffectRevisionGuard.evaluate(original, original.copy(identity = it, revision = 2)))
                .isEqualTo(LegalEffectRevisionOutcome.CONFLICT)
        }
    }

    @Test
    fun `a first complete snapshot may arrive after earlier revisions were missed`() {
        assertThat(LegalEffectRevisionGuard.evaluate(null, original.copy(revision = 10)))
            .isEqualTo(LegalEffectRevisionOutcome.APPLY)
        assertThat(LegalEffectRevisionGuard.evaluate(original, original.copy(revision = Long.MAX_VALUE)))
            .isEqualTo(LegalEffectRevisionOutcome.APPLY)
    }

    @Test
    fun `reject invalid revision and digest`() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { revision ->
            assertThatIllegalArgumentException().isThrownBy { original.copy(revision = revision) }
        }
        listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "a".repeat(63) + "\n").forEach {
            assertThatIllegalArgumentException().isThrownBy { original.copy(contentSha256 = it) }
        }
    }

    @Test
    fun `delivery permutations converge without lifting an unrelated effect`() {
        val secondId = UUID.fromString("00000000-0000-0000-0000-000000000007")
        val second = original.copy(identity = identity.copy(effectId = secondId))
        val release = original.copy(revision = 2, disposition = LegalEffectDisposition.RELEASED)
        val deliveries = listOf(original, second, release, original, release)
        // Exhaust all 120 delivery orders, including duplicates and release-before-attachment.
        permutations(deliveries).forEach { order ->
            val stored = mutableMapOf<UUID, LegalEffectRevision>()
            order.forEach { incoming ->
                val key = incoming.identity.effectId
                if (LegalEffectRevisionGuard.evaluate(stored[key], incoming) == LegalEffectRevisionOutcome.APPLY) {
                    stored[key] = incoming
                }
            }
            assertThat(stored).containsEntry(identity.effectId, release)
                .containsEntry(second.identity.effectId, second)
        }
    }

    private fun permutations(values: List<LegalEffectRevision>): List<List<LegalEffectRevision>> =
        if (values.isEmpty()) {
            listOf(emptyList())
        } else {
            values.indices.flatMap { index ->
                permutations(values.filterIndexed { other, _ -> other != index }).map { listOf(values[index]) + it }
            }
        }
}
