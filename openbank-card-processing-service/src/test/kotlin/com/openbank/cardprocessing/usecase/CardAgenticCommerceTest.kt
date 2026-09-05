// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.cardprocessing.application.port.`in`.AuthorizationCommand
import com.openbank.cardprocessing.application.port.out.AgentMandatePort
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
import com.openbank.cardprocessing.application.port.out.MandateOutcome
import com.openbank.cardprocessing.application.port.out.MandateVerification
import com.openbank.cardprocessing.application.port.out.PresentedMandate
import com.openbank.cardprocessing.application.usecase.CardProcessingService
import com.openbank.cardprocessing.domain.event.CardDeclined
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.CountedSpend
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.infrastructure.scheme.SimulatedSchemeAdapter
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
 * Agent-initiated card purchases under an AP2 mandate (ADR-0283 D6, ADR-0193).
 *
 * THE PROPERTIES THIS FILE EXISTS FOR, none of which any other test can see:
 *
 *  1. A purchase with NO mandate is unchanged, and the mandate port is never consulted. Without
 *     this, the agentic path could quietly become mandatory for every card tap.
 *  2. A mandate that does not authorise the payment DECLINES BEFORE the issuer is asked. Asking the
 *     issuer first would make the customer's controls the only thing standing between an
 *     over-reaching agent and the money.
 *  3. A verifier that cannot answer also declines — fail CLOSED — with its OWN reason. "The agent
 *     exceeded its authority" and "we could not tell" must not look alike to a customer, a metric
 *     or a chargeback.
 *  4. Either way a ROW is written and `card.declined.v1` is published. An exception would be a 500
 *     with no record that an agent ever tried, and the record is what a dispute turns on.
 */
class CardAgenticCommerceTest {

