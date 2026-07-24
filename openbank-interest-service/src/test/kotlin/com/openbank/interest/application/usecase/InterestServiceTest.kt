// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.out.AccountDirectoryPort
import com.openbank.interest.application.port.out.AccountPage
import com.openbank.interest.application.port.out.AccountSnapshot
import com.openbank.interest.application.port.out.BalanceSnapshot
import com.openbank.interest.application.port.out.CapitalizationPosting
import com.openbank.interest.application.port.out.InterestAccrualRepository
import com.openbank.interest.application.port.out.InterestCapitalizationRepository
import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.application.port.out.LedgerPostingPort
import com.openbank.interest.application.port.out.TaxProfilePort
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.AccrualStatus
import com.openbank.interest.domain.model.AccrualSummary
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.domain.model.InterestRateType
import com.openbank.interest.domain.model.RateConfigNotFoundException
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.TaxResidency
import com.openbank.interest.domain.tax.TaxpayerType
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.interest.domain.tax.WithholdingTreatment
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class InterestServiceTest {

    private val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val configRepo = mockk<InterestRateConfigRepository>()
    private val accrualRepo = mockk<InterestAccrualRepository>()
    private val capitalizationRepo = mockk<InterestCapitalizationRepository>()
    private val taxProfilePort = mockk<TaxProfilePort>()
    private val ledgerPostingPort = mockk<LedgerPostingPort>()
    private val accountDirectoryPort = mockk<AccountDirectoryPort>()
    private val service = InterestService(
        configRepo,
        accrualRepo,
        capitalizationRepo,
        taxProfilePort,
        ledgerPostingPort,
        accountDirectoryPort,
        "ACT_365",
        "SAVINGS",
        clock,
    )

    // Captures of the single-transaction capitalization write, filled by stubCapitalization().
    private val capSlot: CapturingSlot<InterestCapitalization> = slot()
    private val whtSlot: CapturingSlot<WithholdingTax> = slot()
    private val eventSlot: CapturingSlot<OutboxMessage> = slot()
    private val accrualIdsSlot: CapturingSlot<List<UUID>> = slot()

    /** Captures the ADR-0033 §D credit leg handed to the ledger, filled by stubCapitalization(). */
    private val postingSlot: CapturingSlot<CapitalizationPosting> = slot()

    /** Captures the ACCRUING -> CAPITALIZING claim the use case takes before it posts. */
    private val claimIdsSlot: CapturingSlot<List<UUID>> = slot()
    private val claimPeriodSlot: CapturingSlot<LocalDate> = slot()
    private val claimProfileSlot: CapturingSlot<TaxProfile> = slot()

    /**
     * The default world: nothing is CAPITALIZING, so capitalize() takes a fresh ACCRUING set and
     * claims it. The claim is the finding-2 fix — it freezes the exact accrual set the ledger is
     * about to be told about, because the idempotency key carries no amount.
     */
    private fun stubNoClaimOutstanding() {
        every { accrualRepo.findClaimedForCapitalization(any(), any()) } returns Uni.createFrom().item(emptyList())
        every {
            accrualRepo.claimForCapitalization(
                capture(claimIdsSlot),
                capture(claimPeriodSlot),
                capture(claimProfileSlot),
            )
        } returns Uni.createFrom().item(Unit)
    }

    /**
     * Stubs the profile lookup, the ledger credit leg, and the ONE atomic write the use case
     * performs: capitalization + withholding + outbox event + the guarded accrual flip all land
     * through `saveWithOutbox`, so there is nothing else for the service to call.
     */
    private fun stubCapitalization(profile: TaxProfile = TaxProfile.FAIL_SAFE_DEFAULT) {
        stubNoClaimOutstanding()
        every { taxProfilePort.resolve(any()) } returns Uni.createFrom().item(profile)
        every { ledgerPostingPort.post(capture(postingSlot)) } returns Uni.createFrom().item(Unit)
        every {
            capitalizationRepo.saveWithOutbox(
                capture(capSlot),
                capture(whtSlot),
                capture(eventSlot),
                capture(accrualIdsSlot),
                any(),
            )
        } answers { Uni.createFrom().item(firstArg<InterestCapitalization>()) }
    }

    @Test
    fun `accrue calculates daily rate correctly`() {
        val request = AccrualRequest(
            accountId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            productId = "SAVINGS",
            balance = BigDecimal("1000.00"),
            currency = "EUR",
            accrualDate = LocalDate.of(2026, 1, 20),
        )
        val config = sampleConfig(annualRate = BigDecimal("0.365"), dayCount = DayCount.ACT_365)
        val accrualSlot: CapturingSlot<InterestAccrual> = slot()

        every {
            configRepo.findEffectiveRate(request.accountId, request.productId, request.accrualDate, request.currency)
        } returns Uni.createFrom().item(config)
        every { accrualRepo.save(capture(accrualSlot)) } returns
            Uni.createFrom().item(expectedAccrual(request, config))

        val result = service.accrue(request).await().indefinitely()

        assertThat(accrualSlot.captured.dailyRate).isEqualByComparingTo(BigDecimal("0.0010000000"))
        assertThat(accrualSlot.captured.accruedAmount).isEqualByComparingTo(BigDecimal("1.000000"))
        assertThat(result).isEqualTo(expectedAccrual(request, config))
        verify(exactly = 1) {
            configRepo.findEffectiveRate(request.accountId, request.productId, request.accrualDate, request.currency)
        }
        verify(exactly = 1) { accrualRepo.save(any()) }
    }

    @Test
    fun `accrue throws when no config found`() {
        val request = AccrualRequest(
            accountId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            productId = "SAVINGS",
            balance = BigDecimal("1000.00"),
            currency = "EUR",
            accrualDate = LocalDate.of(2026, 1, 20),
        )

        every {
            configRepo.findEffectiveRate(request.accountId, request.productId, request.accrualDate, request.currency)
        } returns Uni.createFrom().nullItem()

        assertThatThrownBy { service.accrue(request).await().indefinitely() }
            .isInstanceOf(RateConfigNotFoundException::class.java)
            .hasMessage("No active rate config for product SAVINGS in currency EUR")
        verify(exactly = 0) { accrualRepo.save(any()) }
    }

    @Test
    fun `accrueAll walks all pages, accrues only accruable types, and returns the written count`() {
        val date = LocalDate.of(2026, 1, 20)
        val savingsA = UUID.fromString("aaaa0000-0000-0000-0000-000000000001")
        val currentB = UUID.fromString("bbbb0000-0000-0000-0000-000000000002")
        val savingsC = UUID.fromString("cccc0000-0000-0000-0000-000000000003")
        val config = sampleConfig(annualRate = BigDecimal("0.365"))

        // Two pages; a CURRENT account sits between the two SAVINGS ones and must be filtered out
        // BEFORE its balance is ever read.
        every { accountDirectoryPort.listActiveAccounts(null, any()) } returns Uni.createFrom().item(
            AccountPage(
                items = listOf(
                    AccountSnapshot(savingsA, "SAVINGS_PRODUCT", "SAVINGS", "CZK"),
                    AccountSnapshot(currentB, "CURRENT_PRODUCT", "CURRENT", "CZK"),
                ),
                nextCursor = "cursor-1",
            ),
        )
        every { accountDirectoryPort.listActiveAccounts("cursor-1", any()) } returns Uni.createFrom().item(
            AccountPage(
                items = listOf(AccountSnapshot(savingsC, "SAVINGS_PRODUCT", "savings", "CZK")),
                nextCursor = null,
            ),
        )
        every { accountDirectoryPort.bookedBalance(savingsA) } returns
            Uni.createFrom().item(BalanceSnapshot(BigDecimal("1000.00"), "CZK"))
        every { accountDirectoryPort.bookedBalance(savingsC) } returns
            Uni.createFrom().item(BalanceSnapshot(BigDecimal("2000.00"), "CZK"))
        every { configRepo.findEffectiveRate(any(), any(), date, any()) } returns Uni.createFrom().item(config)
        every { accrualRepo.save(any()) } answers { Uni.createFrom().item(firstArg<InterestAccrual>()) }

        val count = service.accrueAll(date).await().indefinitely()

        assertThat(count).isEqualTo(2)
        verify(exactly = 1) { accountDirectoryPort.bookedBalance(savingsA) }
        verify(exactly = 1) { accountDirectoryPort.bookedBalance(savingsC) }
        // The CURRENT account is filtered by type — its balance is never even fetched.
        verify(exactly = 0) { accountDirectoryPort.bookedBalance(currentB) }
    }

    @Test
    fun `accrueAll skips zero balance and survives a per-account accrual failure without aborting`() {
        val date = LocalDate.of(2026, 1, 20)
        val zero = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val dup = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
        val ok = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
        val config = sampleConfig(annualRate = BigDecimal("0.365"))

        every { accountDirectoryPort.listActiveAccounts(null, any()) } returns Uni.createFrom().item(
            AccountPage(
                items = listOf(
                    AccountSnapshot(zero, "SAVINGS_PRODUCT", "SAVINGS", "CZK"),
                    AccountSnapshot(dup, "SAVINGS_PRODUCT", "SAVINGS", "CZK"),
                    AccountSnapshot(ok, "SAVINGS_PRODUCT", "SAVINGS", "CZK"),
                ),
                nextCursor = null,
            ),
        )
        every { accountDirectoryPort.bookedBalance(zero) } returns
            Uni.createFrom().item(BalanceSnapshot(BigDecimal.ZERO, "CZK"))
        every { accountDirectoryPort.bookedBalance(dup) } returns
            Uni.createFrom().item(BalanceSnapshot(BigDecimal("500.00"), "CZK"))
        every { accountDirectoryPort.bookedBalance(ok) } returns
            Uni.createFrom().item(BalanceSnapshot(BigDecimal("1000.00"), "CZK"))
        every { configRepo.findEffectiveRate(any(), any(), date, any()) } returns Uni.createFrom().item(config)
        // The duplicate account simulates the UNIQUE(account, date, product) violation on re-run.
        every { accrualRepo.save(match { it.accountId == dup }) } returns
            Uni.createFrom().failure(IllegalStateException("duplicate key value violates unique constraint"))
        every { accrualRepo.save(match { it.accountId == ok }) } answers
            { Uni.createFrom().item(firstArg<InterestAccrual>()) }

        val count = service.accrueAll(date).await().indefinitely()

        assertThat(count).isEqualTo(1)
        // Zero-balance account is skipped before any accrual save is attempted.
        verify(exactly = 0) { accrualRepo.save(match { it.accountId == zero }) }
    }

    @Test
    fun `capitalize sums accruals and credits gross for non-CZK (deferred withholding)`() {
        val accountId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val productId = "SAVINGS"
        val toDate = LocalDate.of(2026, 1, 20)
        // EUR interest: §38 conversion not in v1 scope -> DEFERRED_FX, credited gross (ADR-0033 §E).
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("1.20")),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("2.30")),
        )
        stubCapitalization()

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        val result = service.capitalize(accountId, productId, toDate).await().indefinitely()

        assertThat(capSlot.captured.totalAccrued).isEqualByComparingTo(BigDecimal("3.50"))
        assertThat(capSlot.captured.grossAmount).isEqualByComparingTo(BigDecimal("3.5000"))
        assertThat(capSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal("0"))
        assertThat(capSlot.captured.capitalizedAmount).isEqualByComparingTo(BigDecimal("3.5000"))
        assertThat(capSlot.captured.periodFrom).isEqualTo(LocalDate.of(2026, 1, 18))
        assertThat(capSlot.captured.periodTo).isEqualTo(toDate)
        assertThat(whtSlot.captured.treatment).isEqualTo(WithholdingTreatment.DEFERRED_FX)
        assertThat(whtSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal("0"))
        assertThat(result.capitalizedAmount).isEqualByComparingTo(BigDecimal("3.5000"))
        // The product filter is part of the query, not a post-filter: the repo must never be asked
        // for an account's accruals across products.
        verify(exactly = 1) { accrualRepo.findPendingCapitalization(accountId, productId, toDate) }
    }

    @Test
    fun `capitalizeAll capitalizes every pending pair and recovers a per-pair failure`() {
        val toDate = LocalDate.of(2026, 1, 20)
        val prod = "SAVINGS"
        val accountA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val accountB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        stubCapitalization()

        every { accrualRepo.findAccountsWithPendingCapitalization(toDate) } returns
            Uni.createFrom().item(listOf(accountA to prod, accountB to prod))
        // A has a pending accrual → capitalizes; B's set is empty by the time it runs (a lost race) →
        // capitalize() fails, and capitalizeAll must recover per-pair rather than abort the whole run.
        every { accrualRepo.findPendingCapitalization(accountA, prod, toDate) } returns
            Uni.createFrom().item(listOf(sampleAccrual(accountA, prod, LocalDate.of(2026, 1, 18), BigDecimal("1.50"))))
        every { accrualRepo.findPendingCapitalization(accountB, prod, toDate) } returns
            Uni.createFrom().item(emptyList())

        val count = service.capitalizeAll(toDate).await().indefinitely()

        assertThat(count).isEqualTo(1) // only A capitalized; B's failure was swallowed, not propagated
        verify(exactly = 1) { accrualRepo.findAccountsWithPendingCapitalization(toDate) }
        verify(exactly = 1) { accrualRepo.findPendingCapitalization(accountA, prod, toDate) }
        verify(exactly = 1) { accrualRepo.findPendingCapitalization(accountB, prod, toDate) }
    }

    @Test
    fun `capitalizeAll returns 0 and does nothing when no pair is pending`() {
        val toDate = LocalDate.of(2026, 1, 20)
        every { accrualRepo.findAccountsWithPendingCapitalization(toDate) } returns
            Uni.createFrom().item(emptyList())

        assertThat(service.capitalizeAll(toDate).await().indefinitely()).isEqualTo(0)
        verify(exactly = 0) { accrualRepo.findPendingCapitalization(any(), any(), any()) }
    }

    @Test
    fun `capitalize withholds 15 percent and credits net on CZK interest`() {
        val accountId = UUID.fromString("88888888-8888-8888-8888-888888888888")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("60.00"), currency = "CZK"),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("40.00"), currency = "CZK"),
        )
        stubCapitalization() // resident individual default -> 15 %

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        val result = service.capitalize(accountId, productId, toDate).await().indefinitely()

        // gross 100.00 CZK -> base 100, tax 15, net 85.
        assertThat(capSlot.captured.grossAmount).isEqualByComparingTo(BigDecimal("100.0000"))
        assertThat(capSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal("15"))
        assertThat(capSlot.captured.netAmount).isEqualByComparingTo(BigDecimal("85.0000"))
        assertThat(capSlot.captured.capitalizedAmount).isEqualByComparingTo(BigDecimal("85.0000"))
        assertThat(whtSlot.captured.treatment).isEqualTo(WithholdingTreatment.WITHHELD)
        assertThat(whtSlot.captured.rate).isEqualByComparingTo(BigDecimal("0.15"))
        assertThat(whtSlot.captured.taxableBase).isEqualByComparingTo(BigDecimal("100"))
        assertThat(whtSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal("15"))
        assertThat(whtSlot.captured.capitalizationId).isEqualTo(capSlot.captured.id)
        assertThat(result.capitalizedAmount).isEqualByComparingTo(BigDecimal("85.0000"))
    }

    @Test
    fun `capitalization, withholding, event and accrual flip are handed to ONE transaction`() {
        val accountId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("60.00"), currency = "CZK"),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("40.00"), currency = "CZK"),
        )
        stubCapitalization()

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        service.capitalize(accountId, productId, toDate).await().indefinitely()

        // Exactly one write call: the four legs of a credit can no longer be committed separately.
        verify(exactly = 1) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
        // ...and the accrual flip is part of that same call, carrying every source accrual.
        assertThat(accrualIdsSlot.captured).containsExactlyElementsOf(accruals.map { it.id })
        assertThat(eventSlot.captured.eventType).isEqualTo("interest.withholding.recorded.v1")
        assertThat(eventSlot.captured.aggregateId).isEqualTo(capSlot.captured.id)
        assertThat(eventSlot.captured.payload).contains("\"taxAmount\":\"15\"")
    }

    @Test
    fun `a failing single transaction surfaces the failure and writes nothing else`() {
        val accountId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("60.00"), currency = "CZK"),
        )

        stubNoClaimOutstanding()
        every { taxProfilePort.resolve(any()) } returns Uni.createFrom().item(TaxProfile.FAIL_SAFE_DEFAULT)
        every { ledgerPostingPort.post(any()) } returns Uni.createFrom().item(Unit)
        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)
        // Mirrors the repo's status guard tripping (a concurrent run flipped the accruals first):
        // the whole write set rolls back, so the use case must propagate, not swallow-and-continue.
        every { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) } returns
            Uni.createFrom().failure(IllegalStateException("Capitalization aborted: expected to flip 1, matched 0"))

        assertThatThrownBy { service.capitalize(accountId, productId, toDate).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Capitalization aborted")
    }

    @Test
    fun `the ledger credit leg carries gross-net-tax and the customer sub-ledger`() {
        val accountId = UUID.fromString("77777777-7777-7777-7777-777777777777")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("100.00"), currency = "CZK"),
        )
        stubCapitalization()

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        service.capitalize(accountId, productId, toDate).await().indefinitely()

        // Without this the bank remits withholding tax on interest it never credited (the ADR-0033
        // §D hole): the split must reach the ledger, not just interest-service's own tables.
        verify(exactly = 1) { ledgerPostingPort.post(any()) }
        assertThat(postingSlot.captured.accountId).isEqualTo(accountId)
        assertThat(postingSlot.captured.currency).isEqualTo("CZK")
        assertThat(postingSlot.captured.gross.amount).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(postingSlot.captured.tax.amount).isEqualByComparingTo(BigDecimal("15"))
        assertThat(postingSlot.captured.net.amount).isEqualByComparingTo(BigDecimal("85.00"))
        // gross = net + tax, so the three-leg entry balances within CZK.
        assertThat(postingSlot.captured.gross.amount)
            .isEqualByComparingTo(postingSlot.captured.net.amount.add(postingSlot.captured.tax.amount))
        // NOT isEqualByComparingTo: scale IS the property that broke (finding 1). The ledger wraps
        // every line in Money.of(amount, currencyCode) and Money refuses scale > the currency's
        // minor units, so a scale-4 gross 400s the whole capitalization. isEqualByComparingTo
        // ignores scale by construction, which is precisely why the old suite could not see it.
        assertThat(postingSlot.captured.gross.amount.scale()).isEqualTo(2)
        assertThat(postingSlot.captured.net.amount.scale()).isEqualTo(2)
    }

    @Test
    fun `a ledger failure leaves NO capitalization row (post before transaction)`() {
        val accountId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("100.00"), currency = "CZK"),
        )

        stubNoClaimOutstanding()
        every { taxProfilePort.resolve(any()) } returns Uni.createFrom().item(TaxProfile.FAIL_SAFE_DEFAULT)
        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)
        every { ledgerPostingPort.post(any()) } returns
            Uni.createFrom().failure(IllegalStateException("ledger unavailable"))

        assertThatThrownBy { service.capitalize(accountId, productId, toDate).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ledger unavailable")

        // The whole point of posting BEFORE the local write: a capitalization row that the GL knows
        // nothing about is unrepairable (its accruals are already CAPITALIZED, so no retry revisits
        // them). Failing here leaves the accruals CAPITALIZING — claimed, so the retry re-derives
        // the identical amount and key, and simply completes.
        verify(exactly = 0) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { accrualRepo.claimForCapitalization(accruals.map { it.id }, toDate, any()) }
    }

    @Test
    fun `a zero-gross period books no journal but still records the capitalization`() {
        val accountId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        // A zero-balance account still runs the accrual pass; it accrues nothing worth crediting.
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("0.00"), currency = "CZK"),
        )
        stubCapitalization()

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        service.capitalize(accountId, productId, toDate).await().indefinitely()

        // Every leg would be zero, and the ledger requires >=2 lines each with amount > 0.
        verify(exactly = 0) { ledgerPostingPort.post(any()) }
        verify(exactly = 1) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
        assertThat(capSlot.captured.grossAmount).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `capitalize refuses a mixed-currency accrual set instead of summing it`() {
        val accountId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val productId = "SAVINGS"
        val toDate = LocalDate.of(2026, 1, 20)
        // 100 CZK + 5 EUR is not "105" of anything, and only the CZK leg is withholdable (§E).
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("100.00"), currency = "CZK"),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("5.00"), currency = "EUR"),
        )

        stubNoClaimOutstanding()
        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        assertThatThrownBy { service.capitalize(accountId, productId, toDate).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("mixed-currency")
            .hasMessageContaining("[CZK, EUR]")
        // Nothing is credited, nothing is withheld, and the accruals stay ACCRUING for an operator:
        // the currency check runs BEFORE the claim, so there is nothing to unwind.
        verify(exactly = 0) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { accrualRepo.claimForCapitalization(any(), any(), any()) }
        verify(exactly = 0) { taxProfilePort.resolve(any()) }
    }

    @Test
    fun `a single currency in mixed casing is one currency, not a mixed set`() {
        val accountId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("60.00"), currency = "czk"),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("40.00"), currency = "CZK"),
        )
        stubCapitalization()

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        service.capitalize(accountId, productId, toDate).await().indefinitely()

        // Normalized to the canonical code, and still withheld as CZK interest.
        assertThat(capSlot.captured.currency).isEqualTo("CZK")
        assertThat(whtSlot.captured.currency).isEqualTo("CZK")
        assertThat(whtSlot.captured.treatment).isEqualTo(WithholdingTreatment.WITHHELD)
        assertThat(capSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal("15"))
    }

    @Test
    fun `capitalize throws when no pending accruals`() {
        val accountId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val productId = "SAVINGS"
        val toDate = LocalDate.of(2026, 1, 20)

        stubNoClaimOutstanding()
        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(emptyList())

        assertThatThrownBy { service.capitalize(accountId, productId, toDate).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("No pending accruals to capitalize")
        verify(exactly = 0) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
    }

    // --- The claim (finding 2: the idempotency key is amount-blind) ----------------------------

    @Test
    fun `capitalize claims the accrual set BEFORE it tells the ledger anything`() {
        val accountId = UUID.fromString("12121212-1212-1212-1212-121212121212")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("100.00"), currency = "CZK"),
        )
        stubCapitalization()
        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        service.capitalize(accountId, productId, toDate).await().indefinitely()

        // Order is the whole fix: the ledger's idempotency key carries no amount, so the set the
        // ledger is told about must be frozen before it is told. Claiming after the post would
        // leave the same window open.
        verifyOrder {
            accrualRepo.claimForCapitalization(any(), any(), any())
            ledgerPostingPort.post(any())
            capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any())
        }
        assertThat(claimIdsSlot.captured).containsExactlyElementsOf(accruals.map { it.id })
        assertThat(claimPeriodSlot.captured).isEqualTo(toDate)
        // #1355: the claim also freezes the resolved tax profile, so a later retry replays it.
        assertThat(claimProfileSlot.captured).isEqualTo(TaxProfile.FAIL_SAFE_DEFAULT)
    }

    @Test
    fun `a crashed attempt is recovered by a plain retry, on the SAME claimed set and amount`() {
        // The finding-2 scenario. First attempt: gross 100, ledger posts J(key, 100), the pod dies
        // before saveWithOutbox commits. A missed-day accrual for an EARLIER date is then
        // backfilled. Before the claim, the retry re-read `ACCRUING AND accrualDate <= toDate`
        // (no lower bound), summed 120, replayed the SAME amount-blind key, got J(100) back from
        // findByIdempotencyKey without any amount check, and committed a cap row for 120. The GL
        // moved 100. The remittance then paid real cash on 20 CZK nobody was credited.
        val accountId = UUID.fromString("13131313-1313-1313-1313-131313131313")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val claimed = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("100.00"), currency = "CZK")
                .copy(status = AccrualStatus.CAPITALIZING, claimedPeriodTo = toDate),
        )
        stubCapitalization()
        every { accrualRepo.findClaimedForCapitalization(accountId, productId) } returns
            Uni.createFrom().item(claimed)

        val result = service.capitalize(accountId, productId, toDate).await().indefinitely()

        // The retry credits the CLAIMED 100, not 100 + the backfill: the claimed set is the only
        // thing it looks at, so the amount matches the journal the interrupted attempt booked.
        assertThat(result.grossAmount).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(postingSlot.captured.gross.amount).isEqualByComparingTo(BigDecimal("100.00"))
        // The pending query is never even reached, so the backfilled accrual cannot leak in. It
        // stays ACCRUING and falls into the next period — which is the correct answer.
        verify(exactly = 0) { accrualRepo.findPendingCapitalization(any(), any(), any()) }
        // Already claimed: re-claiming would trip the ACCRUING guard and wedge the recovery.
        verify(exactly = 0) { accrualRepo.claimForCapitalization(any(), any(), any()) }
        // ...and the credit still completes. A claimed set is never a wedge.
        verify(exactly = 1) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a retry replays the tax profile frozen at claim, not a freshly resolved one (issue #1355)`() {
        // #1355: capitalize() froze the accrual set (gross) at claim but re-resolved the tax profile on
        // every attempt. If the account's tax attributes changed between a crashed post and the retry,
        // the ledger idempotently replayed the ORIGINAL journal while the withholding row was recomputed
        // from the NEW profile — GL and row disagreeing on the tax split. The claim now snapshots the
        // profile; the retry must recompute from THAT, never a fresh resolve.
        val accountId = UUID.fromString("15151515-1515-1515-1515-151515151515")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        // Profile frozen at claim (attempt 1): a legal entity — interest is NOT withheld (§36).
        val frozen = TaxProfile(TaxpayerType.LEGAL_ENTITY, TaxResidency.RESIDENT)
        val claimed = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("100.00"), currency = "CZK")
                .copy(status = AccrualStatus.CAPITALIZING, claimedPeriodTo = toDate, claimedTaxProfile = frozen),
        )
        // resolve(any()) returns FAIL_SAFE_DEFAULT — an INDIVIDUAL, 15 % WITHHELD: the "changed" profile.
        stubCapitalization()
        every { accrualRepo.findClaimedForCapitalization(accountId, productId) } returns
            Uni.createFrom().item(claimed)

        service.capitalize(accountId, productId, toDate).await().indefinitely()

        // Uses the FROZEN legal-entity profile: NOT_WITHHELD, zero tax, net == gross. Had it re-resolved,
        // it would have withheld 15 (the individual default) — the exact row-vs-GL divergence #1355 closes.
        assertThat(whtSlot.captured.treatment).isEqualTo(WithholdingTreatment.NOT_WITHHELD)
        assertThat(whtSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(capSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(capSlot.captured.netAmount).isEqualByComparingTo(BigDecimal("100"))
        // The freeze, proven directly: a retry never re-resolves the profile.
        verify(exactly = 0) { taxProfilePort.resolve(any()) }
    }

    @Test
    fun `a claim held for a different period end is refused, not silently re-keyed`() {
        val accountId = UUID.fromString("14141414-1414-1414-1414-141414141414")
        val productId = "SAVINGS_CZK"
        val claimed = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("100.00"), currency = "CZK")
                .copy(status = AccrualStatus.CAPITALIZING, claimedPeriodTo = LocalDate.of(2026, 1, 20)),
        )
        every { accrualRepo.findClaimedForCapitalization(accountId, productId) } returns
            Uni.createFrom().item(claimed)

        // periodTo is part of the idempotency key, so completing this claim to 2026-02-20 would
        // mint a SECOND key and post a SECOND journal for interest the first attempt may already
        // have credited.
        assertThatThrownBy {
            service.capitalize(accountId, productId, LocalDate.of(2026, 2, 20)).await().indefinitely()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already CAPITALIZING")
            .hasMessageContaining("2026-01-20")

        verify(exactly = 0) { ledgerPostingPort.post(any()) }
        verify(exactly = 0) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
    }

    // --- Negative gross (finding 3) --------------------------------------------------------------

    @Test
    fun `a negative gross is refused loudly instead of silently skipping the GL`() {
        val accountId = UUID.fromString("15151515-1515-1515-1515-151515151515")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        // Reachable: interest_rate_configs.annual_rate has no CHECK and createConfig does not
        // validate, so a negative rate accrues negative interest.
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("-10.00"), currency = "CZK"),
        )
        stubNoClaimOutstanding()
        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        assertThatThrownBy { service.capitalize(accountId, productId, toDate).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("NEGATIVE gross")
            .hasMessageContaining("-10.00 CZK")

        // The old code returned Unit here and committed the capitalization anyway: the row said the
        // customer had been CHARGED while the GL recorded nothing. Nothing may commit, and the
        // accruals must stay claimable so an operator can fix the rate and re-run.
        verify(exactly = 0) { ledgerPostingPort.post(any()) }
        verify(exactly = 0) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { accrualRepo.claimForCapitalization(any(), any(), any()) }
        verify(exactly = 0) { taxProfilePort.resolve(any()) }
    }

    @Test
    fun `gross is rounded to the currency's minor units, not to scale 4`() {
        val accountId = UUID.fromString("16161616-1616-1616-1616-161616161616")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        // Accruals carry scale-6 daily amounts; their sum is 100.004999, which used to reach the
        // ledger as 100.0050 (scale 4) and 400 every time.
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("60.002500"), currency = "CZK"),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("40.002499"), currency = "CZK"),
        )
        stubCapitalization()
        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        val result = service.capitalize(accountId, productId, toDate).await().indefinitely()

        // Scale asserted explicitly — isEqualByComparingTo would pass on 100.0050 too.
        assertThat(result.grossAmount.scale()).isEqualTo(2)
        assertThat(result.grossAmount).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(result.netAmount.scale()).isEqualTo(2)
        // The raw accrual sum is preserved at full precision: it is a measurement, not money, and
        // V6's partial index keys on it.
        assertThat(capSlot.captured.totalAccrued).isEqualByComparingTo(BigDecimal("100.004999"))
        // The cap row and the ledger MUST carry the identical figures — rounding once, here, is
        // what guarantees it. Rounding at the adapter would leave them up to 0.005 apart.
        assertThat(postingSlot.captured.gross.amount).isEqualTo(capSlot.captured.grossAmount)
        assertThat(postingSlot.captured.net.amount).isEqualTo(capSlot.captured.netAmount)
        assertThat(postingSlot.captured.tax.amount).isEqualTo(capSlot.captured.taxAmount)
    }

    @Test
    fun `getSummary returns correct totals`() {
        val accountId = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val from = LocalDate.of(2026, 1, 1)
        val to = LocalDate.of(2026, 1, 31)
        val accruals = listOf(
            sampleAccrual(accountId, "SAVINGS", LocalDate.of(2026, 1, 5), BigDecimal("1.25")),
            sampleAccrual(accountId, "SAVINGS", LocalDate.of(2026, 1, 10), BigDecimal("2.75")),
        )

        every { accrualRepo.findByAccountId(accountId, from, to) } returns Uni.createFrom().item(accruals)

        val result = service.getSummary(accountId, from, to).await().indefinitely()

        assertThat(result).isEqualTo(
            AccrualSummary(
                accountId = accountId,
                totalAccrued = BigDecimal("4.00"),
                currency = "EUR",
                fromDate = from,
                toDate = to,
                accrualCount = 2,
            ),
        )
        verify(exactly = 1) { accrualRepo.findByAccountId(accountId, from, to) }
    }

    @Test
    fun `deactivateConfig throws when not found`() {
        val id = UUID.fromString("66666666-6666-6666-6666-666666666666")

        every { configRepo.findById(id) } returns Uni.createFrom().nullItem()

        assertThatThrownBy { service.deactivateConfig(id).await().indefinitely() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Config not found")
        verify(exactly = 0) { configRepo.update(any()) }
    }

    @Test
    fun `getAccruals delegates to repo`() {
        val accountId = UUID.fromString("77777777-7777-7777-7777-777777777777")
        val from = LocalDate.of(2026, 1, 1)
        val to = LocalDate.of(2026, 1, 31)
        val accruals = listOf(sampleAccrual(accountId, "SAVINGS", LocalDate.of(2026, 1, 15), BigDecimal("1.11")))

        every { accrualRepo.findByAccountId(accountId, from, to) } returns Uni.createFrom().item(accruals)

        val result = service.getAccruals(accountId, from, to).await().indefinitely()

        assertThat(result).isEqualTo(accruals)
        verify(exactly = 1) { accrualRepo.findByAccountId(accountId, from, to) }
    }

    private fun sampleConfig(annualRate: BigDecimal, dayCount: DayCount = DayCount.ACT_365) = InterestRateConfig(
        id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        productId = "SAVINGS",
        currency = "EUR",
        rateType = InterestRateType.FIXED,
        annualRate = annualRate,
        minBalance = BigDecimal.ZERO,
        maxBalance = BigDecimal("1000000.00"),
        dayCount = dayCount,
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        active = true,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )

    private fun expectedAccrual(request: AccrualRequest, config: InterestRateConfig) = InterestAccrual(
        id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        accountId = request.accountId,
        productId = request.productId,
        configId = config.id,
        accrualDate = request.accrualDate,
        balance = request.balance,
        dailyRate = BigDecimal("0.0010000000"),
        accruedAmount = BigDecimal("1.000000"),
        currency = request.currency,
        status = AccrualStatus.ACCRUING,
        capitalizedAt = null,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )

    private fun sampleAccrual(
        accountId: UUID,
        productId: String,
        accrualDate: LocalDate,
        accruedAmount: BigDecimal,
        currency: String = "EUR",
    ) = InterestAccrual(
        id = UUID.randomUUID(),
        accountId = accountId,
        productId = productId,
        configId = UUID.randomUUID(),
        accrualDate = accrualDate,
        balance = BigDecimal("1000.00"),
        dailyRate = BigDecimal("0.0010000000"),
        accruedAmount = accruedAmount,
        currency = currency,
        status = AccrualStatus.ACCRUING,
        capitalizedAt = null,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )
}
