// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.cardprocessing.application.port.`in`.ChangeTokenStatusCommand
import com.openbank.cardprocessing.application.port.`in`.ProvisionTokenCommand
import com.openbank.cardprocessing.application.port.out.CardIssuerUnavailableException
import com.openbank.cardprocessing.application.port.out.CardLifecycleMetricsPort
import com.openbank.cardprocessing.application.port.out.CardLookupPort
import com.openbank.cardprocessing.application.port.out.CardOwnership
import com.openbank.cardprocessing.application.port.out.CardTokenRegistrationRepository
import com.openbank.cardprocessing.application.port.out.IdempotencyClaim
import com.openbank.cardprocessing.application.port.out.LifecycleOperation
import com.openbank.cardprocessing.application.usecase.CardTokenService
import com.openbank.cardprocessing.domain.event.CardTokenProvisioned
import com.openbank.cardprocessing.domain.model.CardTokenRegistration
import com.openbank.cardprocessing.domain.model.TokenOutcome
import com.openbank.cardprocessing.domain.model.TokenReadSource
import com.openbank.cardprocessing.domain.model.TokenRefusal
import com.openbank.cardprocessing.infrastructure.scheme.SimulatedTokenisationAdapter
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.NetworkToken
import com.openbank.libs.domain.cards.scheme.NetworkTokenStatus
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import com.openbank.libs.domain.cards.scheme.TokenRequestor
import com.openbank.libs.domain.cards.scheme.TokenisationPort
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The token path against a real simulator and a mocked mirror.
 *
 * What these assert, beyond coverage: that a degraded read is LABELLED as one, that a scheme which
 * cannot answer writes no row, and that a repeated idempotency key does not mint a second wallet
 * credential. Each is a property the code could lose without any other test noticing.
 */
class CardTokenServiceTest {