    private val now = Instant.parse("2026-09-05T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val cardId = UUID.randomUUID()

    private val repository = mockk<CardAuthorizationRepository>()
    private val cards = mockk<CardLookupPort>()
    private val issuer = mockk<CardIssuancePolicyPort>()
    private val ledger = mockk<LedgerPostingPort>()
    private val fraud = mockk<FraudScoringPort>()
    private val mandates = mockk<AgentMandatePort>()
    private val metrics = mockk<CardProcessingMetricsPort>(relaxed = true)
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    private fun service() = CardProcessingService(
        repository = repository,
        cards = cards,
        issuerPolicy = issuer,
        ledger = ledger,
        fraud = fraud,
        merchants = SimulatedSchemeAdapter(),
        mandates = mandates,
        metrics = metrics,
        mapper = mapper,
        clock = clock,
        holdExpiryDays = 7,
    )

    private val mandate = PresentedMandate(
        kind = "PAYMENT",
        issuer = "did:example:customer",
        subject = "agent:shopping-assistant",
        signingInput = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJhZ2VudCJ9",
        signatureB64 = "c2lnbmF0dXJl",
        algorithm = "ES256",
        payee = "Shop",
        amountCapMinorUnits = 50_000,
        currency = "CZK",
        expiresAt = now.plusSeconds(3_600),
        singleUse = false,
        agentId = "agent:shopping-assistant",
    )

    private fun command(withMandate: Boolean, key: String = "idem-agent-1") = AuthorizationCommand(
        cardId = cardId,
        amountMinorUnits = 25_000,
        currencyCode = "CZK",
        channel = PresentmentChannel.ONLINE,
        mcc = "5411",
        merchantName = "Shop",
        merchantCountry = "CZ",
        networkReference = "acq-1",
        idempotencyKey = key,
        mandate = if (withMandate) mandate else null,
    )

    @Test
    fun `a purchase with no mandate is unchanged and never consults the mandate port`(): Unit = runBlocking {
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        coEvery { cards.lookup(cardId) } returns CardOwnership(UUID.randomUUID(), UUID.randomUUID(), "CZK")
        coEvery { repository.countSpend(any(), any(), any()) } returns CountedSpend(0, 0, 0)
        coEvery { issuer.decide(any(), any(), any(), any(), any(), any()) } returns
            IssuerDecision(approved = true, reason = null, category = "GROCERIES")
        coEvery { fraud.score(any()) } returns FraudScore(FraudScoringOutcome.SKIPPED_DISABLED, null, null)
        val saved = slot<CardAuthorization>()
        coEvery { repository.save(capture(saved), any(), any()) } answers { saved.captured }

        val authorization = service().authorize(command(withMandate = false))

        assertThat(authorization.status).isEqualTo(AuthorizationStatus.APPROVED)
        assertThat(authorization.initiatedByAgentId).isNull()
        // The discriminating assertion: the agentic path must not become mandatory for a card tap.
        coVerify(exactly = 0) { mandates.verify(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a rejected mandate declines BEFORE the issuer is asked, and records the attempt`(): Unit = runBlocking {
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        coEvery { cards.lookup(cardId) } returns CardOwnership(UUID.randomUUID(), UUID.randomUUID(), "CZK")
        coEvery { fraud.score(any()) } returns FraudScore(FraudScoringOutcome.SKIPPED_DISABLED, null, null)
        coEvery { mandates.verify(any(), any(), any(), any(), any()) } returns
            MandateVerification(MandateOutcome.REJECTED, listOf("amount 25000 exceeds cap 10000"))
        val saved = slot<CardAuthorization>()
        val event = slot<OutboxMessage>()
        coEvery { repository.save(capture(saved), capture(event), any()) } answers { saved.captured }

        val authorization = service().authorize(command(withMandate = true))

        assertThat(authorization.status).isEqualTo(AuthorizationStatus.DECLINED)
        assertThat(authorization.declineReason).isEqualTo("AGENT_MANDATE_REJECTED")
        assertThat(authorization.initiatedByAgentId).isEqualTo("agent:shopping-assistant")
        // The issuer was never asked: an over-reaching agent must not get as far as the customer's
        // own controls being the only thing in the way.
        coVerify(exactly = 0) { issuer.decide(any(), any(), any(), any(), any(), any()) }
        // The attempt is recorded and announced — a 500 would leave nothing behind.
        assertThat(event.captured.eventType).isEqualTo(CardDeclined.EVENT_TYPE)
    }

    @Test
    fun `a verifier that cannot answer declines with its OWN reason`(): Unit = runBlocking {
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        coEvery { cards.lookup(cardId) } returns CardOwnership(UUID.randomUUID(), UUID.randomUUID(), "CZK")
        coEvery { fraud.score(any()) } returns FraudScore(FraudScoringOutcome.SKIPPED_DISABLED, null, null)
        coEvery { mandates.verify(any(), any(), any(), any(), any()) } returns
            MandateVerification(MandateOutcome.UNVERIFIABLE, detail = "connect timeout")
        val saved = slot<CardAuthorization>()
        coEvery { repository.save(capture(saved), any(), any()) } answers { saved.captured }

        val authorization = service().authorize(command(withMandate = true))

        assertThat(authorization.status).isEqualTo(AuthorizationStatus.DECLINED)
        // NOT the same reason as a rejection. "The agent exceeded its authority" is a customer
        // conversation; "we could not establish authority" is an incident.
        assertThat(authorization.declineReason).isEqualTo("AGENT_MANDATE_UNVERIFIABLE")
        coVerify(exactly = 0) { issuer.decide(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a verified mandate proceeds to the issuer and marks the purchase as agent-initiated`(): Unit = runBlocking {
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        coEvery { cards.lookup(cardId) } returns CardOwnership(UUID.randomUUID(), UUID.randomUUID(), "CZK")
        coEvery { repository.countSpend(any(), any(), any()) } returns CountedSpend(0, 0, 0)
        coEvery { fraud.score(any()) } returns FraudScore(FraudScoringOutcome.SKIPPED_DISABLED, null, null)
        coEvery { mandates.verify(any(), any(), any(), any(), any()) } returns
            MandateVerification(MandateOutcome.VERIFIED)
        coEvery { issuer.decide(any(), any(), any(), any(), any(), any()) } returns
            IssuerDecision(approved = true, reason = null, category = "GROCERIES")
        val saved = slot<CardAuthorization>()
        coEvery { repository.save(capture(saved), any(), any()) } answers { saved.captured }

        val authorization = service().authorize(command(withMandate = true))

        assertThat(authorization.status).isEqualTo(AuthorizationStatus.APPROVED)
        assertThat(authorization.initiatedByAgentId).isEqualTo("agent:shopping-assistant")
        // A verified mandate does not replace the customer's controls — it only adds the agent's
        // authority to them, so the issuer is still asked.
        coVerify(exactly = 1) { issuer.decide(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the payment sent for verification is the ACQUIRER's, never the mandate's own figures`(): Unit = runBlocking {
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        coEvery { cards.lookup(cardId) } returns CardOwnership(UUID.randomUUID(), UUID.randomUUID(), "CZK")
        coEvery { repository.countSpend(any(), any(), any()) } returns CountedSpend(0, 0, 0)
        coEvery { fraud.score(any()) } returns FraudScore(FraudScoringOutcome.SKIPPED_DISABLED, null, null)
        coEvery { issuer.decide(any(), any(), any(), any(), any(), any()) } returns
            IssuerDecision(approved = true, reason = null, category = "GROCERIES")
        val amount = slot<Long>()
        val currency = slot<String>()
        val payee = slot<String>()
        coEvery {
            mandates.verify(any(), capture(amount), capture(currency), capture(payee), any())
        } returns MandateVerification(MandateOutcome.VERIFIED)
        val saved = slot<CardAuthorization>()
        coEvery { repository.save(capture(saved), any(), any()) } answers { saved.captured }

        service().authorize(command(withMandate = true))

        // Sending the mandate's own cap back would ask the verifier to compare a value with itself,
        // and every constraint check would pass by construction — a verification that cannot fail.
        assertThat(amount.captured).isEqualTo(25_000)
        assertThat(amount.captured).isNotEqualTo(mandate.amountCapMinorUnits)
        assertThat(currency.captured).isEqualTo("CZK")
        assertThat(payee.captured).isEqualTo("Shop")
    }
}
