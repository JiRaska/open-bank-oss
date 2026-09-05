// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.cardprocessing.application.port.`in`.AuthorizationCommand
import com.openbank.cardprocessing.application.port.`in`.PresentmentCommand
import com.openbank.cardprocessing.application.port.out.CardAuthorizationRepository
import com.openbank.cardprocessing.application.port.out.CardIssuancePolicyPort
import com.openbank.cardprocessing.application.port.out.CardLookupPort
import com.openbank.cardprocessing.application.port.out.CardOwnership
import com.openbank.cardprocessing.application.port.out.CardProcessingMetricsPort
import com.openbank.cardprocessing.application.port.out.FraudScore
import com.openbank.cardprocessing.application.port.out.FraudScoringOutcome
import com.openbank.cardprocessing.application.port.out.FraudScoringPort
import com.openbank.cardprocessing.application.port.out.IssuerDecision
import com.openbank.cardprocessing.application.port.out.LedgerPostingPort
import com.openbank.cardprocessing.application.port.out.PostingOutcome
import com.openbank.cardprocessing.application.port.out.PostingResult
import com.openbank.cardprocessing.application.usecase.CardNotFoundException
import com.openbank.cardprocessing.application.usecase.CardProcessingService
import com.openbank.cardprocessing.domain.event.CardAuthorised
import com.openbank.cardprocessing.domain.event.CardDeclined
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.CountedSpend
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import com.openbank.cardprocessing.domain.model.SpendWindow
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The use case against mocked ports.
 *
 * What these tests are FOR, beyond coverage: the two facts that make this service worth existing
 * are that the spend figures handed to the issuer are measured here rather than supplied by the
 * caller, and that a ledger posting which did not happen can never read as one that did. Both are
 * asserted directly below.
 */
class CardProcessingServiceTest {

