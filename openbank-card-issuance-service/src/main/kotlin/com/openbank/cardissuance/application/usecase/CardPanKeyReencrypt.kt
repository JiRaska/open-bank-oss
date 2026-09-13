// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.usecase

import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.cardissuance.application.port.out.CardSecretCipher
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.infrastructure.crypto.AesGcmCardSecretCipher
import com.openbank.cardissuance.infrastructure.crypto.OpenBaoTransitDekUnwrapper
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.Base64
import java.util.Optional

/** Outcome of one re-encrypt pass. Counts only — never a card number, never a ciphertext. */
data class CardPanReencryptResult(
    val migrated: Int,
    val alreadyCurrent: Int,
    val skippedConcurrentWrite: Int,
    val unmigrable: Int,
) {
    val isEmpty: Boolean get() = migrated == 0 && alreadyCurrent == 0 && skippedConcurrentWrite == 0 && unmigrable == 0
}

/**
 * Re-encrypts every stored PAN/CVV from a PREVIOUS OpenBao Transit DEK to the CURRENT one
 * (ADR-0262 follow-up, the batch job that ADR's Decision section calls out as separate work).
 *
 * A KEK rotation (`vault write -f transit/keys/<kek-name>/rotate`) does not, by itself, touch a
 * single row: `OpenBaoEnvelopeCardSecretCipher` keeps decrypting existing rows fine because Transit
 * retains prior key versions, and every NEW row is encrypted under the new DEK from the moment the
 * operator switches `openbank.card.envelope.wrapped-dek` to the freshly-wrapped one. What is left
 * outstanding after that switch is every row written BEFORE it — this job is how those catch up, so
 * the old DEK can eventually be retired from Transit for good.
 *
 * **No row metadata says which DEK encrypted it** — the wire format
 * (`base64(IV ‖ ciphertext ‖ tag)`) carries no key identifier (see `AesGcmCardSecretCipher`'s own
 * doc on why: the whole point of envelope encryption is that Transit's OWN versioned ciphertext is
 * the source of truth for that, not a scheme this service reinvents). So this job discovers a row's
 * key the only way possible: try the CURRENT cipher first (cheap, no OpenBao call — already
 * migrated rows are the common case after the first pass), and only on failure try the OLD one
 * (unwrapped once via [unwrapper], not per row).
 *
 * Idempotent by construction, the same shape as [CardPanVaultBackfill]: a row that decrypts under
 * the current cipher needs no action, and the write itself is a compare-and-swap on the ciphertext
 * it read ([CardRepository.updatePanCredentialIfMatches]) — a second pass, a second replica, or a
 * card that changed underneath the job all land as a no-op rather than a clobber.
 */
@ApplicationScoped
class CardPanKeyReencrypt(
    private val repo: CardRepository,
    private val currentCipher: CardSecretCipher,
    private val unwrapper: OpenBaoTransitDekUnwrapper,
) {

    /**
     * Unwraps [previousWrappedDek] once, then walks every card with a stored credential. Logs one
     * summary line. **No PAN, CVV, or ciphertext is ever logged**, here or in the failure path —
     * only the reason a row could not be migrated, by card id.
     */
    suspend fun run(previousWrappedDek: String): CardPanReencryptResult {
        // Lazy: unwrapping is the one OpenBao call this job makes, and it must happen at most
        // once — never per row — but also NOT at all if every row turns out to already be on the
        // current DEK (a re-run after a completed migration is exactly that case).
        val oldCipher = lazy {
            AesGcmCardSecretCipher(
                Optional.of(Base64.getEncoder().encodeToString(unwrapper.unwrap(previousWrappedDek))),
                false,
            )
        }

        val candidates = repo.findWithPanCredential()
        var migrated = 0
        var alreadyCurrent = 0
        var skippedConcurrentWrite = 0
        var unmigrable = 0
        for (card in candidates) {
            when (migrateOne(card, oldCipher)) {
                Outcome.ALREADY_CURRENT -> alreadyCurrent++
                Outcome.MIGRATED -> migrated++
                Outcome.SKIPPED_CONCURRENT_WRITE -> skippedConcurrentWrite++
                Outcome.UNMIGRABLE -> unmigrable++
            }
        }
        log.infof(
            "[pan-key-reencrypt] %d card(s) with a stored credential: %d already on the current " +
                "DEK, %d migrated, %d skipped (changed concurrently, will retry next pass), " +
                "%d unmigrable (neither the current nor the given previous DEK decrypts them)",
            candidates.size,
            alreadyCurrent,
            migrated,
            skippedConcurrentWrite,
            unmigrable,
        )
        return CardPanReencryptResult(migrated, alreadyCurrent, skippedConcurrentWrite, unmigrable)
    }

    private suspend fun migrateOne(card: Card, oldCipher: Lazy<AesGcmCardSecretCipher>): Outcome {
        val panEncrypted = card.panEncrypted ?: return Outcome.UNMIGRABLE
        val cvvEncrypted = card.cvvEncrypted ?: return Outcome.UNMIGRABLE

        if (runCatching { currentCipher.decrypt(panEncrypted) }.isSuccess) return Outcome.ALREADY_CURRENT

        val oldPan = runCatching { oldCipher.value.decrypt(panEncrypted) }.getOrNull()
        val oldCvv = runCatching { oldCipher.value.decrypt(cvvEncrypted) }.getOrNull()
        if (oldPan == null || oldCvv == null) {
            log.warnf(
                "[pan-key-reencrypt] card %s: neither the current DEK nor the given previous one " +
                    "decrypts this row — leaving it untouched",
                card.id,
            )
            return Outcome.UNMIGRABLE
        }

        val written = repo.updatePanCredentialIfMatches(
            card.id,
            panEncrypted,
            currentCipher.encrypt(oldPan),
            currentCipher.encrypt(oldCvv),
        )
        return if (written) Outcome.MIGRATED else Outcome.SKIPPED_CONCURRENT_WRITE
    }

    private enum class Outcome { ALREADY_CURRENT, MIGRATED, SKIPPED_CONCURRENT_WRITE, UNMIGRABLE }

    private companion object {
        val log: Logger = Logger.getLogger(CardPanKeyReencrypt::class.java)
    }
}
