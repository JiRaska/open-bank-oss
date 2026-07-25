// SPDX-License-Identifier: Apache-2.0
package com.openbank.cardissuance.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.openbank.cardissuance.application.port.`in`.CardStatusCommand
import com.openbank.cardissuance.application.port.`in`.EntitlementSource
import com.openbank.cardissuance.application.port.`in`.IssueCardCommand
import com.openbank.cardissuance.application.port.`in`.ReadSecureDetailsQuery
import com.openbank.cardissuance.application.port.`in`.UpdateControlsCommand
import com.openbank.cardissuance.application.port.out.CardConfigLookup
import com.openbank.cardissuance.application.port.out.CardProductCatalogPort
import com.openbank.cardissuance.application.port.out.CardProductConfig
import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.CardEntitlementException
import com.openbank.cardissuance.domain.model.CardErrorCode
import com.openbank.cardissuance.domain.model.CardNetwork
import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import com.openbank.cardissuance.domain.model.SecureDetailsForbiddenException
import com.openbank.cardissuance.domain.model.SecureDetailsNotStoredException
import com.openbank.cardissuance.domain.model.SyntheticPanGenerator
import com.openbank.cardissuance.infrastructure.crypto.AesGcmCardSecretCipher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional
import java.util.UUID

class CardServiceTest {
    private lateinit var repo: CardRepository
    private lateinit var catalog: CardProductCatalogPort
    private lateinit var service: CardService