    private val now = Instant.parse("2026-09-05T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val cardId = UUID.randomUUID()
    private val accountId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()

    private val repository = mockk<CardAuthorizationRepository>()
    private val cards = mockk<CardLookupPort>()
    private val issuer = mockk<CardIssuancePolicyPort>()
    private val ledger = mockk<LedgerPostingPort>()
    private val fraud = mockk<FraudScoringPort>()
    private val metrics = mockk<CardProcessingMetricsPort>(relaxed = true)
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    private fun service() = CardProcessingService(
        repository = repository,
        cards = cards,
        issuerPolicy = issuer,
        ledger = ledger,
        fraud = fraud,
        metrics = metrics,
        mapper = mapper,
        clock = clock,
        holdExpiryDays = HOLD_DAYS,
    )

    private fun command(amount: Long = 25_000, key: String = "idem-1") = AuthorizationCommand(
        cardId = cardId,
        amountMinorUnits = amount,
        currencyCode = "CZK",
        channel = PresentmentChannel.ONLINE,
        mcc = "5812",
        merchantName = "Restaurace",
        merchantCountry = "CZ",
        networkReference = "acq-42",
        idempotencyKey = key,
    )

    private fun stubOwnership() {
        coEvery { cards.lookup(cardId) } returns CardOwnership(accountId, partyId, "CZK")
    }

    @Test
    fun `an approved authorisation is held, published and scored in shadow`() = runBlocking {
        stubOwnership()
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        coEvery { repository.countSpend(cardId, any(), any()) } returns CountedSpend(0, 0, 0)
        coEvery { issuer.decide(any(), any(), any(), any(), any(), any()) } returns
            IssuerDecision(approved = true, reason = null, category = "RESTAURANTS")
        coEvery { fraud.score(any()) } returns FraudScore(FraudScoringOutcome.SCORED, 0.1, "ALLOW")
        // ONE stub capturing both slots. Two `coEvery` blocks on the same call replace each other,
        // and the earlier slot then reads "Value not yet captured" — a test failure that looks like
        // a production defect and is not one.
        val saved = slot<CardAuthorization>()
        val event = slot<OutboxMessage>()
        coEvery { repository.save(capture(saved), capture(event), any()) } answers { firstArg() }

        val result = service().authorize(command())

        assertThat(result.status).isEqualTo(AuthorizationStatus.APPROVED)
        assertThat(result.declineReason).isNull()
        assertThat(result.heldAmountMinorUnits).isEqualTo(25_000)
        assertThat(result.expiresAt).isEqualTo(now.plusSeconds(HOLD_DAYS * SECONDS_PER_DAY))
        assertThat(event.captured.eventType).isEqualTo(CardAuthorised.EVENT_TYPE)
        // createdAt comes from the injected clock, not the OutboxMessage default: the dispatcher
        // claims rows in created_at order, so a row stamped off a different clock sorts wrongly.
        assertThat(event.captured.createdAt).isEqualTo(now)
        assertThat(saved.captured.category).isEqualTo("RESTAURANTS")
        coVerify(exactly = 1) { fraud.score(any()) }
        Unit
    }

    @Test
    fun `the spend figures handed to the issuer are the ones this service measured`() = runBlocking {
        stubOwnership()
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        val measured = CountedSpend(
            todayMinorUnits = 40_000,
            thisMonthMinorUnits = 900_000,
            thisMonthInCategoryMinorUnits = 12_000,
        )
        coEvery { repository.countSpend(cardId, any(), any()) } returns measured
        val handed = slot<CountedSpend>()
        coEvery { issuer.decide(any(), any(), any(), any(), any(), capture(handed)) } returns
            IssuerDecision(approved = true, reason = null, category = CardProcessingService.UNMAPPED_CATEGORY)
        coEvery { fraud.score(any()) } returns FraudScore(FraudScoringOutcome.SKIPPED_DISABLED, null, null)
        coEvery { repository.save(any(), any(), any()) } answers { firstArg() }

        service().authorize(command())

        // The defect the service exists to close: before it, the issuer endpoint took these three
        // numbers from whoever called it, and nobody called it.
        assertThat(handed.captured).isEqualTo(measured)
        Unit
    }

    @Test
    fun `a decline is recorded with the issuer's own reason, not a re-worded one`() = runBlocking {
        stubOwnership()
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        coEvery { repository.countSpend(cardId, any(), any()) } returns CountedSpend(0, 0, 0)
        coEvery { issuer.decide(any(), any(), any(), any(), any(), any()) } returns
            IssuerDecision(approved = false, reason = "ABROAD_DISABLED", category = "TRAVEL")
        coEvery { fraud.score(any()) } returns FraudScore(FraudScoringOutcome.SKIPPED_DISABLED, null, null)
        val event = slot<OutboxMessage>()
        coEvery { repository.save(any(), capture(event), any()) } answers { firstArg() }

        val result = service().authorize(command())

        assertThat(result.status).isEqualTo(AuthorizationStatus.DECLINED)
        assertThat(result.declineReason).isEqualTo("ABROAD_DISABLED")
        assertThat(result.heldAmountMinorUnits).isZero()
        assertThat(event.captured.eventType).isEqualTo(CardDeclined.EVENT_TYPE)
        Unit
    }

    @Test
    fun `a repeated idempotency key returns the first authorisation and takes no second hold`() = runBlocking {
        val existing = mockk<CardAuthorization>()
        coEvery { repository.findByIdempotencyKey("idem-1") } returns existing

        val result = service().authorize(command())

        assertThat(result).isSameAs(existing)
        coVerify(exactly = 0) { issuer.decide(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.save(any(), any(), any()) }
        Unit
    }

    @Test
    fun `an unknown card is a 404-shaped failure, not an approval`() = runBlocking {
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        coEvery { cards.lookup(cardId) } returns null

        assertThatThrownBy { runBlocking { service().authorize(command()) } }
            .isInstanceOf(CardNotFoundException::class.java)
        Unit
    }

    @Test
    fun `a failed ledger posting is reported as FAILED and never as a success`() = runBlocking {
        val approved = authorization(AuthorizationStatus.APPROVED)
        coEvery { repository.findById(approved.id) } returns approved
        coEvery { repository.save(any(), any(), any()) } answers { firstArg() }
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.FAILED, null, "connection refused")
        val recorded = slot<PostingOutcome>()
        coEvery { metrics.ledgerPosting(capture(recorded)) } returns Unit

        val outcome = service().clear(PresentmentCommand(approved.id, 10_000, "CZK", "clr-1"))

        // The clearing still stands — the acquirer asserted it, and refusing to record it because
        // the ledger is unreachable would lose the fact. FAILED is what makes the gap visible.
        assertThat(outcome).isInstanceOf(PresentmentOutcome.Accepted::class.java)
        assertThat(recorded.captured).isEqualTo(PostingOutcome.FAILED)
        Unit
    }

    @Test
    fun `a disabled ledger adapter is counted as SKIPPED_DISABLED, not as POSTED`() = runBlocking {
        val approved = authorization(AuthorizationStatus.APPROVED)
        coEvery { repository.findById(approved.id) } returns approved
        coEvery { repository.save(any(), any(), any()) } answers { firstArg() }
        coEvery { ledger.postClearedSpend(any(), any(), any()) } returns
            PostingResult(PostingOutcome.SKIPPED_DISABLED, null, "disabled")
        val recorded = slot<PostingOutcome>()
        coEvery { metrics.ledgerPosting(capture(recorded)) } returns Unit

        service().clear(PresentmentCommand(approved.id, 10_000, "CZK", "clr-2"))

        // The whole reason PostingOutcome is an enum: a skip counted as a success is how the push
        // fan-out reported deliveries that never left the process (#4348).
        assertThat(recorded.captured).isEqualTo(PostingOutcome.SKIPPED_DISABLED)
        Unit
    }

    @Test
    fun `clearing an unknown authorisation refuses instead of throwing`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repository.findById(id) } returns null