    private val now = Instant.parse("2026-09-05T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val cardId = UUID.randomUUID()

    private val registrations = mockk<CardTokenRegistrationRepository>()
    private val cards = mockk<CardLookupPort>()
    private val metrics = mockk<CardLifecycleMetricsPort>(relaxed = true)
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    // The real simulator, not a mock: it is the binding this repository ships, and a mock would let
    // the reconcile and terminal-status paths pass without the port ever being exercised.
    private val simulator = SimulatedTokenisationAdapter(clock)

    private val idempotency = FakeLifecycleIdempotency()

    private fun service(port: TokenisationPort = simulator) =
        CardTokenService(port, registrations, idempotency, cards, metrics, mapper, clock)

    private fun activeCard(status: String? = "ACTIVE") =
        CardOwnership(UUID.randomUUID(), UUID.randomUUID(), "CZK", status)

    /** A save that completes the claim the way the real repository does, inside its transaction. */
    private fun savingCompletes(saved: CapturingSlot<CardTokenRegistration>, event: CapturingSlot<OutboxMessage>) {
        coEvery { registrations.save(capture(saved), capture(event), any(), any()) } answers {
            arg<IdempotencyClaim?>(3)?.let { idempotency.complete(it, saved.captured.id) }
            saved.captured
        }
    }

    private fun command(key: String = "idem-token-1") = ProvisionTokenCommand(cardId, "wallet-apple", "Apple Pay", key)

    @Test
    fun `provisioning mirrors what the scheme answered and emits the event in the same write`(): Unit = runBlocking {
        coEvery { cards.lookup(cardId) } returns activeCard()
        val saved = slot<CardTokenRegistration>()
        val event = slot<OutboxMessage>()
        savingCompletes(saved, event)

        val outcome = service().provision(command())

        assertThat(outcome).isInstanceOf(TokenOutcome.Provisioned::class.java)
        val registration = (outcome as TokenOutcome.Provisioned).registration
        assertThat(registration.tokenReference).startsWith("sim-tok-")
        assertThat(registration.status).isEqualTo(NetworkTokenStatus.ACTIVE)
        assertThat(registration.scheme).isEqualTo(CardScheme.SIMULATOR)
        // Recency, never isNotNull(): an Instant.EPOCH default passes a non-null assertion (#3882).
        assertThat(registration.provisionedAt).isEqualTo(now)
        assertThat(event.captured.eventType).isEqualTo(CardTokenProvisioned.EVENT_TYPE)
        assertThat(event.captured.aggregateId).isEqualTo(registration.id)
    }

    @Test
    fun `a repeated idempotency key replays the first registration and does not call the scheme again`(): Unit =
        runBlocking {
            coEvery { cards.lookup(cardId) } returns activeCard()
            val saved = slot<CardTokenRegistration>()
            savingCompletes(saved, slot())
            val port = mockk<TokenisationPort>()
            coEvery { port.provision(any(), any()) } coAnswers { simulator.provision(firstArg(), secondArg()) }
            val first = service(port).provision(command()) as TokenOutcome.Provisioned
            coEvery { registrations.findById(first.registration.id) } returns first.registration

            val replay = service(port).provision(command())

            assertThat((replay as TokenOutcome.Provisioned).registration).isEqualTo(first.registration)
            // The discriminating assertion: a retry that reached the scheme would mint a SECOND
            // wallet credential the customer can see, and every other assertion here still passes.
            coVerify(exactly = 1) { port.provision(any(), any()) }
        }

    @Test
    fun `a same-key request while the first is still running is told in progress and never reaches the scheme`(): Unit =
        runBlocking {
            idempotency.reserve(LifecycleOperation.TOKEN_PROVISION, "idem-token-1", fingerprintOfFirstCall())
            val port = mockk<TokenisationPort>()

            assertThatThrownBy { runBlocking { service(port).provision(command()) } }
                .isInstanceOf(IdempotencyRequestInProgressException::class.java)
            coVerify(exactly = 0) { port.provision(any(), any()) }
            coVerify(exactly = 0) { cards.lookup(any()) }
        }

    @Test
    fun `the same key with a different request is a reuse conflict, not a replay`(): Unit = runBlocking {
        idempotency.reserve(LifecycleOperation.TOKEN_PROVISION, "idem-token-1", "f".repeat(64))
        val port = mockk<TokenisationPort>()

        assertThatThrownBy { runBlocking { service(port).provision(command()) } }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)
        coVerify(exactly = 0) { port.provision(any(), any()) }
    }

    @Test
    fun `a scheme that cannot answer refuses, writes no row and frees the key for a retry`(): Unit = runBlocking {
        coEvery { cards.lookup(cardId) } returns activeCard()
        val unbound = mockk<TokenisationPort>()
        coEvery { unbound.provision(any(), any()) } returns SchemeResult.Unanswered(
            SchemeFailure.NOT_BOUND,
            CardScheme.VISA,
            "tokenisation on visa needs a scheme contract",
        )

        val outcome = service(unbound).provision(command())

        assertThat(outcome).isInstanceOf(TokenOutcome.Refused::class.java)
        assertThat((outcome as TokenOutcome.Refused).reason).isEqualTo(TokenRefusal.SCHEME_UNAVAILABLE)
        coVerify(exactly = 0) { registrations.save(any(), any(), any(), any()) }
        assertThat(idempotency.isPending(LifecycleOperation.TOKEN_PROVISION, "idem-token-1")).isFalse()
    }

    @Test
    fun `a failure after the scheme answered leaves the key pending so a retry cannot mint a second token`(): Unit =
        runBlocking {
            coEvery { cards.lookup(cardId) } returns activeCard()
            coEvery { registrations.save(any(), any(), any(), any()) } throws IllegalStateException("db down")

            assertThatThrownBy { runBlocking { service().provision(command()) } }
                .isInstanceOf(IllegalStateException::class.java)
            assertThat(idempotency.isPending(LifecycleOperation.TOKEN_PROVISION, "idem-token-1")).isTrue()
            assertThatThrownBy { runBlocking { service().provision(command()) } }
                .isInstanceOf(IdempotencyRequestInProgressException::class.java)
        }

