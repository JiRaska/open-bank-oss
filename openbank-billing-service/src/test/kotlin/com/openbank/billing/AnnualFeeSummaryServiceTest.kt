// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.AccountPartyLookupPort
import com.openbank.billing.application.port.out.BillableAccountDiscoveryPort
import com.openbank.billing.application.port.out.BillableAccountsPage
import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.usecase.AnnualFeeSummaryService
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.PostingStatus
import com.openbank.libs.product.WaiveReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit coverage for [AnnualFeeSummaryService] (ADR-0248): the year-boundary read, fail-closed
 * skip on an unresolvable party, idempotent-append delegation, and per-account isolation across a
 * discovered batch.
 */
class AnnualFeeSummaryServiceTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-15T05:00:00Z"), ZoneOffset.UTC)

    private fun postedFee(feeId: String, amount: String) = AssessedFee(
        cycleId = "2025-06",
        accountId = "acc-1",
        feeId = feeId,
        name = "Fee $feeId",
        currency = "CZK",
        chargedAmount = BigDecimal(amount),
        waived = false,
        reason = WaiveReason.NOT_WAIVABLE,
        postingStatus = PostingStatus.POSTED,
    )

    @Test
    fun `publishForAccount reads the calendar-year window and appends the aggregated summary`(): Unit = runBlocking {
        val assessments = mockk<BillingAssessmentRepository>()
        val partyLookup = mockk<AccountPartyLookupPort>()
        val discovery = mockk<BillableAccountDiscoveryPort>()
        coEvery { partyLookup.partyIdFor("acc-1") } returns "party-1"
        coEvery { assessments.postedFeesForAccount("acc-1", any(), any()) } returns listOf(postedFee("f1", "50.00"))
        coEvery { assessments.appendAnnualFeeSummaryEvent(any(), any()) } returns true

        val service = AnnualFeeSummaryService(assessments, partyLookup, discovery, clock)
        val summary = service.publishForAccount("acc-1", 2025, "CZK")

        assertThat(summary).isNotNull
        assertThat(summary!!.accountId).isEqualTo("acc-1")
        assertThat(summary.partyRef).isEqualTo("party-1")
        assertThat(summary.year).isEqualTo(2025)
        assertThat(summary.totalFees).isEqualByComparingTo("50.00")

        val fromSlot = slot<Instant>()
        val toSlot = slot<Instant>()
        coVerify(exactly = 1) {
            assessments.postedFeesForAccount("acc-1", capture(fromSlot), capture(toSlot))
        }
        val prague = ZoneId.of("Europe/Prague")
        assertThat(fromSlot.captured).isEqualTo(LocalDate.of(2025, 1, 1).atStartOfDay(prague).toInstant())
        assertThat(toSlot.captured).isEqualTo(LocalDate.of(2026, 1, 1).atStartOfDay(prague).toInstant())
        coVerify(exactly = 1) {
            assessments.appendAnnualFeeSummaryEvent(match { it.accountId == "acc-1" && it.year == 2025 }, any())
        }
    }

    @Test
    fun `publishForAccount skips (returns null, appends nothing) when the party cannot be resolved`(): Unit =
        runBlocking {
            val assessments = mockk<BillingAssessmentRepository>()
            val partyLookup = mockk<AccountPartyLookupPort>()
            val discovery = mockk<BillableAccountDiscoveryPort>()
            coEvery { partyLookup.partyIdFor("acc-unknown") } returns null

            val service = AnnualFeeSummaryService(assessments, partyLookup, discovery, clock)
            val summary = service.publishForAccount("acc-unknown", 2025, "CZK")

            assertThat(summary).isNull()
            coVerify(exactly = 0) { assessments.postedFeesForAccount(any(), any(), any()) }
            coVerify(exactly = 0) { assessments.appendAnnualFeeSummaryEvent(any(), any()) }
        }

    @Test
    fun `publishForAllAccounts pages through discovery and continues past one account's failure`(): Unit = runBlocking {
        val assessments = mockk<BillingAssessmentRepository>()
        val partyLookup = mockk<AccountPartyLookupPort>()
        val discovery = mockk<BillableAccountDiscoveryPort>()

        coEvery { discovery.activeAccounts(2, null) } returns
            BillableAccountsPage(listOf("acc-good", "acc-bad"), "cursor-2")
        coEvery { discovery.activeAccounts(2, "cursor-2") } returns BillableAccountsPage(emptyList(), null)

        coEvery { partyLookup.partyIdFor("acc-good") } returns "party-good"
        coEvery { partyLookup.partyIdFor("acc-bad") } throws RuntimeException("boom")

        coEvery { assessments.postedFeesForAccount("acc-good", any(), any()) } returns emptyList()
        coEvery { assessments.appendAnnualFeeSummaryEvent(any(), any()) } returns true

        val service = AnnualFeeSummaryService(assessments, partyLookup, discovery, clock)
        val result = service.publishForAllAccounts(2025, "CZK", pageSize = 2)

        assertThat(result.published).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(1)
    }
}
