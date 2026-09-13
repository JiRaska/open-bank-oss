// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.domain.AnnualFeeSummary
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.PostingStatus
import com.openbank.libs.product.WaiveReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit coverage for [AnnualFeeSummary.aggregate] (ADR-0248 PAD Art. 5 annual statement of fees):
 * fee grouping by (feeId, name), the year-total computation, and the exclusion of every
 * non-POSTED status (waived, still-pending, failed, under/already reversed) from the aggregate —
 * even though the repository is expected to filter those out too, this is the one layer that can
 * assert the exclusion rule without a database.
 */
class AnnualFeeSummaryTest {

    private fun fee(
        feeId: String,
        name: String,
        amount: String,
        status: PostingStatus = PostingStatus.POSTED,
        cycleId: String = "2026-01",
    ) = AssessedFee(
        cycleId = cycleId,
        accountId = "acc-1",
        feeId = feeId,
        name = name,
        currency = "CZK",
        chargedAmount = BigDecimal(amount),
        waived = status == PostingStatus.NOT_APPLICABLE,
        reason = WaiveReason.NOT_WAIVABLE,
        postingStatus = status,
    )

    @Test
    fun `sums repeated charges of the same fee across cycles into one line`() {
        val fees = listOf(
            fee("maintenance", "Monthly account maintenance", "50.00", cycleId = "2026-01"),
            fee("maintenance", "Monthly account maintenance", "50.00", cycleId = "2026-02"),
            fee("maintenance", "Monthly account maintenance", "50.00", cycleId = "2026-03"),
        )

        val summary = AnnualFeeSummary.aggregate("acc-1", "party-1", 2026, "CZK", fees, interestRate = null)

        assertThat(summary.fees).hasSize(1)
        assertThat(summary.fees[0].code).isEqualTo("maintenance")
        assertThat(summary.fees[0].amount).isEqualByComparingTo("150.00")
        assertThat(summary.totalFees).isEqualByComparingTo("150.00")
    }

    @Test
    fun `groups distinct fee codes into distinct lines and computes the grand total`() {
        val fees = listOf(
            fee("maintenance", "Monthly account maintenance", "50.00"),
            fee("atm-withdrawal", "ATM withdrawal", "40.00"),
            fee("atm-withdrawal", "ATM withdrawal", "40.00"),
        )

        val summary = AnnualFeeSummary.aggregate("acc-1", "party-1", 2026, "CZK", fees, interestRate = null)

        assertThat(summary.fees).extracting("code").containsExactlyInAnyOrder("maintenance", "atm-withdrawal")
        assertThat(summary.fees.first { it.code == "atm-withdrawal" }.amount).isEqualByComparingTo("80.00")
        assertThat(summary.totalFees).isEqualByComparingTo("130.00")
    }

    @Test
    fun `excludes waived (NOT_APPLICABLE) fees from the aggregate`() {
        val fees = listOf(
            fee("maintenance", "Monthly account maintenance", "50.00", status = PostingStatus.POSTED),
            fee("waived-fee", "Waived fee", "0.00", status = PostingStatus.NOT_APPLICABLE),
        )

        val summary = AnnualFeeSummary.aggregate("acc-1", "party-1", 2026, "CZK", fees, interestRate = null)

        assertThat(summary.fees).hasSize(1)
        assertThat(summary.fees[0].code).isEqualTo("maintenance")
        assertThat(summary.totalFees).isEqualByComparingTo("50.00")
    }

    @Test
    fun `excludes fees that are still pending, failed, or under-or-already reversed`() {
        val fees = listOf(
            fee("maintenance", "Monthly account maintenance", "50.00", status = PostingStatus.POSTED),
            fee("pending-fee", "Pending fee", "10.00", status = PostingStatus.PENDING),
            fee("failed-fee", "Failed fee", "10.00", status = PostingStatus.FAILED),
            fee("reversal-pending-fee", "Reversal pending fee", "10.00", status = PostingStatus.REVERSAL_PENDING),
            fee("reversed-fee", "Reversed fee", "10.00", status = PostingStatus.REVERSED),
        )

        val summary = AnnualFeeSummary.aggregate("acc-1", "party-1", 2026, "CZK", fees, interestRate = null)

        assertThat(summary.fees).hasSize(1)
        assertThat(summary.fees[0].code).isEqualTo("maintenance")
        assertThat(summary.totalFees).isEqualByComparingTo("50.00")
    }

    @Test
    fun `an empty (or fully-excluded) input produces a zero-total summary with no lines`() {
        val fees = listOf(fee("waived-fee", "Waived fee", "0.00", status = PostingStatus.NOT_APPLICABLE))

        val summary = AnnualFeeSummary.aggregate("acc-1", "party-1", 2026, "CZK", fees, interestRate = null)

        assertThat(summary.fees).isEmpty()
        assertThat(summary.totalFees).isEqualByComparingTo("0")
    }

    @Test
    fun `a fee whose display name changed mid-year does not silently merge into one line`() {
        val fees = listOf(
            fee("maintenance", "Monthly account maintenance", "50.00", cycleId = "2026-01"),
            fee("maintenance", "Monthly maintenance fee (renamed)", "55.00", cycleId = "2026-06"),
        )

        val summary = AnnualFeeSummary.aggregate("acc-1", "party-1", 2026, "CZK", fees, interestRate = null)

        assertThat(summary.fees).hasSize(2)
        assertThat(summary.fees).allMatch { it.code == "maintenance" }
        assertThat(summary.totalFees).isEqualByComparingTo("105.00")
    }

    @Test
    fun `carries accountId, partyRef, year, currency and a null interestRate through untouched`() {
        val summary = AnnualFeeSummary.aggregate(
            accountId = "acc-42",
            partyRef = "party-42",
            year = 2025,
            currency = "EUR",
            candidateFees = emptyList(),
            interestRate = null,
        )

        assertThat(summary.accountId).isEqualTo("acc-42")
        assertThat(summary.partyRef).isEqualTo("party-42")
        assertThat(summary.year).isEqualTo(2025)
        assertThat(summary.currency).isEqualTo("EUR")
        assertThat(summary.interestRate).isNull()
    }
}
