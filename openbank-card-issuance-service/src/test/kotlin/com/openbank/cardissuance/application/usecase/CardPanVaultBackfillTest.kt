// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.usecase

import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.CardNetwork
import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import com.openbank.cardissuance.domain.model.SyntheticPanGenerator
import com.openbank.cardissuance.infrastructure.crypto.AesGcmCardSecretCipher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.Optional
import java.util.UUID

/**
 * The vault backfill (ADR-0194 follow-up): cards issued before `pan_encrypted` existed can never
 * serve "Detaily" — and nothing else in the system repairs them.
 */
class CardPanVaultBackfillTest {

    private lateinit var repo: CardRepository
    private lateinit var backfill: CardPanVaultBackfill

    // A real cipher, as in CardServiceTest: the round-trip is part of the behaviour under test.
    // TEST ONLY key, built in code rather than pasted as a base64 literal (the repo's secret scan
    // cannot tell a "only a test key" blob from a real one, and should not have to).
    private val cipher = AesGcmCardSecretCipher(
        Optional.of(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })),
        false,
    )

    @BeforeEach
    fun setUp() {
        repo = mockk()
        backfill = CardPanVaultBackfill(repo, cipher)
    }

    @Test fun `fills the vault for a card that has none, keeping its displayed last 4`(): Unit = runBlocking {
        val legacy = card(maskedPan = "**** **** **** 3901")
        coEvery { repo.findWithoutPanCredential() } returns listOf(legacy)
        val pan = slot<String>()
        val cvv = slot<String>()
        coEvery { repo.storePanCredentialIfAbsent(legacy.id, capture(pan), capture(cvv)) } returns true

        val result = backfill.run()

        assertThat(result).isEqualTo(CardPanBackfillResult(backfilled = 1, skipped = 0))
        val stored = cipher.decrypt(pan.captured)
        // The customer has already read "3901" off the screen — the minted PAN must not renumber
        // the card under them.
        assertThat(stored).endsWith("3901")
        assertThat(stored).startsWith("411111")
        assertThat(SyntheticPanGenerator.isLuhnValid(stored)).isTrue()
        assertThat(cipher.decrypt(cvv.captured)).hasSize(3).containsOnlyDigits()
        // Ciphertext, not the clear value.
        assertThat(pan.captured).isNotEqualTo(stored)
    }

    @Test fun `the card's maskedPan still matches the PAN that was minted for it`(): Unit = runBlocking {
        val legacy = card(maskedPan = "**** **** **** 0007", network = CardNetwork.AMEX)
        coEvery { repo.findWithoutPanCredential() } returns listOf(legacy)
        val pan = slot<String>()
        coEvery { repo.storePanCredentialIfAbsent(legacy.id, capture(pan), any()) } returns true

        backfill.run()

        assertThat(SyntheticPanGenerator.mask(cipher.decrypt(pan.captured))).isEqualTo(legacy.maskedPan)
    }

    // Terminal cards are excluded by the query itself (CardRepository.findWithoutPanCredential),
    // so the backfill must not need to re-filter them — and must not touch one if it sees it.
    @Test fun `a terminal card is never a candidate`(): Unit = runBlocking {
        coEvery { repo.findWithoutPanCredential() } returns emptyList()

        val result = backfill.run()

        assertThat(result.isEmpty).isTrue()
        coVerify(exactly = 0) { repo.storePanCredentialIfAbsent(any(), any(), any()) }
        assertThat(Card.TERMINAL_STATUSES).containsExactlyInAnyOrder(CardStatus.CANCELLED, CardStatus.EXPIRED)
    }

    // Idempotence has two layers: the query only returns NULL rows, and the write re-checks the
    // predicate. This is the second layer — a row that gained a credential in between is skipped,
    // never overwritten.
    @Test fun `a card that gained a credential concurrently is skipped, not renumbered`(): Unit = runBlocking {
        val legacy = card(maskedPan = "**** **** **** 3901")
        coEvery { repo.findWithoutPanCredential() } returns listOf(legacy)
        coEvery { repo.storePanCredentialIfAbsent(legacy.id, any(), any()) } returns false

        val result = backfill.run()

        assertThat(result).isEqualTo(CardPanBackfillResult(backfilled = 0, skipped = 1))
    }

    @Test fun `a second run is a no-op once every card has a credential`(): Unit = runBlocking {
        val legacy = card(maskedPan = "**** **** **** 3901")
        coEvery { repo.findWithoutPanCredential() } returnsMany listOf(listOf(legacy), emptyList())
        coEvery { repo.storePanCredentialIfAbsent(legacy.id, any(), any()) } returns true

        assertThat(backfill.run().backfilled).isEqualTo(1)
        assertThat(backfill.run()).isEqualTo(CardPanBackfillResult(backfilled = 0, skipped = 0))

        coVerify(exactly = 1) { repo.storePanCredentialIfAbsent(legacy.id, any(), any()) }
    }

    // The pre-vault masks were random, and some are not four digits. Refusing beats renumbering.
    @Test fun `a card whose mask carries no usable last 4 is skipped, not renumbered`(): Unit = runBlocking {
        val unusable = card(maskedPan = "**** **** **** ****")
        coEvery { repo.findWithoutPanCredential() } returns listOf(unusable)

        val result = backfill.run()

        assertThat(result).isEqualTo(CardPanBackfillResult(backfilled = 0, skipped = 1))
        coVerify(exactly = 0) { repo.storePanCredentialIfAbsent(any(), any(), any()) }
    }

    @Test fun `a mixed batch reports both counts`(): Unit = runBlocking {
        val good = card(maskedPan = "**** **** **** 1234")
        val unusable = card(
            maskedPan = "**** **** **** ab12",
            id = UUID.fromString("44444444-4444-4444-4444-444444444444"),
        )
        coEvery { repo.findWithoutPanCredential() } returns listOf(good, unusable)
        coEvery { repo.storePanCredentialIfAbsent(good.id, any(), any()) } returns true

        assertThat(backfill.run()).isEqualTo(CardPanBackfillResult(backfilled = 1, skipped = 1))
    }

    private fun card(
        maskedPan: String,
        network: CardNetwork = CardNetwork.VISA,
        id: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111"),
    ) = Card(
        id = id,
        idempotencyKey = "idem-$id-$maskedPan",
        partyId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        accountId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        productCode = "CARD-001",
        cardType = CardType.VIRTUAL,
        network = network,
        maskedPan = maskedPan,
        cardholderName = "Jane Doe",
        embossedName = "JANE DOE",
        expiryDate = LocalDate.of(2030, 12, 31),
        status = CardStatus.ACTIVE,
        dailyLimitMinorUnits = 100_00,
        monthlyLimitMinorUnits = 1_000_00,
        currency = "EUR",
        deliveryAddress = null,
        activatedAt = null,
        blockedAt = null,
        blockedReason = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
