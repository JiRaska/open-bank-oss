// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.usecase

import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.cardissuance.application.port.out.CardSecretCipher
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.CardNetwork
import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import com.openbank.cardissuance.infrastructure.crypto.AesGcmCardSecretCipher
import com.openbank.cardissuance.infrastructure.crypto.OpenBaoTransitDekUnwrapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.Optional
import java.util.UUID

private const val WRAPPED_OLD_DEK = "vault:v1:old-not-a-real-wrapped-value"

/**
 * The re-encrypt batch job (ADR-0262 follow-up): migrates rows off a rotated-out DEK, the same
 * way [CardPanVaultBackfillTest] exercises the sibling backfill.
 */
class CardPanKeyReencryptTest {

    private lateinit var repo: CardRepository
    private lateinit var unwrapper: OpenBaoTransitDekUnwrapper
    private lateinit var reencrypt: CardPanKeyReencrypt

    // Real ciphers, as in CardPanVaultBackfillTest: the round-trip (and the deliberate MISMATCH
    // between them) is part of the behaviour under test. TEST ONLY keys, built in code rather than
    // pasted as base64 literals.
    private val currentCipher: CardSecretCipher =
        AesGcmCardSecretCipher(Optional.of(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })), false)
    private val oldDekBytes = ByteArray(32) { (it + 1).toByte() }
    private val oldCipher = AesGcmCardSecretCipher(Optional.of(Base64.getEncoder().encodeToString(oldDekBytes)), false)

    @BeforeEach
    fun setUp() {
        repo = mockk()
        unwrapper = mockk()
        every { unwrapper.unwrap(WRAPPED_OLD_DEK) } returns oldDekBytes
        reencrypt = CardPanKeyReencrypt(repo, currentCipher, unwrapper)
    }

    @Test fun `migrates a row encrypted under the old DEK to the current one`(): Unit = runBlocking {
        val oldPan = oldCipher.encrypt("4111111234567893")
        val oldCvv = oldCipher.encrypt("123")
        val card = card(panEncrypted = oldPan, cvvEncrypted = oldCvv)
        coEvery { repo.findWithPanCredential() } returns listOf(card)
        val newPan = slot<String>()
        val newCvv = slot<String>()
        coEvery {
            repo.updatePanCredentialIfMatches(card.id, oldPan, capture(newPan), capture(newCvv))
        } returns true

        val result = reencrypt.run(WRAPPED_OLD_DEK)

        assertThat(
            result,
        ).isEqualTo(
            CardPanReencryptResult(migrated = 1, alreadyCurrent = 0, skippedConcurrentWrite = 0, unmigrable = 0),
        )
        assertThat(currentCipher.decrypt(newPan.captured)).isEqualTo("4111111234567893")
        assertThat(currentCipher.decrypt(newCvv.captured)).isEqualTo("123")
    }

    @Test fun `a row already on the current DEK is left untouched`(): Unit = runBlocking {
        val card =
            card(panEncrypted = currentCipher.encrypt("4111111234567893"), cvvEncrypted = currentCipher.encrypt("123"))
        coEvery { repo.findWithPanCredential() } returns listOf(card)

        val result = reencrypt.run(WRAPPED_OLD_DEK)

        assertThat(
            result,
        ).isEqualTo(
            CardPanReencryptResult(migrated = 0, alreadyCurrent = 1, skippedConcurrentWrite = 0, unmigrable = 0),
        )
        coVerify(exactly = 0) { repo.updatePanCredentialIfMatches(any(), any(), any(), any()) }
        // The fast path never needed the old cipher at all — confirms "try current first" is
        // actually cheap (no OpenBao call), not just documented as such.
        verify(exactly = 0) { unwrapper.unwrap(any()) }
    }

    @Test fun `a row under neither key is left untouched and counted unmigrable`(): Unit = runBlocking {
        val thirdKeyBytes = ByteArray(32) { (it + 2).toByte() }
        val thirdCipher = AesGcmCardSecretCipher(Optional.of(Base64.getEncoder().encodeToString(thirdKeyBytes)), false)
        val card =
            card(panEncrypted = thirdCipher.encrypt("4111111234567893"), cvvEncrypted = thirdCipher.encrypt("123"))
        coEvery { repo.findWithPanCredential() } returns listOf(card)

        val result = reencrypt.run(WRAPPED_OLD_DEK)

        assertThat(
            result,
        ).isEqualTo(
            CardPanReencryptResult(migrated = 0, alreadyCurrent = 0, skippedConcurrentWrite = 0, unmigrable = 1),
        )
        coVerify(exactly = 0) { repo.updatePanCredentialIfMatches(any(), any(), any(), any()) }
    }

    // Idempotence's second layer, same shape as the backfill: the CAS write loses to a row that
    // changed between the read and the write, and that is a SKIP, never a clobber.
    @Test fun `a row that changed concurrently is skipped, not overwritten`(): Unit = runBlocking {
        val oldPan = oldCipher.encrypt("4111111234567893")
        val card = card(panEncrypted = oldPan, cvvEncrypted = oldCipher.encrypt("123"))
        coEvery { repo.findWithPanCredential() } returns listOf(card)
        coEvery { repo.updatePanCredentialIfMatches(card.id, oldPan, any(), any()) } returns false

        val result = reencrypt.run(WRAPPED_OLD_DEK)

        assertThat(
            result,
        ).isEqualTo(
            CardPanReencryptResult(migrated = 0, alreadyCurrent = 0, skippedConcurrentWrite = 1, unmigrable = 0),
        )
    }

    @Test fun `a second pass is a no-op once every row has migrated`(): Unit = runBlocking {
        val oldPan = oldCipher.encrypt("4111111234567893")
        val card = card(panEncrypted = oldPan, cvvEncrypted = oldCipher.encrypt("123"))
        var stored = card
        coEvery { repo.findWithPanCredential() } answers { listOf(stored) }
        coEvery { repo.updatePanCredentialIfMatches(card.id, oldPan, any(), any()) } answers {
            stored = stored.copy(panEncrypted = thirdArg(), cvvEncrypted = arg(3))
            true
        }

        assertThat(reencrypt.run(WRAPPED_OLD_DEK).migrated).isEqualTo(1)
        assertThat(reencrypt.run(WRAPPED_OLD_DEK)).isEqualTo(
            CardPanReencryptResult(migrated = 0, alreadyCurrent = 1, skippedConcurrentWrite = 0, unmigrable = 0),
        )
    }

    @Test fun `no candidates is an empty, not-unmigrable result`(): Unit = runBlocking {
        coEvery { repo.findWithPanCredential() } returns emptyList()

        val result = reencrypt.run(WRAPPED_OLD_DEK)

        assertThat(result.isEmpty).isTrue()
    }

    private fun card(
        panEncrypted: String,
        cvvEncrypted: String,
        id: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111"),
    ) = Card(
        id = id,
        idempotencyKey = "idem-$id",
        partyId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        accountId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        productCode = "CARD-001",
        cardType = CardType.VIRTUAL,
        network = CardNetwork.VISA,
        maskedPan = "**** **** **** 7893",
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
        panEncrypted = panEncrypted,
        cvvEncrypted = cvvEncrypted,
    )
}
