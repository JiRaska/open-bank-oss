// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.persistence.mapper

import com.openbank.dispute.domain.model.Dispute
import com.openbank.dispute.domain.model.DisputeEvidence
import com.openbank.dispute.domain.model.DisputeResolution
import com.openbank.dispute.domain.model.DisputeStatus
import com.openbank.dispute.domain.model.DisputeTimelineEvent
import com.openbank.dispute.domain.model.DisputeType
import com.openbank.dispute.domain.model.EvidenceChain
import com.openbank.dispute.domain.model.RemediationOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Round-trip tests for [DisputeMapper]: every field must survive domain -> entity -> domain
 * without loss or transposition. Pure JVM object mapping — no Quarkus/Panache boot needed.
 */
class DisputeMapperTest {

    private val mapper = DisputeMapper()

    private val now = OffsetDateTime.of(2026, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 6, 15)

    @Test
    fun `dispute round-trips through entity with all fields populated`() {
        val dispute = Dispute(
            id = UUID.randomUUID(),
            reference = "DSP-1001",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.NOT_AS_DESCRIBED,
            status = DisputeStatus.UNDER_REVIEW,
            resolution = DisputeResolution.CHARGEBACK,
            amount = BigDecimal("123.45"),
            currency = "CZK",
            description = "Item never arrived",
            merchantName = "Acme Corp",
            merchantId = "MERCH-42",
            transactionDate = today.minusDays(10),
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            resolvedAt = now,
            resolvedBy = "caseworker-1",
            chargebackAmount = BigDecimal("100.00"),
            remediationOutcome = RemediationOutcome.PARTIAL,
            remediationAmount = BigDecimal("60.00"),
            createdAt = now,
            updatedAt = now,
        )

        val entity = mapper.toEntity(dispute)
        val roundTripped = mapper.toDomain(entity)

        assertThat(roundTripped).isEqualTo(dispute)
    }

    @Test
    fun `dispute round-trips through entity with nullable fields absent`() {
        val dispute = Dispute(
            reference = "DSP-1002",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.OTHER,
            amount = BigDecimal("5.00"),
            transactionDate = today,
            filingDate = today,
            createdAt = now,
            updatedAt = now,
        )

        val roundTripped = mapper.toDomain(mapper.toEntity(dispute))

        assertThat(roundTripped.description).isNull()
        assertThat(roundTripped.merchantName).isNull()
        assertThat(roundTripped.merchantId).isNull()
        assertThat(roundTripped.resolutionDeadline).isNull()
        assertThat(roundTripped.resolvedAt).isNull()
        assertThat(roundTripped.resolvedBy).isNull()
        assertThat(roundTripped.chargebackAmount).isNull()
        assertThat(roundTripped.remediationOutcome).isNull()
        assertThat(roundTripped.remediationAmount).isNull()
        assertThat(roundTripped).isEqualTo(dispute)
    }

    @Test
    fun `dispute evidence round-trips through entity including chain fields`() {
        val raw = DisputeEvidence(
            id = UUID.randomUUID(),
            disputeId = UUID.randomUUID(),
            submittedBy = "customer",
            evidenceType = "RECEIPT",
            description = "Photo of receipt",
            fileReference = "s3://bucket/receipt.png",
            submittedAt = now,
        )
        val evidence = EvidenceChain.append(raw, previous = null)

        val roundTripped = mapper.toDomain(mapper.toEntity(evidence))

        assertThat(roundTripped).isEqualTo(evidence)
        assertThat(roundTripped.sequence).isEqualTo(0)
        assertThat(roundTripped.prevHash).isEqualTo(EvidenceChain.GENESIS_HASH)
        assertThat(roundTripped.recordHash).isNotNull()
    }

    @Test
    fun `evidence without submittedAt fails fast when mapped to an entity`() {
        val evidence = DisputeEvidence(
            disputeId = UUID.randomUUID(),
            submittedBy = "customer",
            evidenceType = "RECEIPT",
            submittedAt = null,
        )

        assertThatThrownBy { mapper.toEntity(evidence) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("submittedAt must be set")
    }

    @Test
    fun `evidence without recordHash fails fast when mapped to an entity`() {
        val evidence = DisputeEvidence(
            disputeId = UUID.randomUUID(),
            submittedBy = "customer",
            evidenceType = "RECEIPT",
            submittedAt = now,
        )

        assertThatThrownBy { mapper.toEntity(evidence) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("recordHash must be set")
    }

    @Test
    fun `dispute timeline event round-trips through entity`() {
        val event = DisputeTimelineEvent(
            id = UUID.randomUUID(),
            disputeId = UUID.randomUUID(),
            eventType = "OPENED",
            description = "Dispute opened: UNAUTHORIZED",
            actor = "CUSTOMER",
            createdAt = now,
        )

        val roundTripped = mapper.toDomain(mapper.toEntity(event))

        assertThat(roundTripped).isEqualTo(event)
    }

    @Test
    fun `dispute timeline event round-trips with null actor`() {
        val event = DisputeTimelineEvent(
            disputeId = UUID.randomUUID(),
            eventType = "STATUS_CHANGED",
            description = "Status updated to ESCALATED",
            actor = null,
            createdAt = now,
        )

        val roundTripped = mapper.toDomain(mapper.toEntity(event))

        assertThat(roundTripped.actor).isNull()
        assertThat(roundTripped).isEqualTo(event)
    }
}
