// SPDX-License-Identifier: Apache-2.0
package com.openbank.cardissuance.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.openbank.cardissuance.application.port.`in`.CardStatusCommand
import com.openbank.cardissuance.application.port.`in`.IssueCardCommand
import com.openbank.cardissuance.application.port.`in`.UpdateControlsCommand
import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.CardNetwork
import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class CardServiceTest {
    private lateinit var repo: CardRepository
    private lateinit var service: CardService

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    private val clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        repo = mockk()
        service = CardService(repo, mapper, clock)
    }

    @Test fun `issue card is idempotent on same idempotency key`(): Unit = runBlocking {
        val existing = card(status = CardStatus.PENDING)
        coEvery { repo.findByIdempotencyKey(existing.idempotencyKey) } returns existing

        val result = service.issueCard(issueCmd(existing.idempotencyKey))

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) { repo.save(any(), any()) }
    }

    @Test fun `issue card writes the card and its issued event to the outbox`(): Unit = runBlocking {
        val command = issueCmd("idem-new")
        coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        val result = service.issueCard(command)

        assertThat(result.status).isEqualTo(CardStatus.PENDING)
        // The aggregate and its domain event are handed to the repository together (ADR-0050): the
        // event is keyed on the card id (aggregateId, N2) and carries the versioned event type (N3).
        coVerify {
            repo.save(
                match { it.idempotencyKey == command.idempotencyKey && it.status == CardStatus.PENDING },
                match {
                    it.aggregateId == result.id &&
                        it.eventType == CardService.EVENT_CARD_ISSUED &&
                        it.payload.contains(result.id.toString())
                },
            )
        }
    }

    @Test fun `activate card writes status changed event to the outbox`(): Unit = runBlocking {
        val pending = card(status = CardStatus.PENDING)
        val command = CardStatusCommand(cardId = pending.id, reason = "Manual activation", changedBy = "ops-user")
        coEvery { repo.findById(pending.id) } returns pending
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        val result = service.activateCard(command)

        assertThat(result.status).isEqualTo(CardStatus.ACTIVE)
        coVerify {
            repo.save(
                match { it.id == pending.id && it.status == CardStatus.ACTIVE },
                match {
                    it.aggregateId == pending.id &&
                        it.eventType == CardService.EVENT_CARD_STATUS_CHANGED &&
                        it.payload.contains("PENDING") &&
                        it.payload.contains("ACTIVE") &&
                        it.payload.contains("Manual activation") &&
                        it.payload.contains("ops-user")
                },
            )
        }
    }

    @Test fun `updateControls applies the flags and emits a controls-changed event`(): Unit = runBlocking {
        val active = card(status = CardStatus.ACTIVE)
        coEvery { repo.findById(active.id) } returns active
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        val result = service.updateControls(
            UpdateControlsCommand(
                cardId = active.id,
                contactlessEnabled = false,
                onlineEnabled = true,
                atmEnabled = false,
                abroadEnabled = true,
                changedBy = "customer:${active.partyId}",
            ),
        )

        assertThat(result.contactlessEnabled).isFalse()
        assertThat(result.atmEnabled).isFalse()
        assertThat(result.abroadEnabled).isTrue()
        coVerify(exactly = 1) {
            repo.save(any(), match { it.eventType == CardService.EVENT_CARD_CONTROLS_CHANGED })
        }
    }

    @Test fun `block card requires reason validation`(): Unit = runBlocking {
        val active = card(status = CardStatus.ACTIVE)
        coEvery { repo.findById(active.id) } returns active

        assertThatThrownBy {
            runBlocking {
                service.blockCard(CardStatusCommand(cardId = active.id, reason = "   ", changedBy = "ops-user"))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Block reason required")

        coVerify(exactly = 0) { repo.save(any(), any()) }
    }

    // ── listByParty — GDPR Art. 15 export contribution (ADR-0118 §6, issue #268) ────────
    // party-service's GdprAggregationAdapter calls GET /api/v1/cards/party/{partyId},
    // backed by this pass-through. Covered here because it is the sole PII-exposure path
    // this service contributes to the cross-service subject-access export.

    @Test fun `listByParty returns every card issued to the party for GDPR export`(): Unit = runBlocking {
        val partyId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val cards = listOf(card(status = CardStatus.ACTIVE), card(status = CardStatus.BLOCKED))
        coEvery { repo.findByPartyId(partyId) } returns cards

        val result = service.listByParty(partyId)

        assertThat(result).hasSize(2)
        assertThat(result).allMatch { it.partyId == partyId }
        coVerify(exactly = 1) { repo.findByPartyId(partyId) }
    }

    @Test fun `listByParty returns an empty list when the party holds no cards`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findByPartyId(partyId) } returns emptyList()

        val result = service.listByParty(partyId)

        assertThat(result).isEmpty()
    }

    private fun issueCmd(idempotencyKey: String) = IssueCardCommand(
        idempotencyKey = idempotencyKey,
        partyId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        accountId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        productCode = "CARD-001",
        cardType = CardType.DEBIT,
        network = CardNetwork.VISA,
        cardholderName = "Jane Doe",
        embossedName = "JANE DOE",
        currency = "EUR",
        dailyLimitMinorUnits = 100_00,
        monthlyLimitMinorUnits = 1_000_00,
        deliveryAddress = "123 Main St",
    )

    private fun card(status: CardStatus) = Card(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        idempotencyKey = "idem-key",
        partyId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        accountId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        productCode = "CARD-001",
        cardType = CardType.DEBIT,
        network = CardNetwork.VISA,
        maskedPan = "**** **** **** 1234",
        cardholderName = "Jane Doe",
        embossedName = "JANE DOE",
        expiryDate = LocalDate.of(2030, 12, 31),
        status = status,
        dailyLimitMinorUnits = 100_00,
        monthlyLimitMinorUnits = 1_000_00,
        currency = "EUR",
        deliveryAddress = "123 Main St",
        activatedAt = null,
        blockedAt = null,
        blockedReason = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
