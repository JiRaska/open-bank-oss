// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.persistence.mapper

import com.openbank.dispute.domain.model.Complaint
import com.openbank.dispute.domain.model.ComplaintCategory
import com.openbank.dispute.domain.model.ComplaintChannel
import com.openbank.dispute.domain.model.ComplaintStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Round-trip tests for [ComplaintMapper]. Note the mapper does NOT carry [Complaint.breached] —
 * that field is derived at read time by `ComplaintService.withBreach` and must never be persisted,
 * so a round-trip through the entity is expected to reset it to `false` regardless of the input.
 */
class ComplaintMapperTest {

    private val mapper = ComplaintMapper()

    private val now = OffsetDateTime.of(2026, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC)
    private val received = LocalDate.of(2026, 6, 9)
    private val due = LocalDate.of(2026, 6, 30)

    @Test
    fun `complaint round-trips through entity with all fields populated`() {
        val complaint = Complaint(
            id = UUID.randomUUID(),
            reference = "CMP-1001",
            category = ComplaintCategory.FEES,
            channel = ComplaintChannel.BRANCH,
            description = "Disputed monthly fee",
            status = ComplaintStatus.CLOSED,
            accountId = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            disputeId = UUID.randomUUID(),
            receivedDate = received,
            dueDate = due,
            interimReplyAt = now,
            interimReplyReason = "awaiting review",
            resolvedAt = now,
            outcome = "Fee reversed",
            redressGranted = true,
            rootCauseCode = "FEE-ERR",
            closedAt = now,
            createdAt = now,
            updatedAt = now,
        )

        val entity = mapper.toEntity(complaint)
        val roundTripped = mapper.toDomain(entity)

        // `breached` is not carried by the entity/mapper — compare everything else field-by-field.
        assertThat(roundTripped).usingRecursiveComparison().ignoringFields("breached").isEqualTo(complaint)
        assertThat(roundTripped.breached).isFalse()
    }

    @Test
    fun `complaint round-trips through entity with nullable fields absent`() {
        val complaint = Complaint(
            reference = "CMP-1002",
            category = ComplaintCategory.OTHER,
            channel = ComplaintChannel.EMAIL,
            description = "General inquiry",
            receivedDate = received,
            dueDate = due,
            createdAt = now,
            updatedAt = now,
        )

        val roundTripped = mapper.toDomain(mapper.toEntity(complaint))

        assertThat(roundTripped.accountId).isNull()
        assertThat(roundTripped.transactionId).isNull()
        assertThat(roundTripped.disputeId).isNull()
        assertThat(roundTripped.interimReplyAt).isNull()
        assertThat(roundTripped.interimReplyReason).isNull()
        assertThat(roundTripped.resolvedAt).isNull()
        assertThat(roundTripped.outcome).isNull()
        assertThat(roundTripped.redressGranted).isNull()
        assertThat(roundTripped.rootCauseCode).isNull()
        assertThat(roundTripped.closedAt).isNull()
        assertThat(roundTripped).usingRecursiveComparison().ignoringFields("breached").isEqualTo(complaint)
    }

    @Test
    fun `entity default status is RECEIVED and default category is OTHER`() {
        val entity = com.openbank.dispute.infrastructure.persistence.entity.ComplaintEntity()

        assertThat(entity.status).isEqualTo(ComplaintStatus.RECEIVED)
        assertThat(entity.category).isEqualTo(ComplaintCategory.OTHER)
        assertThat(entity.channel).isEqualTo(ComplaintChannel.APP)
    }
}