    @Test
    fun `an unknown card is refused before the scheme is asked`(): Unit = runBlocking {
        coEvery { cards.lookup(cardId) } returns null
        val port = mockk<TokenisationPort>()

        val outcome = service(port).provision(command())

        assertThat((outcome as TokenOutcome.Refused).reason).isEqualTo(TokenRefusal.CARD_NOT_FOUND)
        assertThat(outcome.detail).doesNotContain("could not be reached")
        coVerify(exactly = 0) { port.provision(any(), any()) }
        coVerify { metrics.tokenProvisioned("NONE", TokenRefusal.CARD_NOT_FOUND.name) }
    }

    @Test
    fun `card-issuance being unreachable is ISSUER_UNAVAILABLE, not an unknown card, and frees the key`(): Unit =
        runBlocking {
            coEvery { cards.lookup(cardId) } throws CardIssuerUnavailableException(RuntimeException("connect refused"))
            val port = mockk<TokenisationPort>()

            val outcome = service(port).provision(command())

            assertThat((outcome as TokenOutcome.Refused).reason).isEqualTo(TokenRefusal.ISSUER_UNAVAILABLE)
            coVerify(exactly = 0) { port.provision(any(), any()) }
            coVerify { metrics.tokenProvisioned("NONE", TokenRefusal.ISSUER_UNAVAILABLE.name) }
            assertThat(idempotency.isPending(LifecycleOperation.TOKEN_PROVISION, "idem-token-1")).isFalse()
        }

    @ParameterizedTest
    @ValueSource(strings = ["BLOCKED", "SUSPENDED", "EXPIRED", "CANCELLED", "PENDING"])
    fun `a card that is not ACTIVE is never tokenised`(status: String): Unit = runBlocking {
        coEvery { cards.lookup(cardId) } returns activeCard(status)
        val port = mockk<TokenisationPort>()

        val outcome = service(port).provision(command())

        assertThat((outcome as TokenOutcome.Refused).reason).isEqualTo(TokenRefusal.CARD_NOT_ACTIVE)
        assertThat(outcome.detail).contains(status)
        // The discriminating assertion: a token minted for a blocked card is a live credential for
        // a card the bank has stopped.
        coVerify(exactly = 0) { port.provision(any(), any()) }
    }

    @Test
    fun `a card whose state card-issuance did not report is treated as not active`(): Unit = runBlocking {
        coEvery { cards.lookup(cardId) } returns activeCard(null)
        val port = mockk<TokenisationPort>()

        val outcome = service(port).provision(command())

        assertThat((outcome as TokenOutcome.Refused).reason).isEqualTo(TokenRefusal.CARD_NOT_ACTIVE)
        coVerify(exactly = 0) { port.provision(any(), any()) }
    }

    private fun fingerprintOfFirstCall(): String = RequestFingerprint.of(
        "POST",
        "/api/v1/card-tokens",
        listOf(cardId, "wallet-apple", "Apple Pay").joinToString("\n"),
    )

    @Test
    fun `a live read is labelled NETWORK and a degraded read is labelled LOCAL_MIRROR`(): Unit = runBlocking {
        val mirror = listOf(registration(NetworkTokenStatus.ACTIVE))
        coEvery { registrations.findByCardId(cardId) } returns mirror
        coEvery { registrations.adoptNetworkSeen(any()) } answers { firstArg() }

        // The scheme answers: provenance is NETWORK and no degraded reason is offered.
        simulator.provision(cardId.toString(), TokenRequestor("wallet-apple", "Apple Pay"))
        val live = service().listForCard(cardId)
        assertThat(live.source).isEqualTo(TokenReadSource.NETWORK)
        assertThat(live.degradedReason).isNull()

        // The scheme cannot answer: the same rows come back, and the answer says so. This pair is
        // the test — a single assertion on the list contents cannot tell the two apart, which is
        // exactly how a stale ACTIVE gets rendered as current.
        val unavailable = mockk<TokenisationPort>()
        coEvery { unavailable.listTokens(any()) } returns SchemeResult.Unanswered(
            SchemeFailure.UNAVAILABLE,
            CardScheme.SIMULATOR,
            "connect timeout",
        )
        val degraded = service(unavailable).listForCard(cardId)
        assertThat(degraded.source).isEqualTo(TokenReadSource.LOCAL_MIRROR)
        assertThat(degraded.tokens).isEqualTo(mirror)
        assertThat(degraded.degradedReason).contains("UNAVAILABLE").contains("connect timeout")
    }