        val outcome = service().clear(PresentmentCommand(id, 1_000, "CZK", "clr-3"))

        assertThat(outcome).isInstanceOf(PresentmentOutcome.Refused::class.java)
        coVerify(exactly = 0) { ledger.postClearedSpend(any(), any(), any()) }
        Unit
    }

    @Test
    fun `the expiry sweep releases only the holds it could release`() = runBlocking {
        val due = authorization(AuthorizationStatus.APPROVED, expiresAt = now.minusSeconds(60))
        val notDue = authorization(AuthorizationStatus.APPROVED, expiresAt = now.plusSeconds(60))
        coEvery { repository.findExpiredHolds(now, 100) } returns listOf(due, notDue)
        coEvery { repository.save(any(), any(), any()) } answers { firstArg() }

        val released = service().releaseExpiredHolds(100)

        // The row that is not actually due is refused by the policy, so the count is 1 — the guard
        // against a scheduler releasing funds an acquirer may still present against.
        assertThat(released).isEqualTo(1)
        Unit
    }

    @Test
    fun `a reversal publishes the amount that was actually released`() = runBlocking {
        val approved = authorization(AuthorizationStatus.APPROVED)
        coEvery { repository.findById(approved.id) } returns approved
        val event = slot<OutboxMessage>()
        coEvery { repository.save(any(), capture(event), any()) } answers { firstArg() }

        val outcome = service().reverse(approved.id)

        assertThat(outcome).isInstanceOf(PresentmentOutcome.Accepted::class.java)
        // The released amount is read BEFORE the transition, while the hold still exists. Reading
        // it after would publish zero every time, and a client showing "0 returned" is worse than
        // showing nothing.
        assertThat(event.captured.payload).contains("\"releasedAmountMinorUnits\":10000")
        assertThat(event.captured.payload).contains("\"releaseKind\":\"REVERSAL\"")
        Unit
    }

    private fun authorization(
        status: AuthorizationStatus,
        expiresAt: Instant = now.plusSeconds(3600),
    ) = CardAuthorization(
        id = UUID.randomUUID(),
        cardId = cardId,
        accountId = accountId,
        partyId = partyId,
        amountMinorUnits = 10_000,
        currencyCode = "CZK",
        channel = PresentmentChannel.ONLINE,
        mcc = "5812",
        merchantName = "Restaurace",
        merchantCountry = "CZ",
        status = status,
        category = "RESTAURANTS",
        declineReason = null,
        clearedAmountMinorUnits = 0,
        networkReference = "acq-42",
        authorizedAt = now.minusSeconds(120),
        expiresAt = expiresAt,
        updatedAt = now.minusSeconds(120),
    )

    @Test
    fun `the spend window is the accounting day, not UTC midnight`() {
        // Pinned because it is the kind of thing a later refactor "simplifies" into
        // Instant.truncatedTo(DAYS), which is right in Prague only in winter.
        val window = SpendWindow.resolve(clock)

        assertThat(window.now).isEqualTo(now)
        assertThat(window.dayStart).isBefore(now)
        assertThat(window.monthStart).isBeforeOrEqualTo(window.dayStart)
    }

    private companion object {
        const val HOLD_DAYS = 7L
        const val SECONDS_PER_DAY = 24 * 3600L
    }
}