    // A real cipher, not a mock: the PAN vault round-trip is part of the behaviour under test.
    // TEST ONLY key — base64 of the ASCII string "openbank-test-only-aes-key-32byt".
    // Built here rather than pasted as a base64 literal: a committed key-shaped string is exactly
    // what the repo's secret scan exists to reject, and "but it's only a test key" is not a
    // distinction a scanner or a reader can make.
    private val cipher = AesGcmCardSecretCipher(
        Optional.of(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })),
        false,
    )

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    private val clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        repo = mockk()
        catalog = mockk()
        // Default: catalog says nothing. The fail-open path is the common case in these tests, and
        // the entitlement tests below opt into a Found config explicitly.
        coEvery { catalog.findCardConfig(any()) } returns CardConfigLookup.Unavailable
        service = CardService(repo, mapper, clock, cipher, catalog)
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

    // ── cancel (#2) ────────────────────────────────────────────────────────────────────

    @Test fun `cancel card writes a status changed event to the outbox`(): Unit = runBlocking {
        val active = card(status = CardStatus.ACTIVE)
        coEvery { repo.findById(active.id) } returns active
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        val result = service.cancelCard(CardStatusCommand(active.id, "Customer closed the card", "ops-user"))

        assertThat(result.status).isEqualTo(CardStatus.CANCELLED)
        coVerify(exactly = 1) {
            repo.save(
                match { it.status == CardStatus.CANCELLED },
                match {
                    it.eventType == CardService.EVENT_CARD_STATUS_CHANGED &&
                        it.payload.contains("CANCELLED") &&
                        it.payload.contains("Customer closed the card")
                },
            )
        }
    }

    @Test fun `cancel rejects an already cancelled card`(): Unit = runBlocking {
        val cancelled = card(status = CardStatus.CANCELLED)
        coEvery { repo.findById(cancelled.id) } returns cancelled

        assertThatThrownBy {
            runBlocking { service.cancelCard(CardStatusCommand(cancelled.id, null, "ops-user")) }
        }.isInstanceOf(IllegalArgumentException::class.java)

        coVerify(exactly = 0) { repo.save(any(), any()) }
    }

    // ── issued status by card type ─────────────────────────────────────────────────────
    // PENDING means "waiting for someone to receive the plastic". A card with no plastic has
    // nothing to wait for and no customer-facing activate route, so PENDING there is a dead end.

    @Test fun `a card with plastic is issued PENDING and not activated`(): Unit = runBlocking {
        listOf(CardType.DEBIT, CardType.CREDIT, CardType.PREPAID).forEach { type ->
            val command = issueCmd("idem-$type").copy(cardType = type)
            coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
            coEvery { repo.save(any(), any()) } answers { firstArg() }

            val issued = service.issueCard(command)

            assertThat(issued.status).describedAs("%s", type).isEqualTo(CardStatus.PENDING)
            assertThat(issued.activatedAt).describedAs("%s", type).isNull()
        }
    }

    @Test fun `a card with no plastic is issued ACTIVE and stamped as activated`(): Unit = runBlocking {
        listOf(CardType.VIRTUAL, CardType.SINGLE_USE).forEach { type ->
            val command = issueCmd("idem-$type").copy(cardType = type)
            coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
            coEvery { repo.save(any(), any()) } answers { firstArg() }

            val issued = service.issueCard(command)

            assertThat(issued.status).describedAs("%s", type).isEqualTo(CardStatus.ACTIVE)
            assertThat(issued.activatedAt).describedAs("%s", type).isEqualTo(Instant.now(clock))
        }
    }

    // The physical path must keep working: an ACTIVE-on-issue virtual card is NOT allowed to make
    // activate() unreachable for the plastic it exists for.
    @Test fun `a physical card issued PENDING can still be activated afterwards`(): Unit = runBlocking {
        val command = issueCmd("idem-physical-activate")
        coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { repo.save(any(), any()) } answers { firstArg() }
        val issued = service.issueCard(command)
        coEvery { repo.findById(issued.id) } returns issued

        val activated = service.activateCard(CardStatusCommand(issued.id, "Plastic received", "ops-user"))

        assertThat(activated.status).isEqualTo(CardStatus.ACTIVE)
        assertThat(activated.activatedAt).isEqualTo(Instant.now(clock))
    }

    // ── synthetic PAN vault (#3) ───────────────────────────────────────────────────────

    @Test fun `issue stores an encrypted Luhn-valid PAN and derives the mask from it`(): Unit = runBlocking {
        val command = issueCmd("idem-pan")
        coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        val issued = service.issueCard(command)

        val pan = cipher.decrypt(issued.panEncrypted!!)
        assertThat(SyntheticPanGenerator.isLuhnValid(pan)).isTrue()
        // The masked last-4 is DERIVED from the stored PAN — it used to be a random number that
        // corresponded to nothing.
        assertThat(issued.maskedPan).isEqualTo("**** **** **** ${pan.takeLast(4)}")
        assertThat(issued.panEncrypted).isNotEqualTo(pan)
        assertThat(cipher.decrypt(issued.cvvEncrypted!!)).hasSize(3)
    }

    @Test fun `secure details returns the decrypted credential for a virtual card`(): Unit = runBlocking {
        val credential = SyntheticPanGenerator.generate(CardNetwork.VISA)
        val virtual = card(status = CardStatus.ACTIVE).copy(
            cardType = CardType.VIRTUAL,
            maskedPan = credential.maskedPan,
            panEncrypted = cipher.encrypt(credential.pan),
            cvvEncrypted = cipher.encrypt(credential.cvv),
        )
        coEvery { repo.findById(virtual.id) } returns virtual

        val details = service.readSecureDetails(ReadSecureDetailsQuery(virtual.id, "ops-user"))

        assertThat(details.pan).isEqualTo(credential.pan)
        assertThat(details.cvv).isEqualTo(credential.cvv)
        assertThat(details.network).isEqualTo(CardNetwork.VISA)
    }

    @Test fun `secure details are refused for a card with plastic`(): Unit = runBlocking {
        val physical = card(status = CardStatus.ACTIVE).copy(
            cardType = CardType.DEBIT,
            panEncrypted = cipher.encrypt("4111111111111111"),
            cvvEncrypted = cipher.encrypt("123"),
        )
        coEvery { repo.findById(physical.id) } returns physical

        val ex = assertThrows<SecureDetailsForbiddenException> {
            runBlocking { service.readSecureDetails(ReadSecureDetailsQuery(physical.id, "ops-user")) }
        }
        assertThat(ex.code).isEqualTo(CardErrorCode.CARD_SECURE_DETAILS_NOT_SUPPORTED)
    }

    @Test fun `secure details are refused for a blocked virtual card`(): Unit = runBlocking {
        val blocked = card(status = CardStatus.BLOCKED).copy(
            cardType = CardType.SINGLE_USE,
            panEncrypted = cipher.encrypt("4111111111111111"),
            cvvEncrypted = cipher.encrypt("123"),
        )
        coEvery { repo.findById(blocked.id) } returns blocked

        val ex = assertThrows<SecureDetailsForbiddenException> {
            runBlocking { service.readSecureDetails(ReadSecureDetailsQuery(blocked.id, "ops-user")) }
        }
        assertThat(ex.code).isEqualTo(CardErrorCode.CARD_SECURE_DETAILS_CARD_NOT_LIVE)
    }

    @Test fun `secure details are refused for a pre-migration card with no stored PAN`(): Unit = runBlocking {
        val legacy = card(status = CardStatus.ACTIVE).copy(cardType = CardType.VIRTUAL)
        coEvery { repo.findById(legacy.id) } returns legacy

        val ex = assertThrows<SecureDetailsNotStoredException> {
            runBlocking { service.readSecureDetails(ReadSecureDetailsQuery(legacy.id, "ops-user")) }
        }
        assertThat(ex.code).isEqualTo(CardErrorCode.CARD_SECURE_DETAILS_NOT_STORED)
    }

    // ── product-catalog entitlements (#4) ──────────────────────────────────────────────

    @Test fun `issue is rejected when the party is at the product card quota`(): Unit = runBlocking {
        val command = issueCmd("idem-quota")
        coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { catalog.findCardConfig("CARD-001") } returns CardConfigLookup.Found(cardConfig(maxCards = 2))
        coEvery { repo.findByPartyId(command.partyId) } returns listOf(
            card(status = CardStatus.ACTIVE),
            card(status = CardStatus.SUSPENDED),
        )

        val ex = assertThrows<CardEntitlementException> { runBlocking { service.issueCard(command) } }
        assertThat(ex.code).isEqualTo(CardErrorCode.CARD_QUOTA_EXCEEDED)

        coVerify(exactly = 0) { repo.save(any(), any()) }
    }

    @Test fun `dead cards do not consume the product card quota`(): Unit = runBlocking {
        val command = issueCmd("idem-dead")
        coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { catalog.findCardConfig("CARD-001") } returns CardConfigLookup.Found(cardConfig(maxCards = 2))
        coEvery { repo.findByPartyId(command.partyId) } returns listOf(
            card(status = CardStatus.CANCELLED),
            card(status = CardStatus.BLOCKED),
            card(status = CardStatus.EXPIRED),
        )
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        assertThat(service.issueCard(command).status).isEqualTo(CardStatus.PENDING)
    }

    @Test fun `issue is rejected when the product does not carry cards`(): Unit = runBlocking {
        val command = issueCmd("idem-disabled")
        coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { catalog.findCardConfig("CARD-001") } returns CardConfigLookup.Found(cardConfig(enabled = false))

        val ex = assertThrows<CardEntitlementException> { runBlocking { service.issueCard(command) } }
        assertThat(ex.code).isEqualTo(CardErrorCode.CARD_PRODUCT_DISABLED)
    }

    @Test fun `issue is rejected when the network is not allowed by the product`(): Unit = runBlocking {
        val command = issueCmd("idem-network")
        coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { catalog.findCardConfig("CARD-001") } returns
            CardConfigLookup.Found(cardConfig(networks = listOf(CardNetwork.MASTERCARD)))

        val ex = assertThrows<CardEntitlementException> { runBlocking { service.issueCard(command) } }
        assertThat(ex.code).isEqualTo(CardErrorCode.CARD_NETWORK_NOT_ALLOWED)
    }

    @Test fun `issue is rejected when a single-use card is requested on a product that forbids virtual`(): Unit =
        runBlocking {
            val command = issueCmd("idem-virtual").copy(cardType = CardType.SINGLE_USE)
            coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
            coEvery { catalog.findCardConfig("CARD-001") } returns
                CardConfigLookup.Found(cardConfig(virtualCardAllowed = false))

            val ex = assertThrows<CardEntitlementException> { runBlocking { service.issueCard(command) } }
            assertThat(ex.code).isEqualTo(CardErrorCode.CARD_VIRTUAL_NOT_ALLOWED)
        }

    // The whole point of the fail-open posture: product-catalog is KEDA scale-to-zero, so an
    // unreachable catalog must never take card issuance down (CardProductCatalogPort).
    @Test fun `issue proceeds when product-catalog is unavailable`(): Unit = runBlocking {
        val command = issueCmd("idem-failopen")
        coEvery { repo.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { catalog.findCardConfig("CARD-001") } returns CardConfigLookup.Unavailable
        coEvery { repo.save(any(), any()) } answers { firstArg() }

        assertThat(service.issueCard(command).status).isEqualTo(CardStatus.PENDING)
        // Never even asked how many cards the party holds — there is no cap to compare against.
        coVerify(exactly = 0) { repo.findByPartyId(any()) }
    }

    @Test fun `entitlements report the catalog quota minus live cards`(): Unit = runBlocking {
        val partyId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        coEvery { catalog.findCardConfig("CARD-001") } returns CardConfigLookup.Found(cardConfig(maxCards = 3))
        coEvery { repo.findByPartyId(partyId) } returns listOf(
            card(status = CardStatus.ACTIVE),
            card(status = CardStatus.CANCELLED),
        )

        val entitlements = service.getEntitlements(partyId, "CARD-001")

        assertThat(entitlements.source).isEqualTo(EntitlementSource.CATALOG)
        assertThat(entitlements.issued).isEqualTo(1)
        assertThat(entitlements.remaining).isEqualTo(2)
        assertThat(entitlements.singleUseAllowed).isTrue()
    }

    @Test fun `entitlements fall back to an unknown cap when the catalog is unavailable`(): Unit = runBlocking {
        val partyId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        // The catalog does not answer for this code — but the party's live cards are still counted.
        coEvery { catalog.findCardConfig("CARD-001") } returns CardConfigLookup.Unavailable
        coEvery { repo.findByPartyId(partyId) } returns listOf(card(status = CardStatus.ACTIVE))

        val entitlements = service.getEntitlements(partyId, "CARD-001")

        assertThat(entitlements.productCode).isEqualTo("CARD-001")
        assertThat(entitlements.source).isEqualTo(EntitlementSource.FALLBACK)
        // -1 means "no known cap", NOT "nothing left".
        assertThat(entitlements.maxCards).isEqualTo(-1)
        assertThat(entitlements.remaining).isEqualTo(-1)
        assertThat(entitlements.issued).isEqualTo(1)
    }

    private fun cardConfig(
        enabled: Boolean = true,
        maxCards: Int = 3,
        networks: List<CardNetwork> = listOf(CardNetwork.VISA, CardNetwork.MASTERCARD),
        virtualCardAllowed: Boolean = true,
    ) = CardProductConfig(
        enabled = enabled,
        maxCards = maxCards,
        networks = networks,
        tiers = listOf("STANDARD"),
        virtualCardAllowed = virtualCardAllowed,
        contactlessEnabled = true,
        monthlyFeePerCard = 0.0,
        cardCurrency = "EUR",
    )

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