    @Test
    fun `a network token the mirror has never seen is adopted into the mirror with one stable id`(): Unit =
        runBlocking {
            coEvery { registrations.findByCardId(cardId) } returns emptyList()
            val stored = mutableMapOf<String, CardTokenRegistration>()
            coEvery { registrations.adoptNetworkSeen(any()) } answers {
                firstArg<List<CardTokenRegistration>>().map { stored.getOrPut(it.tokenReference) { it } }
            }
            val port = mockk<TokenisationPort>()
            coEvery { port.listTokens(any()) } returns SchemeResult.Answered(
                listOf(NetworkToken("tok-unknown", "4242", NetworkTokenStatus.SUSPENDED, null, "wallet-google")),
                CardScheme.MASTERCARD,
            )

            val first = service(port).listForCard(cardId)
            val second = service(port).listForCard(cardId)

            assertThat(first.tokens).hasSize(1)
            assertThat(first.tokens.single().status).isEqualTo(NetworkTokenStatus.SUSPENDED)
            // The scheme comes from the ANSWER. Attributing it to the configured binding would name
            // a network that did not reply.
            assertThat(first.tokens.single().scheme).isEqualTo(CardScheme.MASTERCARD)
            // The defect this pins: a fresh random id on every read, and nothing saved.
            assertThat(second.tokens.single().id).isEqualTo(first.tokens.single().id)
            assertThat(stored).containsKey("tok-unknown")
        }

    @Test
    fun `a mirrored token the network no longer returns is kept and flagged, never dropped`(): Unit = runBlocking {
        val gone = registration(NetworkTokenStatus.ACTIVE)
        coEvery { registrations.findByCardId(cardId) } returns listOf(gone)
        val port = mockk<TokenisationPort>()
        coEvery { port.listTokens(any()) } returns SchemeResult.Answered(emptyList(), CardScheme.SIMULATOR)

        val answer = service(port).listForCard(cardId)

        assertThat(answer.source).isEqualTo(TokenReadSource.NETWORK)
        assertThat(answer.tokens).hasSize(1)
        assertThat(answer.tokens.single().id).isEqualTo(gone.id)
        assertThat(answer.tokens.single().absentAtNetwork).isTrue()
        coVerify(exactly = 0) { registrations.adoptNetworkSeen(any()) }
    }

    @Test
    fun `a deleted token is terminal and the scheme is never asked to change it`(): Unit = runBlocking {
        coEvery { registrations.findByTokenReference("tok-dead") } returns registration(NetworkTokenStatus.DELETED)
        val port = mockk<TokenisationPort>()

        val outcome = service(port)
            .changeStatus(ChangeTokenStatusCommand("tok-dead", NetworkTokenStatus.ACTIVE))

        assertThat((outcome as TokenOutcome.Refused).reason).isEqualTo(TokenRefusal.TOKEN_TERMINAL)
        // The rule is the aggregate's, not the adapter's: it must hold for a binding that would
        // have accepted the call.
        coVerify(exactly = 0) { port.changeStatus(any(), any()) }
    }

    private fun registration(status: NetworkTokenStatus) = CardTokenRegistration(
        id = UUID.randomUUID(),
        cardId = cardId,
        tokenReference = "tok-dead",
        requestorId = "wallet-apple",
        requestorLabel = "Apple Pay",
        last4 = "0000",
        status = status,
        scheme = CardScheme.SIMULATOR,
        expiry = null,
        provisionedAt = now,
        updatedAt = now,
    )
}
