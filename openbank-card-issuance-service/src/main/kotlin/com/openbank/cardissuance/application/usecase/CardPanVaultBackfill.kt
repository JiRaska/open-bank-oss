// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.usecase

import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.cardissuance.application.port.out.CardSecretCipher
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.SyntheticPanGenerator
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/** Outcome of one backfill pass. Counts only — never a card number. */
data class CardPanBackfillResult(val backfilled: Int, val skipped: Int) {
    val isEmpty: Boolean get() = backfilled == 0 && skipped == 0
}

/**
 * Fills the synthetic-PAN vault for cards issued **before** it existed (ADR-0194 follow-up).
 *
 * Those cards have `pan_encrypted IS NULL`, so `readSecureDetails` refuses them with
 * `CARD_SECURE_DETAILS_NOT_STORED` — correctly, but permanently: in the app the customer taps
 * "Detaily", authenticates, and gets an error with no way out, forever. Nothing else in the system
 * can ever repair those rows, because a credential is only minted at issue time.
 *
 * **The generated PAN keeps the card's existing last 4 digits.** `maskedPan` is already displayed
 * (`**** **** **** 3901`) and the customer has read it off the screen; minting an unrelated number
 * would silently change the card's identity under them, which is worse than the broken button. Only
 * the hidden middle digits are new. When no Luhn-valid PAN can be built for a given last-4 (a mask
 * that is not four digits — the pre-vault masks were random, so this is possible), the card is
 * **skipped**: an unreadable "Detaily" is recoverable, a renumbered card is not.
 *
 * Idempotent by construction: it only ever considers rows where the column is NULL, and the write
 * itself re-checks that predicate ([CardRepository.storePanCredentialIfAbsent]). A second pass — a
 * restart, a second replica — finds nothing and does nothing.
 */
@ApplicationScoped
class CardPanVaultBackfill(private val repo: CardRepository, private val cipher: CardSecretCipher) {

    /**
     * Mint and store a credential for every non-terminal card missing one. Returns the counts;
     * logs one summary line. **No PAN or CVV is ever logged**, here or in the failure path.
     */
    suspend fun run(): CardPanBackfillResult {
        val candidates = repo.findWithoutPanCredential()
        if (candidates.isEmpty()) return CardPanBackfillResult(backfilled = 0, skipped = 0)

        var backfilled = 0
        var skipped = 0
        for (card in candidates) {
            val credential = SyntheticPanGenerator.generate(card.network, last4Of(card))
            if (credential == null) {
                log.warnf(
                    "[pan-vault-backfill] skipped card %s: its masked PAN carries no usable last 4 " +
                        "digits, and renumbering a card the customer has already seen is not an option",
                    card.id,
                )
                skipped++
                continue
            }
            val written = repo.storePanCredentialIfAbsent(
                card.id,
                cipher.encrypt(credential.pan),
                cipher.encrypt(credential.cvv),
            )
            if (written) backfilled++ else skipped++
        }
        log.infof(
            "[pan-vault-backfill] %d card(s) had no stored credential: %d backfilled, %d skipped",
            candidates.size,
            backfilled,
            skipped,
        )
        return CardPanBackfillResult(backfilled = backfilled, skipped = skipped)
    }

    /** The four digits the customer already sees. Anything else (a shorter or non-numeric tail) is refused upstream. */
    private fun last4Of(card: Card): String = card.maskedPan.takeLast(LAST4_LENGTH)

    private companion object {
        val log: Logger = Logger.getLogger(CardPanVaultBackfill::class.java)
        const val LAST4_LENGTH = 4
    }
}
