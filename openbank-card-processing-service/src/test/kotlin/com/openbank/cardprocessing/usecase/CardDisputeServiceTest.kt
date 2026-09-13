// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.cardprocessing.application.port.`in`.OpenDisputeCommand
import com.openbank.cardprocessing.application.port.`in`.SubmitEvidenceCommand
import com.openbank.cardprocessing.application.port.out.CardAuthorizationRepository
import com.openbank.cardprocessing.application.port.out.CardDisputeCaseRepository
import com.openbank.cardprocessing.application.port.out.CardLifecycleMetricsPort
import com.openbank.cardprocessing.application.usecase.CardDisputeService
import com.openbank.cardprocessing.domain.event.CardDisputeOpened
import com.openbank.cardprocessing.domain.event.CardDisputeStatusChanged
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.CardDisputeCase
import com.openbank.cardprocessing.domain.model.DisputeOutcome
import com.openbank.cardprocessing.domain.model.DisputeRefusal
import com.openbank.cardprocessing.domain.model.DisputeStatus
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.infrastructure.scheme.SimulatedDisputeAdapter
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.DisputePort
import com.openbank.libs.domain.cards.scheme.SchemeDispute
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The dispute path.
 *
 * The properties worth losing sleep over, all asserted below: a case is never recorded unless the
 * network opened one, only cleared money may be disputed, and a status poll that finds no movement
 * publishes nothing.
 */
class CardDisputeServiceTest {

