// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.funding

import com.openbank.pension.domain.contribution.Contribution
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.incentive.ContractYearInput
import com.openbank.pension.domain.incentive.IncentiveEngine
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Golden cases against the SHIPPED CZ packs (loaded through the real loader, so the JSON is what
 * is tested). The pack values are REQUIRES_LEGAL_REVIEW reference data; these cases pin how the
 * engine reads them, not that the law says so.
 */
class CzPackGoldenTest {

    private val registry = JurisdictionPackLoader.loadRegistry()
    private val dps = registry.pinned("CZ", ProductLine.DPS, 2)
    private val dip = registry.pinned("CZ", ProductLine.DIP, 1)

    private fun pay(
        contract: UUID,
        amount: String,
        date: LocalDate,
        source: ContributionSource = ContributionSource.PARTICIPANT,
        employer: UUID? = null,
    ) = Contribution(
        UUID.randomUUID(), contract, "g-${UUID.randomUUID()}", source, ContributionChannel.STANDING_ORDER,
        BigDecimal(amount), "CZK", date, employerPartyId = employer, receivedAt = Instant.EPOCH,
    )

    @ParameterizedTest(name = "monthly {0} CZK -> state contribution {1}")
    @CsvSource("499, 0.00", "500, 100.00", "1000, 200.00", "1700, 340.00", "5000, 340.00")
    fun `DPS monthly state contribution`(monthly: String, expected: String) {
        val c = UUID.randomUUID()
        val drafts = IncentiveEngine.claimsFor(
            dps,
            YearMonth.of(2026, 1),
            listOf(pay(c, monthly, LocalDate.of(2026, 1, 20))),
        )
        val amount = drafts.firstOrNull { it.incentiveId == "state-contribution" }?.amount ?: BigDecimal("0.00")
        assertThat(amount).isEqualByComparingTo(expected)
    }

    @ParameterizedTest(name = "two payments in a month are summed: {0} + {1} -> {2}")
    @CsvSource("300, 300, 120.00", "1000, 1000, 340.00", "200, 200, 0.00")
    fun `DPS state contribution is per month, not per payment`(a: String, b: String, expected: String) {
        val c = UUID.randomUUID()
        val drafts = IncentiveEngine.claimsFor(
            dps,
            YearMonth.of(2026, 2),
            listOf(
                pay(c, a, LocalDate.of(2026, 2, 1)),
                pay(c, b, LocalDate.of(2026, 2, 27)),
                pay(c, "9999", LocalDate.of(2026, 3, 1)),
            ),
        )
        val amount = drafts.firstOrNull()?.amount ?: BigDecimal("0.00")
        assertThat(amount).isEqualByComparingTo(expected)
    }

    @ParameterizedTest(name = "DPS annual {0} CZK -> deductible {1}")
    @CsvSource("20400, 0.00", "24000, 3600.00", "60000, 39600.00", "68400, 48000.00", "120000, 48000.00")
    fun `DPS tax deduction above the threshold up to the cap`(annual: String, expected: String) {
        val id = UUID.randomUUID()
        val alloc = IncentiveEngine.allocateTaxYear(
            listOf(ContractYearInput(id, dps, BigDecimal(annual), emptyList())),
            emptyMap(),
        )
        assertThat(alloc.getValue(id).deductible).isEqualByComparingTo(expected)
    }

    @org.junit.jupiter.api.Test
    fun `DPS and DIP share ONE deduction cap per participant`() {
        val dpsId = UUID.randomUUID()
        val dipId = UUID.randomUUID()
        val alloc = IncentiveEngine.allocateTaxYear(
            listOf(
                ContractYearInput(dpsId, dps, BigDecimal("50400"), emptyList()), // 30000 above threshold
                ContractYearInput(dipId, dip, BigDecimal("30000"), emptyList()), // would be 30000 alone
            ),
            emptyMap(),
        )
        assertThat(alloc.getValue(dpsId).deductible).isEqualByComparingTo("30000.00")
        assertThat(alloc.getValue(dipId).deductible).isEqualByComparingTo("18000.00")
        assertThat(
            alloc.getValue(dipId).usedElsewhere["retirement-products-deduction"],
        ).isEqualByComparingTo("30000.00")
    }

    @org.junit.jupiter.api.Test
    fun `usage declared at another provider reduces the cap here`() {
        val id = UUID.randomUUID()
        val alloc = IncentiveEngine.allocateTaxYear(
            listOf(ContractYearInput(id, dip, BigDecimal("40000"), emptyList())),
            mapOf("retirement-products-deduction" to BigDecimal("20000")),
        )
        assertThat(alloc.getValue(id).deductible).isEqualByComparingTo("28000.00")
        assertThat(alloc.getValue(id).indicativeSaving).isEqualByComparingTo("4200.00")
    }

    @org.junit.jupiter.api.Test
    fun `employer exemption is consumed first come first served across employers`() {
        val id = UUID.randomUUID()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val alloc = IncentiveEngine.allocateTaxYear(
            listOf(
                ContractYearInput(
                    id,
                    dps,
                    BigDecimal.ZERO,
                    listOf(
                        pay(id, "30000", LocalDate.of(2026, 3, 1), ContributionSource.EMPLOYER, a),
                        pay(id, "30000", LocalDate.of(2026, 6, 1), ContributionSource.EMPLOYER, b),
                    ),
                ),
            ),
            emptyMap(),
        )
        val ex = alloc.getValue(id).employerExemptions.associateBy { it.employerPartyId }
        assertThat(ex.getValue(a).exempt).isEqualByComparingTo("30000.00")
        assertThat(ex.getValue(b).exempt).isEqualByComparingTo("20000.00")
        assertThat(ex.getValue(b).taxable).isEqualByComparingTo("10000.00")
    }

    @org.junit.jupiter.api.Test
    fun `the CZ DPS state contribution names its claim format and DIP has no state claim`() {
        assertThat(
            IncentiveEngine.claimableRules(dps).map {
                it.claimFormat
            },
        ).containsExactly("cz-mf-state-contribution-v1")
        assertThat(IncentiveEngine.claimableRules(dip)).isEmpty()
    }
}
