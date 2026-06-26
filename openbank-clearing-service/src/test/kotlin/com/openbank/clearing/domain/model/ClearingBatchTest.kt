// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearing.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ClearingBatchTest {

    private val fixedNow = OffsetDateTime.of(2026, 1, 20, 10, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `batch starts with pending status and default fields`() {
        val batch = ClearingBatch(
            batchReference = "BATCH-001",
            rail = PaymentRail.SEPA_SCT,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )

        assertThat(batch.batchReference).isEqualTo("BATCH-001")
        assertThat(batch.rail).isEqualTo(PaymentRail.SEPA_SCT)
        assertThat(batch.status).isEqualTo(ClearingStatus.PENDING)
        assertThat(batch.settlementType).isEqualTo(SettlementType.NET)
        assertThat(batch.totalDebit).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(batch.totalCredit).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(batch.netPosition).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(batch.currency).isEqualTo("EUR")
        assertThat(batch.itemCount).isZero()
        assertThat(batch.cycleId).isNull()
        assertThat(batch.settlementDate).isNull()
        assertThat(batch.settledAt).isNull()
        assertThat(batch.id).isNotNull()
        assertThat(batch.createdAt).isNotNull()
        assertThat(batch.updatedAt).isNotNull()
    }
}
