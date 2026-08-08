// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.openbank.cardissuance.application.port.`in`.IssueCardCommand
import com.openbank.cardissuance.application.port.out.CardConfigLookup
import com.openbank.cardissuance.application.port.out.CardProductCatalogPort
import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.CardNetwork
import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import com.openbank.cardissuance.infrastructure.crypto.AesGcmCardSecretCipher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional
import java.util.UUID

/**
 * ADR-0249 D1/D2 — the additional cardholder ("dodatková karta") as card-issuance sees it.
 *
 * Two things are pinned here, and they are the two that make the feature real rather than
 * decorative: that a card can be issued to a holder who is not the account owner AND remembers the
 * grant it rests on, and that when that grant ends the card is ENDED — not hidden, not unshared.
 * A card that still authorises at a terminal after its authority is gone is the failure this ADR
 * exists to prevent, so "revocation blocks the card" is a test, not a comment.
 */
class DelegatedCardIssuanceTest {

    private lateinit var repo: CardRepository
    private lateinit var catalog: CardProductCatalogPort
    private lateinit var service: CardService

    // TEST ONLY key — a deterministic 32-byte array, never a committed key-shaped literal.
    private val cipher = AesGcmCardSecretCipher(
        Optional.of(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })),
        false,
    )

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    private val clock = Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC)

    private val grantor = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val grantee = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val account = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val grantId = UUID.fromString("44444444-4444-4444-4444-444444444444")

    @BeforeEach
    fun setUp() {
        repo = mockk()
        catalog = mockk()
        coEvery { catalog.findCardConfig(any()) } returns CardConfigLookup.Unavailable
        service = CardService(repo, mapper, clock, cipher, catalog)
    }

    // ── D1: the card is the delegate's, on the grantor's account ────────────────

    @Test
    fun `a card issued under a grant is held by the GRANTEE on the GRANTOR's account`(): Unit = runBlocking {
        val cmd = delegatedIssueCmd()
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returns null
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        val card = service.issueCard(cmd)

        // The whole shape of D1 in one assertion: holder and account are DIFFERENT parties' things.
        assertThat(card.partyId).isEqualTo(grantee)
        assertThat(card.accountId).isEqualTo(account)
        assertThat(card.partyId).isNotEqualTo(grantor)
        // The link, without which revocation has nothing to find.
        assertThat(card.delegationGrantId).isEqualTo(grantId)
        assertThat(card.isDelegated).isTrue()
        // Its own ceilings, in minor units — the numbers the card rail actually counts.
        assertThat(card.dailyLimitMinorUnits).isEqualTo(50_000L)
        assertThat(card.monthlyLimitMinorUnits).isEqualTo(500_000L)
    }

    @Test
    fun `an ordinary card carries no grant and is not delegated`(): Unit = runBlocking {
        val cmd = delegatedIssueCmd().copy(idempotencyKey = "idem-plain", delegationGrantId = null)
        coEvery { repo.findByIdempotencyKey(cmd.idempotencyKey) } returns null
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        val card = service.issueCard(cmd)

        // Absence means "the holder is the account owner", not "unknown" — every pre-ADR card is here.
        assertThat(card.delegationGrantId).isNull()
        assertThat(card.isDelegated).isFalse()
    }

    // ── D2: revocation bites ───────────────────────────────────────────────────

    @Test
    fun `revoking the grant BLOCKS the live card it authorised`(): Unit = runBlocking {
        val live = delegatedCard(CardStatus.ACTIVE)
        coEvery { repo.findByDelegationGrantId(grantId) } returns listOf(live)
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        val ended = service.blockCardsForRevokedGrant(grantId, "DELEGATION_DelegationRevoked")

        assertThat(ended).hasSize(1)
        assertThat(ended.single().status).isEqualTo(CardStatus.BLOCKED)
        assertThat(ended.single().blockedReason).isEqualTo("DELEGATION_DelegationRevoked")
        // Blocked, not merely dropped from a list: a hidden card still authorises at a terminal.
        assertThat(ended.single().status).isNotEqualTo(CardStatus.ACTIVE)
    }

    @Test
    fun `the block travels on the ordinary status-changed event, so the rails hear it`(): Unit = runBlocking {
        val live = delegatedCard(CardStatus.ACTIVE)
        coEvery { repo.findByDelegationGrantId(grantId) } returns listOf(live)
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        service.blockCardsForRevokedGrant(grantId, "DELEGATION_DelegationRevoked")

        coVerify {
            repo.save(
                match { it.id == live.id && it.status == CardStatus.BLOCKED },
                match {
                    it.aggregateId == live.id &&
                        it.eventType == CardService.EVENT_CARD_STATUS_CHANGED &&
                        it.payload.contains("ACTIVE") &&
                        it.payload.contains("BLOCKED") &&
                        it.payload.contains(CardService.CHANGED_BY_DELEGATION)
                },
            )
        }
    }

    @Test
    fun `a frozen delegated card is blocked too — a suspend is not a safe resting place`(): Unit = runBlocking {
        val frozen = delegatedCard(CardStatus.SUSPENDED)
        coEvery { repo.findByDelegationGrantId(grantId) } returns listOf(frozen)
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        // SUSPENDED is reversible by the holder, so leaving it there would let the delegate simply
        // unfreeze a card whose authority no longer exists.
        assertThat(service.blockCardsForRevokedGrant(grantId, "r").single().status)
            .isEqualTo(CardStatus.BLOCKED)
    }

    @Test
    fun `a card whose plastic never arrived is CANCELLED, since PENDING cannot be blocked`(): Unit = runBlocking {
        val pending = delegatedCard(CardStatus.PENDING)
        coEvery { repo.findByDelegationGrantId(grantId) } returns listOf(pending)
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        // `block` has no PENDING transition; without this branch the revocation would THROW and the
        // consumer would retry it forever, leaving the card alive the whole time.
        assertThat(service.blockCardsForRevokedGrant(grantId, "r").single().status)
            .isEqualTo(CardStatus.CANCELLED)
    }

    @Test
    fun `a redelivered revocation is a no-op on cards that are already dead`(): Unit = runBlocking {
        coEvery { repo.findByDelegationGrantId(grantId) } returns listOf(
            delegatedCard(CardStatus.BLOCKED),
            delegatedCard(CardStatus.CANCELLED),
            delegatedCard(CardStatus.EXPIRED),
        )

        assertThat(service.blockCardsForRevokedGrant(grantId, "r")).isEmpty()
        // Re-blocking a BLOCKED card would overwrite "LOST_OR_STOLEN" with a bookkeeping reason.
        coVerify(exactly = 0) { repo.save(any(), any()) }
    }

    @Test
    fun `a grant that authorised no card ends quietly`(): Unit = runBlocking {
        coEvery { repo.findByDelegationGrantId(grantId) } returns emptyList()

        assertThat(service.blockCardsForRevokedGrant(grantId, "r")).isEmpty()
        coVerify(exactly = 0) { repo.save(any(), any()) }
    }

    @Test
    fun `every card under one grant is ended, not just the first`(): Unit = runBlocking {
        val a = delegatedCard(CardStatus.ACTIVE)
        val b = delegatedCard(CardStatus.ACTIVE)
        coEvery { repo.findByDelegationGrantId(grantId) } returns listOf(a, b)
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        val ended = service.blockCardsForRevokedGrant(grantId, "r")

        assertThat(ended).hasSize(2)
        assertThat(ended.map { it.status }).containsOnly(CardStatus.BLOCKED)
        coVerify(exactly = 2) { repo.save(any(), any()) }
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private fun delegatedIssueCmd() = IssueCardCommand(
        idempotencyKey = "dcard-$grantee-$account-r0",
        partyId = grantee,
        accountId = account,
        productCode = "CURRENT_CZK",
        cardType = CardType.VIRTUAL,
        network = CardNetwork.VISA,
        cardholderName = "Petr Novak",
        embossedName = "PETR NOVAK",
        currency = "CZK",
        dailyLimitMinorUnits = 50_000L,
        monthlyLimitMinorUnits = 500_000L,
        deliveryAddress = null,
        delegationGrantId = grantId,
    )

    private fun delegatedCard(status: CardStatus) = Card(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-${UUID.randomUUID()}",
        partyId = grantee,
        accountId = account,
        productCode = "CURRENT_CZK",
        cardType = CardType.VIRTUAL,
        network = CardNetwork.VISA,
        maskedPan = "**** **** **** 4242",
        cardholderName = "Petr Novak",
        embossedName = "PETR NOVAK",
        expiryDate = LocalDate.of(2030, 12, 31),
        status = status,
        dailyLimitMinorUnits = 50_000L,
        monthlyLimitMinorUnits = 500_000L,
        currency = "CZK",
        deliveryAddress = null,
        activatedAt = null,
        blockedAt = null,
        blockedReason = null,
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        delegationGrantId = grantId,
    )
}
