// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DisputeTest {

    private val clock = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)
    private val today = LocalDate.now(clock)

    @Test
    fun `open dispute uses open status and pending resolution by default`() {
        val dispute = Dispute(
            reference = "DSP-12345",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.UNAUTHORIZED,
            amount = BigDecimal("42.50"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )

        assertThat(dispute.status).isEqualTo(DisputeStatus.OPEN)
        assertThat(dispute.resolution).isEqualTo(DisputeResolution.PENDING)
        assertThat(dispute.resolutionDeadline).isEqualTo(today.plusDays(45))
    }
}