    private val now = Instant.parse("2026-09-05T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val cardId = UUID.randomUUID()
    private val authorizationId = UUID.randomUUID()

    private val cases = mockk<CardDisputeCaseRepository>()
    private val authorizations = mockk<CardAuthorizationRepository>()
    private val metrics = mockk<CardLifecycleMetricsPort>(relaxed = true)
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val simulator = SimulatedDisputeAdapter(clock)

    private fun service(port: DisputePort = simulator) =
        CardDisputeService(port, cases, authorizations, metrics, mapper, clock)

    private fun command(amount: Long = 5_000, key: String = "idem-dispute-1") =
        OpenDisputeCommand(authorizationId, "10.4", amount, "CZK", key)

    @Test
    fun `opening records the network's case id and emits the opened event`(): Unit = runBlocking {
        coEvery { cases.findByIdempotencyKey(any()) } returns null
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
        coEvery { cases.findLiveByAuthorization(authorizationId) } returns null
        val saved = slot<CardDisputeCase>()
        val event = slot<OutboxMessage>()
        coEvery { cases.save(capture(saved), capture(event), any()) } answers { saved.captured }

        val outcome = service().open(command())

        val case = (outcome as DisputeOutcome.Accepted).case
        assertThat(case.networkCaseId).startsWith("sim-case-")
        assertThat(case.status).isEqualTo(DisputeStatus.OPEN)
        // Both vocabularies survive: the bank's status and the network's own string.
        assertThat(case.schemeStatus).isEqualTo("OPEN")
        assertThat(case.respondByDate).isNotNull()
        assertThat(event.captured.eventType).isEqualTo(CardDisputeOpened.EVENT_TYPE)
    }

    @Test
    fun `a scheme that cannot answer leaves NO local case`(): Unit = runBlocking {
        coEvery { cases.findByIdempotencyKey(any()) } returns null
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
        coEvery { cases.findLiveByAuthorization(authorizationId) } returns null
        val down = mockk<DisputePort>()
        coEvery { down.open(any(), any(), any(), any()) } returns SchemeResult.Unanswered(
            SchemeFailure.UNAVAILABLE,
            CardScheme.VISA,
            "connect timeout",
        )

        val outcome = service(down).open(command())

        assertThat((outcome as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.SCHEME_UNAVAILABLE)
        // The whole design in one assertion: a row written here would carry a respond-by date
        // nobody is counting down, and would read as an active case on every screen.
        coVerify(exactly = 0) { cases.save(any(), any(), any()) }
    }

    @Test
    fun `a hold that has cleared nothing cannot be disputed, and the scheme is never asked`(): Unit = runBlocking {
        coEvery { cases.findByIdempotencyKey(any()) } returns null
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 0)
        val port = mockk<DisputePort>()

        val outcome = service(port).open(command())

        assertThat((outcome as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.NOTHING_CLEARED)
        coVerify(exactly = 0) { port.open(any(), any(), any(), any()) }
    }

    @Test
    fun `the disputed amount may not exceed what cleared`(): Unit = runBlocking {
        coEvery { cases.findByIdempotencyKey(any()) } returns null
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
        val port = mockk<DisputePort>()

        val outcome = service(port).open(command(amount = 5_001))

        assertThat((outcome as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.AMOUNT_EXCEEDS_CLEARED)
        coVerify(exactly = 0) { port.open(any(), any(), any(), any()) }
    }

    @Test
    fun `an authorisation with no acquirer reference is refused with its own reason`(): Unit = runBlocking {
        coEvery { cases.findByIdempotencyKey(any()) } returns null
        coEvery { authorizations.findById(authorizationId) } returns
            authorization(cleared = 5_000).copy(networkReference = null)
        coEvery { cases.findLiveByAuthorization(authorizationId) } returns null
        val port = mockk<DisputePort>()

        val outcome = service(port).open(command())

        // Its own value, not folded into SCHEME_UNAVAILABLE: this one is a data problem to chase
        // with the acquirer, not an outage to retry.
        assertThat((outcome as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.NO_NETWORK_REFERENCE)
        coVerify(exactly = 0) { port.open(any(), any(), any(), any()) }
    }

    @Test
    fun `a second live case against one authorisation is refused`(): Unit = runBlocking {
        coEvery { cases.findByIdempotencyKey(any()) } returns null
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
        coEvery { cases.findLiveByAuthorization(authorizationId) } returns case(DisputeStatus.OPEN)

        val outcome = service().open(command())

        assertThat((outcome as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.ALREADY_DISPUTED)
    }

    @Test
    fun `a closed case accepts no further evidence`(): Unit = runBlocking {
        coEvery { cases.findById(any()) } returns case(DisputeStatus.LOST)
        val port = mockk<DisputePort>()

        val outcome = service(port).submitEvidence(SubmitEvidenceCommand(UUID.randomUUID(), "doc-1", null))

        assertThat((outcome as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.CASE_TERMINAL)
        coVerify(exactly = 0) { port.submitEvidence(any()) }
    }

    @Test
    fun `a status poll that finds no movement publishes nothing`(): Unit = runBlocking {
        val existing = case(DisputeStatus.OPEN)
        coEvery { cases.findById(existing.id) } returns existing
        val port = mockk<DisputePort>()
        coEvery { port.status(existing.networkCaseId) } returns SchemeResult.Answered(
            schemeDispute(existing.networkCaseId, "OPEN"),
            CardScheme.SIMULATOR,
        )

        val outcome = service(port).refreshStatus(existing.id)

        assertThat((outcome as DisputeOutcome.Accepted).case).isEqualTo(existing)
        // An event per poll would make "the case changed" indistinguishable from "somebody looked".
        coVerify(exactly = 0) { cases.save(any(), any(), any()) }
    }

    @Test
    fun `a status the network moved is recorded in both vocabularies and announced`(): Unit = runBlocking {
        val existing = case(DisputeStatus.EVIDENCE_SUBMITTED)
        coEvery { cases.findById(existing.id) } returns existing
        val saved = slot<CardDisputeCase>()
        val event = slot<OutboxMessage>()
        coEvery { cases.save(capture(saved), capture(event), any()) } answers { saved.captured }
        val port = mockk<DisputePort>()
        coEvery { port.status(existing.networkCaseId) } returns SchemeResult.Answered(
            schemeDispute(existing.networkCaseId, "RESOLVED_WON"),
            CardScheme.SIMULATOR,
        )

        val outcome = service(port).refreshStatus(existing.id)

        val updated = (outcome as DisputeOutcome.Accepted).case
        assertThat(updated.status).isEqualTo(DisputeStatus.WON)
        assertThat(updated.schemeStatus).isEqualTo("RESOLVED_WON")
        assertThat(event.captured.eventType).isEqualTo(CardDisputeStatusChanged.EVENT_TYPE)
    }

    @Test
    fun `a scheme status this bank does not recognise leaves the bank status alone`(): Unit = runBlocking {
        val existing = case(DisputeStatus.OPEN)
        coEvery { cases.findById(existing.id) } returns existing
        val saved = slot<CardDisputeCase>()
        coEvery { cases.save(capture(saved), any(), any()) } answers { saved.captured }
        val port = mockk<DisputePort>()
        coEvery { port.status(existing.networkCaseId) } returns SchemeResult.Answered(
            schemeDispute(existing.networkCaseId, "PRE_ARBITRATION_PENDING"),
            CardScheme.SIMULATOR,
        )

        val updated = (service(port).refreshStatus(existing.id) as DisputeOutcome.Accepted).case

        // Guessing a bank status from an unknown scheme string is how the two vocabularies end up
        // disagreeing where a deadline is computed. The string still reaches the operator verbatim.
        assertThat(updated.status).isEqualTo(DisputeStatus.OPEN)
        assertThat(updated.schemeStatus).isEqualTo("PRE_ARBITRATION_PENDING")
    }

    private fun schemeDispute(networkCaseId: String, status: String) = SchemeDispute(
        networkCaseId = networkCaseId,
        reasonCode = "10.4",
        amountMinorUnits = 5_000,
        currencyCode = "CZK",
        respondByDate = null,
        status = status,
    )

    private fun case(status: DisputeStatus) = CardDisputeCase(
        id = UUID.randomUUID(),
        authorizationId = authorizationId,
        cardId = cardId,
        networkCaseId = "sim-case-1",
        reasonCode = "10.4",
        amountMinorUnits = 5_000,
        currencyCode = "CZK",
        status = status,
        scheme = CardScheme.SIMULATOR,
        schemeStatus = "OPEN",
        respondByDate = null,
        evidenceReference = null,
        openedAt = now,
        updatedAt = now,
    )

    private fun authorization(cleared: Long) = CardAuthorization(
        id = authorizationId,
        cardId = cardId,
        accountId = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        amountMinorUnits = 5_000,
        currencyCode = "CZK",
        channel = PresentmentChannel.ONLINE,
        mcc = "5411",
        merchantName = "Shop",
        merchantCountry = "CZ",
        status = if (cleared > 0) AuthorizationStatus.CLEARED else AuthorizationStatus.APPROVED,
        category = "GROCERIES",
        declineReason = null,
        clearedAmountMinorUnits = cleared,
        networkReference = "acq-ref-1",
        authorizedAt = now,
        expiresAt = now.plusSeconds(86_400),
        updatedAt = now,
    )
}
