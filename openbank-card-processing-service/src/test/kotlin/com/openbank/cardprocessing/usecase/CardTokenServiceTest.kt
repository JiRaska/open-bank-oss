// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.cardprocessing.application.port.`in`.ChangeTokenStatusCommand
import com.openbank.cardprocessing.application.port.`in`.ProvisionTokenCommand
import com.openbank.cardprocessing.application.port.out.CardLifecycleMetricsPort
import com.openbank.cardprocessing.application.port.out.CardLookupPort
import com.openbank.cardprocessing.application.port.out.CardOwnership
import com.openbank.cardprocessing.application.port.out.CardTokenRegistrationRepository
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

    private fun service(port: TokenisationPort = simulator) =
        CardTokenService(port, registrations, cards, metrics, mapper, clock)

    private fun command(key: String = "idem-token-1") = ProvisionTokenCommand(cardId, "wallet-apple", "Apple Pay", key)

    @Test
    fun `provisioning mirrors what the scheme answered and emits the event in the same write`(): Unit = runBlocking {
        coEvery { registrations.findByIdempotencyKey(any()) } returns null
        coEvery { cards.lookup(cardId) } returns CardOwnership(UUID.randomUUID(), UUID.randomUUID(), "CZK")
        val saved = slot<CardTokenRegistration>()
        val event = slot<OutboxMessage>()
        coEvery { registrations.save(capture(saved), capture(event), any()) } answers { saved.captured }

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
    fun `a repeated idempotency key returns the first registration and does not call the scheme`(): Unit = runBlocking {
        val existing = registration(NetworkTokenStatus.ACTIVE)
        coEvery { registrations.findByIdempotencyKey("idem-token-1") } returns existing
        val port = mockk<TokenisationPort>()

        val outcome = service(port).provision(command())

        assertThat((outcome as TokenOutcome.Provisioned).registration).isEqualTo(existing)
        // The discriminating assertion: without the idempotency read, a retry mints a SECOND
        // wallet credential the customer can see, and every other assertion here still passes.
        coVerify(exactly = 0) { port.provision(any(), any()) }
        coVerify(exactly = 0) { registrations.save(any(), any(), any()) }
    }

    @Test
    fun `a scheme that cannot answer refuses and writes no row`(): Unit = runBlocking {
        coEvery { registrations.findByIdempotencyKey(any()) } returns null
        coEvery { cards.lookup(cardId) } returns CardOwnership(UUID.randomUUID(), UUID.randomUUID(), "CZK")
        val unbound = mockk<TokenisationPort>()
        coEvery { unbound.provision(any(), any()) } returns SchemeResult.Unanswered(
            SchemeFailure.NOT_BOUND,
            CardScheme.VISA,
            "tokenisation on visa needs a scheme contract",
        )

        val outcome = service(unbound).provision(command())

        assertThat(outcome).isInstanceOf(TokenOutcome.Refused::class.java)
        assertThat((outcome as TokenOutcome.Refused).reason).isEqualTo(TokenRefusal.SCHEME_UNAVAILABLE)
        coVerify(exactly = 0) { registrations.save(any(), any(), any()) }
    }

    @Test
    fun `an unknown card is refused before the scheme is asked`(): Unit = runBlocking {
        coEvery { registrations.findByIdempotencyKey(any()) } returns null
        coEvery { cards.lookup(cardId) } returns null
        val port = mockk<TokenisationPort>()

        val outcome = service(port).provision(command())

        assertThat((outcome as TokenOutcome.Refused).reason).isEqualTo(TokenRefusal.CARD_NOT_FOUND)
        coVerify(exactly = 0) { port.provision(any(), any()) }
    }

    @Test
    fun `a live read is labelled NETWORK and a degraded read is labelled LOCAL_MIRROR`(): Unit = runBlocking {
        val mirror = listOf(registration(NetworkTokenStatus.ACTIVE))
        coEvery { registrations.findByCardId(cardId) } returns mirror

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
    fun `a network token the mirror has never seen is still returned, attributed to the answering scheme`(): Unit =
        runBlocking {
            coEvery { registrations.findByCardId(cardId) } returns emptyList()
            val port = mockk<TokenisationPort>()
            coEvery { port.listTokens(any()) } returns SchemeResult.Answered(
                listOf(NetworkToken("tok-unknown", "4242", NetworkTokenStatus.SUSPENDED, null, "wallet-google")),
                CardScheme.MASTERCARD,
            )

            val answer = service(port).listForCard(cardId)

            assertThat(answer.tokens).hasSize(1)
            assertThat(answer.tokens.single().status).isEqualTo(NetworkTokenStatus.SUSPENDED)
            // The scheme comes from the ANSWER. Attributing it to the configured binding would name
            // a network that did not reply.
            assertThat(answer.tokens.single().scheme).isEqualTo(CardScheme.MASTERCARD)
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
