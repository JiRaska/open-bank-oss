// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class BalanceHoldEvidenceTest {
    @Test
    fun `hold retains reason expiry and release evidence`() {
        val created = OffsetDateTime.parse("2026-09-01T10:00:00Z")
        val expires = created.plusDays(2)
        val released = created.plusHours(1)
        val hold = BalanceHold(
            id = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            amount = BigDecimal("75.00"),
            currency = "CZK",
            reason = "delegated-payment-reservation",
            referenceId = "payment-42",
            expiresAt = expires,
            createdAt = created,
            releasedAt = released,
        )

        assertThat(hold.reason).isEqualTo("delegated-payment-reservation")
        assertThat(hold.expiresAt).isEqualTo(expires)
        assertThat(hold.releasedAt).isEqualTo(released)
    }
}
