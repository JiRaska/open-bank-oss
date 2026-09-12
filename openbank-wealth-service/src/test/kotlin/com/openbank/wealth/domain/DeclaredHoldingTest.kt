// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.domain

import com.openbank.wealth.domain.model.DeclaredHolding
import com.openbank.wealth.domain.model.HoldingStatus
import com.openbank.wealth.domain.model.HoldingType
import com.openbank.wealth.domain.model.Valuation
import com.openbank.wealth.domain.model.ValuationSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class DeclaredHoldingTest {

    private val now: Instant = Instant.parse("2026-09-12T10:00:00Z")
    private val later: Instant = now.plusSeconds(3600)

    private fun valuation(
        amount: String = "1000000.00",
        source: ValuationSource = ValuationSource.CUSTOMER_DECLARED,
        appraiser: String? = null,
    ) = Valuation(
        amount = BigDecimal(amount),
        currency = "CZK",
        valuedAt = LocalDate.of(2026, 9, 1),
        source = source,
        appraiserReference = appraiser,
    )

    private fun holding(
        type: HoldingType = HoldingType.REAL_ESTATE,
        share: String = "1",
        status: HoldingStatus = HoldingStatus.ACTIVE,
        loanId: UUID? = null,
    ) = DeclaredHolding(
        id = UUID.randomUUID(),
        ownerPartyId = UUID.randomUUID(),
        holdingType = type,
        label = "Flat, Prague 2",
        valuation = valuation(),
        ownershipShare = BigDecimal(share),
        status = status,
        pledgedToLoanId = loanId,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `an expert appraisal must name its appraiser`() {
        assertThatThrownBy { valuation(source = ValuationSource.EXPERT_APPRAISAL) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("appraiser")

        // And the same source WITH a reference is accepted — without this half the assertion
        // above would pass even if the rule rejected every appraisal unconditionally.
        assertThat(valuation(source = ValuationSource.EXPERT_APPRAISAL, appraiser = "APP-1").source)
            .isEqualTo(ValuationSource.EXPERT_APPRAISAL)
    }

    @Test
    fun `a negative amount is refused`() {
        assertThatThrownBy { valuation(amount = "-1") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ownership share must be within nought exclusive and one inclusive`() {
        assertThatThrownBy { holding(share = "0") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { holding(share = "1.5") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(holding(share = "0.5").ownershipShare).isEqualByComparingTo("0.5")
    }

    @Test
    fun `attributable amount applies the ownership share`() {
        assertThat(holding(share = "0.25").attributableAmount).isEqualByComparingTo("250000.00")
    }

    @Test
    fun `PLEDGED and the loan id must agree in both directions`() {
        assertThatThrownBy { holding(status = HoldingStatus.PLEDGED, loanId = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { holding(status = HoldingStatus.ACTIVE, loanId = UUID.randomUUID()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a pledged holding cannot be withdrawn`() {
        val loanId = UUID.randomUUID()
        val pledged = holding().pledge(loanId, later)

        assertThatThrownBy { pledged.withdraw(later) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(loanId.toString())
    }

    @Test
    fun `releasing makes a pledged holding withdrawable again`() {
        val released = holding().pledge(UUID.randomUUID(), later).release(later)

        assertThat(released.status).isEqualTo(HoldingStatus.ACTIVE)
        assertThat(released.pledgedToLoanId).isNull()
        assertThat(released.withdraw(later).status).isEqualTo(HoldingStatus.WITHDRAWN)
    }

    @Test
    fun `a withdrawn holding cannot be revalued or withdrawn twice`() {
        val withdrawn = holding().withdraw(later)

        assertThatThrownBy { withdrawn.revalue(valuation("2"), later) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { withdrawn.withdraw(later) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `revaluing moves the value and the updated timestamp, and nothing else`() {
        val original = holding()
        val revalued = original.revalue(valuation("2000000.00"), later)

        assertThat(revalued.valuation.amount).isEqualByComparingTo("2000000.00")
        assertThat(revalued.updatedAt).isEqualTo(later)
        assertThat(revalued.createdAt).isEqualTo(original.createdAt)
        assertThat(revalued.id).isEqualTo(original.id)
        assertThat(revalued.status).isEqualTo(original.status)
    }

    @Test
    fun `side is fixed by the type, and every type declares one`() {
        // Asserted over the WHOLE enum, not a sample: a value added later is covered the day it is
        // added, which a hand-listed pair of cases would not be. The point of deriving side from
        // the type is that REAL_ESTATE + LIABILITY is unrepresentable — there is no second field
        // to disagree with.
        assertThat(HoldingType.entries.filter { it.isLiability })
            .containsExactlyInAnyOrder(
                HoldingType.MORTGAGE,
                HoldingType.CONSUMER_CREDIT,
                HoldingType.PRIVATE_DEBT,
                HoldingType.OTHER_LIABILITY,
            )
        assertThat(HoldingType.entries.filterNot { it.isLiability }).hasSize(7)
        assertThat(HoldingType.entries.map { it.side }).doesNotContainNull()
    }

    @Test
    fun `valuation age is measured from the date the value asserts, not from the row`() {
        // The whole reason the field is on the wire: a 2019 number and a 2026 number are otherwise
        // indistinguishable to a consumer that does not do this subtraction itself.
        val h = holding()
        assertThat(h.valuationAgeDays(LocalDate.of(2026, 9, 12))).isEqualTo(11)
        assertThat(h.valuationAgeDays(LocalDate.of(2026, 9, 1))).isZero()
    }

    @Test
    fun `only an active holding can be pledged`() {
        val pledged = holding().pledge(UUID.randomUUID(), later)
        assertThatThrownBy { pledged.pledge(UUID.randomUUID(), later) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
