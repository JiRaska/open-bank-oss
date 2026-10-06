// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.cardprocessing.application.port.`in`.OpenDisputeCommand
import com.openbank.cardprocessing.application.port.`in`.RefreshDisputeCommand
import com.openbank.cardprocessing.application.port.`in`.SubmitEvidenceCommand
import com.openbank.cardprocessing.application.port.out.CardAuthorizationRepository
import com.openbank.cardprocessing.application.port.out.CardDisputeCaseRepository
import com.openbank.cardprocessing.application.port.out.CardLifecycleMetricsPort
import com.openbank.cardprocessing.application.port.out.IdempotencyClaim
import com.openbank.cardprocessing.application.port.out.LifecycleOperation
import com.openbank.cardprocessing.application.usecase.CardDisputeService
import com.openbank.cardprocessing.domain.event.CardDisputeOpened
import com.openbank.cardprocessing.domain.event.CardDisputeStatusChanged
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.CardDisputeCase
import com.openbank.cardprocessing.domain.model.DisputeEvidenceRecord
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
import com.openbank.libs.domain.money.InvalidMoneyException
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
        CardDisputeService(port, cases, idempotency, authorizations, metrics, mapper, clock)

    private val idempotency = FakeLifecycleIdempotency()

    private fun evidence(disputeId: UUID, doc: String = "doc-1", key: String = "idem-evidence-1") =
        SubmitEvidenceCommand(disputeId, doc, null, key)

    private fun command(amount: Long = 5_000, key: String = "idem-dispute-1") =
        OpenDisputeCommand(authorizationId, "10.4", amount, "CZK", key)

    @Test
    fun `opening records the network's case id and emits the opened event`(): Unit = runBlocking {
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
        coEvery { cases.findLiveByAuthorization(authorizationId) } returns null
        val saved = slot<CardDisputeCase>()
        val event = slot<OutboxMessage>()
        coEvery { cases.save(capture(saved), capture(event), any(), any()) } answers {
            arg<IdempotencyClaim?>(3)?.let { idempotency.completeNow(it, saved.captured.id) }
            saved.captured
        }

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
        coVerify(exactly = 0) { cases.save(any(), any(), any(), any()) }
    }

    @Test
    fun `a hold that has cleared nothing cannot be disputed, and the scheme is never asked`(): Unit = runBlocking {
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 0)
        val port = mockk<DisputePort>()

        val outcome = service(port).open(command())

        assertThat((outcome as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.NOTHING_CLEARED)
        coVerify(exactly = 0) { port.open(any(), any(), any(), any()) }
    }

    @Test
    fun `the disputed amount may not exceed what cleared`(): Unit = runBlocking {
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
        val port = mockk<DisputePort>()

        val outcome = service(port).open(command(amount = 5_001))

        assertThat((outcome as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.AMOUNT_EXCEEDS_CLEARED)
        coVerify(exactly = 0) { port.open(any(), any(), any(), any()) }
    }

    @Test
    fun `an authorisation with no acquirer reference is refused with its own reason`(): Unit = runBlocking {
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
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
        coEvery { cases.findLiveByAuthorization(authorizationId) } returns case(DisputeStatus.OPEN)

        val outcome = service().open(command())

        assertThat((outcome as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.ALREADY_DISPUTED)
    }

    @Test
    fun `a closed case accepts no further evidence`(): Unit = runBlocking {
        coEvery { cases.findById(any()) } returns case(DisputeStatus.LOST)
        val port = mockk<DisputePort>()

        val outcome = service(port).submitEvidence(evidence(UUID.randomUUID()))

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

        val outcome = service(port).refreshStatus(RefreshDisputeCommand(existing.id, UUID.randomUUID().toString()))

        assertThat((outcome as DisputeOutcome.Accepted).case).isEqualTo(existing)
        // An event per poll would make "the case changed" indistinguishable from "somebody looked".
        coVerify(exactly = 0) { cases.save(any(), any(), any(), any()) }
    }

    @Test
    fun `a status the network moved is recorded in both vocabularies and announced`(): Unit = runBlocking {
        val existing = case(DisputeStatus.EVIDENCE_SUBMITTED)
        coEvery { cases.findById(existing.id) } returns existing
        val saved = slot<CardDisputeCase>()
        val event = slot<OutboxMessage>()
        coEvery { cases.save(capture(saved), capture(event), any(), any()) } answers { saved.captured }
        val port = mockk<DisputePort>()
        coEvery { port.status(existing.networkCaseId) } returns SchemeResult.Answered(
            schemeDispute(existing.networkCaseId, "RESOLVED_WON"),
            CardScheme.SIMULATOR,
        )

        val outcome = service(port).refreshStatus(RefreshDisputeCommand(existing.id, UUID.randomUUID().toString()))

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
        coEvery { cases.save(capture(saved), any(), any(), any()) } answers { saved.captured }
        val port = mockk<DisputePort>()
        coEvery { port.status(existing.networkCaseId) } returns SchemeResult.Answered(
            schemeDispute(existing.networkCaseId, "PRE_ARBITRATION_PENDING"),
            CardScheme.SIMULATOR,
        )

        val updated = (
            service(
                port,
            ).refreshStatus(
                RefreshDisputeCommand(existing.id, UUID.randomUUID().toString()),
            ) as DisputeOutcome.Accepted
            ).case

        // Guessing a bank status from an unknown scheme string is how the two vocabularies end up
        // disagreeing where a deadline is computed. The string still reaches the operator verbatim.
        assertThat(updated.status).isEqualTo(DisputeStatus.OPEN)
        assertThat(updated.schemeStatus).isEqualTo("PRE_ARBITRATION_PENDING")
    }

    @Test
    fun `a dispute in another currency than the authorisation is refused before the scheme is asked`(): Unit =
        runBlocking {
            coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
            val port = mockk<DisputePort>()

            val outcome = service(port).open(command().copy(currencyCode = "EUR"))

            assertThat((outcome as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.CURRENCY_MISMATCH)
            assertThat(outcome.detail).contains("EUR").contains("CZK")
            // The defect this pins: 5_000 EUR minor units compared to 5_000 CZK as raw numbers passed.
            coVerify(exactly = 0) { port.open(any(), any(), any(), any()) }
            coVerify { metrics.disputeOpened("NONE", DisputeRefusal.CURRENCY_MISMATCH.name) }
            assertThat(idempotency.isPending(LifecycleOperation.DISPUTE_OPEN, "idem-dispute-1")).isFalse()
        }

    @Test
    fun `the currency check is case-insensitive and an unknown code is the client's 400, not a 409`(): Unit =
        runBlocking {
            coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
            coEvery { cases.findLiveByAuthorization(authorizationId) } returns null
            coEvery { cases.save(any(), any(), any(), any()) } answers { firstArg() }

            assertThat(service().open(command().copy(currencyCode = "czk")))
                .isInstanceOf(DisputeOutcome.Accepted::class.java)
            assertThatThrownBy { runBlocking { service().open(command(key = "k2").copy(currencyCode = "ZZZ")) } }
                .isInstanceOf(InvalidMoneyException::class.java)
            // A thrown validation error frees the key: the network was never asked.
            assertThat(idempotency.isPending(LifecycleOperation.DISPUTE_OPEN, "k2")).isFalse()
        }

    @Test
    fun `a repeated open replays the case and never opens a second chargeback`(): Unit = runBlocking {
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
        coEvery { cases.findLiveByAuthorization(authorizationId) } returns null
        val saved = slot<CardDisputeCase>()
        coEvery { cases.save(capture(saved), any(), any(), any()) } answers {
            arg<IdempotencyClaim?>(3)?.let { idempotency.completeNow(it, saved.captured.id) }
            saved.captured
        }
        val port = mockk<DisputePort>()
        coEvery { port.open(any(), any(), any(), any()) } coAnswers {
            simulator.open(firstArg(), secondArg(), thirdArg(), arg(3))
        }
        val first = (service(port).open(command()) as DisputeOutcome.Accepted).case
        coEvery { cases.findById(first.id) } returns first

        val replay = service(port).open(command())

        assertThat((replay as DisputeOutcome.Accepted).case).isEqualTo(first)
        coVerify(exactly = 1) { port.open(any(), any(), any(), any()) }
    }

    @Test
    fun `a same-key open still in flight is IN_PROGRESS and never reaches the scheme`(): Unit = runBlocking {
        val port = mockk<DisputePort>()
        // Claim with the real fingerprint by starting a request that parks inside the network call.
        coEvery { authorizations.findById(authorizationId) } returns authorization(cleared = 5_000)
        coEvery { cases.findLiveByAuthorization(authorizationId) } returns null
        coEvery { port.open(any(), any(), any(), any()) } coAnswers {
            assertThatThrownBy { runBlocking { service(port).open(command()) } }
                .isInstanceOf(IdempotencyRequestInProgressException::class.java)
            SchemeResult.Unanswered(SchemeFailure.UNAVAILABLE, CardScheme.SIMULATOR, "timeout")
        }

        service(port).open(command())

        coVerify(exactly = 1) { port.open(any(), any(), any(), any()) }
    }

    @Test
    fun `evidence is appended to the history, idempotently, and a retry never re-files with the network`(): Unit =
        runBlocking {
            val existing = case(DisputeStatus.OPEN)
            coEvery { cases.findById(existing.id) } returns existing
            val record = slot<DisputeEvidenceRecord>()
            coEvery { cases.recordEvidence(any(), capture(record), any(), any()) } answers {
                idempotency.completeNow(arg(3), record.captured.id)
                firstArg()
            }
            val port = mockk<DisputePort>()
            coEvery { port.submitEvidence(any()) } returns SchemeResult.Answered(
                schemeDispute(existing.networkCaseId, "EVIDENCE_RECEIVED"),
                CardScheme.SIMULATOR,
            )

            val first = service(port).submitEvidence(evidence(existing.id))
            val retry = service(port).submitEvidence(evidence(existing.id))

            assertThat(first).isInstanceOf(DisputeOutcome.Accepted::class.java)
            assertThat(retry).isInstanceOf(DisputeOutcome.Accepted::class.java)
            coVerify(exactly = 1) { port.submitEvidence(any()) }
            coVerify(exactly = 1) { cases.recordEvidence(any(), any(), any(), any()) }
            assertThat(record.captured.documentReference).isEqualTo("doc-1")
            assertThat(record.captured.schemeStatus).isEqualTo("EVIDENCE_RECEIVED")
            assertThat(record.captured.submittedAt).isEqualTo(now)

            // A DIFFERENT filing under a new key is a second history row, not an overwrite.
            service(port).submitEvidence(evidence(existing.id, doc = "doc-2", key = "idem-evidence-2"))
            coVerify(exactly = 2) { cases.recordEvidence(any(), any(), any(), any()) }
            assertThat(record.captured.documentReference).isEqualTo("doc-2")
        }

    @Test
    fun `the same evidence key on another case is a reuse conflict`(): Unit = runBlocking {
        val a = case(DisputeStatus.OPEN)
        coEvery { cases.findById(a.id) } returns a
        coEvery { cases.recordEvidence(any(), any(), any(), any()) } answers {
            idempotency.completeNow(arg(3), UUID.randomUUID())
            firstArg()
        }
        val port = mockk<DisputePort>()
        coEvery { port.submitEvidence(any()) } returns SchemeResult.Answered(
            schemeDispute(a.networkCaseId, "EVIDENCE_RECEIVED"),
            CardScheme.SIMULATOR,
        )
        assertThat(service(port).submitEvidence(evidence(a.id))).isInstanceOf(DisputeOutcome.Accepted::class.java)

        assertThatThrownBy { runBlocking { service(port).submitEvidence(evidence(UUID.randomUUID())) } }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)
    }

    @ParameterizedTest
    @EnumSource(value = DisputeStatus::class, names = ["WON", "LOST", "WITHDRAWN"])
    fun `a closed case is terminal - a refresh never moves it and reports a disagreeing network`(
        stored: DisputeStatus,
    ): Unit = runBlocking {
        val closed = case(stored)
        coEvery { cases.findById(closed.id) } returns closed
        val reported = if (stored == DisputeStatus.WON) "RESOLVED_LOST" else "RESOLVED_WON"
        val reportedBank = if (stored == DisputeStatus.WON) DisputeStatus.LOST else DisputeStatus.WON
        val port = mockk<DisputePort>()
        coEvery { port.status(closed.networkCaseId) } returns SchemeResult.Answered(
            schemeDispute(closed.networkCaseId, reported),
            CardScheme.SIMULATOR,
        )

        val outcome = service(port).refreshStatus(RefreshDisputeCommand(closed.id, UUID.randomUUID().toString()))

        assertThat((outcome as DisputeOutcome.Accepted).case).isEqualTo(closed)
        coVerify(exactly = 0) { cases.save(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            metrics.disputeTerminalMismatch("SIMULATOR", stored.name, reportedBank.name)
        }
    }

    @Test
    fun `a closed case whose network agrees records no mismatch, and an unreachable network changes nothing`(): Unit =
        runBlocking {
            val closed = case(DisputeStatus.WON)
            coEvery { cases.findById(closed.id) } returns closed
            val port = mockk<DisputePort>()
            coEvery { port.status(any()) } returnsMany listOf(
                SchemeResult.Answered(schemeDispute(closed.networkCaseId, "WON"), CardScheme.SIMULATOR),
                SchemeResult.Unanswered(SchemeFailure.UNAVAILABLE, CardScheme.SIMULATOR, "down"),
            )

            assertThat(
                (
                    service(
                        port,
                    ).refreshStatus(
                        RefreshDisputeCommand(closed.id, UUID.randomUUID().toString()),
                    ) as DisputeOutcome.Accepted
                    ).case,
            ).isEqualTo(closed)
            assertThat(
                (
                    service(
                        port,
                    ).refreshStatus(
                        RefreshDisputeCommand(closed.id, UUID.randomUUID().toString()),
                    ) as DisputeOutcome.Accepted
                    ).case,
            ).isEqualTo(closed)
            coVerify(exactly = 0) { metrics.disputeTerminalMismatch(any(), any(), any()) }
            coVerify(exactly = 0) { cases.save(any(), any(), any(), any()) }
        }

    @Test
    fun `a refresh retried under the same key replays the case and asks the network once`(): Unit = runBlocking {
        val existing = case(DisputeStatus.EVIDENCE_SUBMITTED)
        coEvery { cases.findById(existing.id) } returns existing
        val saved = slot<CardDisputeCase>()
        coEvery { cases.save(capture(saved), any(), any(), any()) } answers {
            arg<IdempotencyClaim?>(3)?.let { idempotency.completeNow(it, saved.captured.id) }
            saved.captured
        }
        val port = mockk<DisputePort>()
        coEvery { port.status(existing.networkCaseId) } returns SchemeResult.Answered(
            schemeDispute(existing.networkCaseId, "RESOLVED_WON"),
            CardScheme.SIMULATOR,
        )
        val command = RefreshDisputeCommand(existing.id, "idem-refresh-1")

        val first = service(port).refreshStatus(command)
        val replay = service(port).refreshStatus(command)

        assertThat((first as DisputeOutcome.Accepted).case.id).isEqualTo(existing.id)
        assertThat((replay as DisputeOutcome.Accepted).case.id).isEqualTo(existing.id)
        coVerify(exactly = 1) { port.status(any()) }
        coVerify(exactly = 1) { cases.save(any(), any(), any(), any()) }
    }

    @Test
    fun `a refresh that finds nothing moved, or a closed case, still completes the reservation`(): Unit = runBlocking {
        val open = case(DisputeStatus.OPEN).copy(networkCaseId = "case-open")
        val closed = case(DisputeStatus.WON).copy(networkCaseId = "case-closed")
        coEvery { cases.findById(open.id) } returns open
        coEvery { cases.findById(closed.id) } returns closed
        val port = mockk<DisputePort>()
        coEvery { port.status(open.networkCaseId) } returns
            SchemeResult.Answered(schemeDispute(open.networkCaseId, "OPEN"), CardScheme.SIMULATOR)
        coEvery { port.status(closed.networkCaseId) } returns
            SchemeResult.Answered(schemeDispute(closed.networkCaseId, "WON"), CardScheme.SIMULATOR)

        service(port).refreshStatus(RefreshDisputeCommand(open.id, "idem-refresh-open"))
        service(port).refreshStatus(RefreshDisputeCommand(closed.id, "idem-refresh-closed"))
        // The terminal case's stored outcome is what a retry replays.
        val replay = service(port).refreshStatus(RefreshDisputeCommand(closed.id, "idem-refresh-closed"))

        assertThat((replay as DisputeOutcome.Accepted).case).isEqualTo(closed)
        assertThat(idempotency.completedStandalone.map { it.second }).containsExactly(open.id, closed.id)
        coVerify(exactly = 1) { port.status(closed.networkCaseId) }
        coVerify(exactly = 0) { cases.save(any(), any(), any(), any()) }
    }

    @Test
    fun `a refresh key reused for another case is a reuse conflict, and an unknown case frees the key`(): Unit =
        runBlocking {
            val existing = case(DisputeStatus.OPEN)
            coEvery { cases.findById(existing.id) } returns existing
            val missing = UUID.randomUUID()
            coEvery { cases.findById(missing) } returns null

            val refused = service().refreshStatus(RefreshDisputeCommand(missing, "idem-refresh-x"))
            assertThat((refused as DisputeOutcome.Refused).reason).isEqualTo(DisputeRefusal.CASE_NOT_FOUND)
            assertThat(idempotency.isPending(LifecycleOperation.DISPUTE_REFRESH, "idem-refresh-x")).isFalse()

            val port = mockk<DisputePort>()
            coEvery { port.status(any()) } returns
                SchemeResult.Answered(schemeDispute(existing.networkCaseId, "OPEN"), CardScheme.SIMULATOR)
            service(port).refreshStatus(RefreshDisputeCommand(existing.id, "idem-refresh-y"))
            assertThatThrownBy {
                runBlocking { service(port).refreshStatus(RefreshDisputeCommand(UUID.randomUUID(), "idem-refresh-y")) }
            }.isInstanceOf(IdempotencyKeyReusedException::class.java)
            coVerify(exactly = 1) { port.status(any()) }
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
