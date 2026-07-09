// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class EvidenceChainTest {

    private val clock = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)
    private val disputeId = UUID.randomUUID()

    private fun rawEvidence(submittedBy: String, evidenceType: String, description: String? = null) = DisputeEvidence(
        disputeId = disputeId,
        submittedBy = submittedBy,
        evidenceType = evidenceType,
        description = description,
        submittedAt = now,
    )

    @Test
    fun `first evidence item chains from genesis`() {
        val item = EvidenceChain.append(rawEvidence("customer", "STATEMENT"), previous = null)

        assertThat(item.sequence).isEqualTo(0)
        assertThat(item.prevHash).isEqualTo(EvidenceChain.GENESIS_HASH)
        assertThat(item.recordHash).isNotNull().isNotEqualTo(EvidenceChain.GENESIS_HASH)
    }

    @Test
    fun `second evidence item chains from the first`() {
        val first = EvidenceChain.append(rawEvidence("customer", "STATEMENT"), previous = null)
        val second = EvidenceChain.append(rawEvidence("ops", "TRANSACTION_REF"), previous = first)

        assertThat(second.sequence).isEqualTo(1)
        assertThat(second.prevHash).isEqualTo(first.recordHash)
        assertThat(second.recordHash).isNotEqualTo(first.recordHash)
    }

    @Test
    fun `verify reports intact for an untampered chain`() {
        val first = EvidenceChain.append(rawEvidence("customer", "STATEMENT"), previous = null)
        val second = EvidenceChain.append(rawEvidence("ops", "TRANSACTION_REF"), previous = first)
        val third = EvidenceChain.append(rawEvidence("merchant", "PSD2_MESSAGE_ID"), previous = second)

        val result = EvidenceChain.verify(disputeId, listOf(first, second, third))

        assertThat(result.intact).isTrue()
        assertThat(result.itemsChecked).isEqualTo(3)
        assertThat(result.firstBrokenEvidenceId).isNull()
    }

    @Test
    fun `verify detects a mutated description on a stored item`() {
        val first = EvidenceChain.append(rawEvidence("customer", "STATEMENT"), previous = null)
        val second = EvidenceChain.append(rawEvidence("ops", "TRANSACTION_REF"), previous = first)
        val third = EvidenceChain.append(rawEvidence("merchant", "PSD2_MESSAGE_ID"), previous = second)

        // Simulate tampering: an operator (or a compromised DB user) edits the second item's
        // description in place, without recomputing its recordHash.
        val tamperedSecond = second.copy(description = "forged description")

        val result = EvidenceChain.verify(disputeId, listOf(first, tamperedSecond, third))

        assertThat(result.intact).isFalse()
        assertThat(result.firstBrokenEvidenceId).isEqualTo(tamperedSecond.id)
        assertThat(result.itemsChecked).isEqualTo(1) // only the first item verified before the break
    }

    @Test
    fun `verify detects a deleted item shifting the chain`() {
        val first = EvidenceChain.append(rawEvidence("customer", "STATEMENT"), previous = null)
        val second = EvidenceChain.append(rawEvidence("ops", "TRANSACTION_REF"), previous = first)
        val third = EvidenceChain.append(rawEvidence("merchant", "PSD2_MESSAGE_ID"), previous = second)

        // Simulate deleting the second item: third now directly follows first in the stored list,
        // but third.prevHash still points at second's hash, not first's.
        val result = EvidenceChain.verify(disputeId, listOf(first, third))

        assertThat(result.intact).isFalse()
        assertThat(result.firstBrokenEvidenceId).isEqualTo(third.id)
    }

    @Test
    fun `verify detects reordered items`() {
        val first = EvidenceChain.append(rawEvidence("customer", "STATEMENT"), previous = null)
        val second = EvidenceChain.append(rawEvidence("ops", "TRANSACTION_REF"), previous = first)

        val result = EvidenceChain.verify(disputeId, listOf(second, first))

        assertThat(result.intact).isFalse()
    }

    @Test
    fun `empty chain is trivially intact`() {
        val result = EvidenceChain.verify(disputeId, emptyList())

        assertThat(result.intact).isTrue()
        assertThat(result.itemsChecked).isEqualTo(0)
    }
}
