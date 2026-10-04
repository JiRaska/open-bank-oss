// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import com.openbank.cardprocessing.application.port.out.LedgerPostingPort
import com.openbank.cardprocessing.application.port.out.PostingOutcome
import com.openbank.cardprocessing.application.port.out.PostingResult
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.libs.domain.calendar.AccountingClock
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.Currency
import java.util.UUID

/**
 * Posts a cleared presentment as a `CARD`-rail debit through transaction-service.
 *
 * ## The conversion happens exactly here
 *
 * Card amounts are minor units end to end; the ledger works in the currency's own scale. The
 * conversion is one line, at the boundary, using the **currency's** fraction digits rather than a
 * hardcoded 2 — JPY has none, and dividing a yen amount by 100 would post a hundredth of the money,
 * silently and plausibly.
 *
 * ## Why the outcome is an enum
 *
 * [PostingOutcome.SKIPPED_DISABLED] exists so an unbound or switched-off adapter can never be
 * counted as a successful posting. That is not hypothetical: the push-notification fan-out returned
 * `success = true` for a skipped send, committed the row as SENT and announced a delivery that never
 * left the process (ADR-0252 phase 0, #4348).
 */
@ApplicationScoped
class TransactionLedgerPostingAdapter(
    @RestClient private val client: TransactionServiceClient,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.card-processing.ledger-posting-enabled", defaultValue = "true")
    private val postingEnabled: Boolean,
) : LedgerPostingPort {

    private val log = Logger.getLogger(TransactionLedgerPostingAdapter::class.java)

    override suspend fun postClearedSpend(
        authorization: CardAuthorization,
        clearedAmountMinorUnits: Long,
        idempotencyKey: String,
    ): PostingResult {
        if (!postingEnabled) {
            // Loud, and its own outcome value. A quiet skip is the failure mode the enum exists for.
            log.warnf(
                "ledger posting DISABLED — card spend %d %s on authorization %s is NOT in the books",
                clearedAmountMinorUnits,
                authorization.currencyCode,
                authorization.id,
            )
            return PostingResult(PostingOutcome.SKIPPED_DISABLED, null, DISABLED_DETAIL)
        }
        return try {
            val response = client.initiate(
                InitiateTransactionRequest(
                    // Derived from the clearing, not random: transaction-service dedupes on it, so
                    // a retried clearing must present the same key or the customer is debited twice.
                    idempotencyKey = CardClearingLedgerKey.of(authorization.id, idempotencyKey),
                    type = TYPE_DEBIT,
                    sourceAccountId = authorization.accountId,
                    targetAccountId = null,
                    amount = toMajorUnits(clearedAmountMinorUnits, authorization.currencyCode),
                    currencyCode = authorization.currencyCode,
                    description = authorization.merchantName,
                    valueDate = AccountingClock(clock).today().toString(),
                    rail = RAIL_CARD,
                    instructionType = INSTRUCTION_ONE_OFF,
                ),
            )
            PostingResult(PostingOutcome.POSTED, response.id, response.status)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: this runs AFTER the clearing has committed, so no exception from
            // here may propagate and undo a fact the acquirer has already asserted. The FAILED
            // outcome is what makes the gap visible instead of swallowed.
            log.errorf(e, "ledger posting failed for authorization %s", authorization.id)
            PostingResult(PostingOutcome.FAILED, null, e.message)
        }
    }

    private fun toMajorUnits(minorUnits: Long, currencyCode: String): BigDecimal {
        val digits = runCatching { Currency.getInstance(currencyCode.uppercase()).defaultFractionDigits }
            .getOrDefault(DEFAULT_FRACTION_DIGITS)
            .coerceAtLeast(0)
        return BigDecimal.valueOf(minorUnits).movePointLeft(digits).setScale(digits, RoundingMode.UNNECESSARY)
    }

    private companion object {
        const val TYPE_DEBIT = "DEBIT"
        const val RAIL_CARD = "CARD"
        const val INSTRUCTION_ONE_OFF = "ONE_OFF"
        const val DISABLED_DETAIL = "openbank.card-processing.ledger-posting-enabled=false"

        /** Only reached for a code `Currency` does not know; two digits is the majority shape. */
        const val DEFAULT_FRACTION_DIGITS = 2
    }
}

/** The ledger idempotency key a clearing presents to transaction-service. */
object CardClearingLedgerKey {
    /** transaction-service's `transactions.idempotency_key` is VARCHAR(100). */
    const val MAX_LENGTH = 100

    /** `card-clearing:` (14) + authorisation UUID (36) + `:h:` (3) + base64url SHA-256 (43). */
    const val LENGTH = 96

    /**
     * `card-clearing:<authorizationId>:h:<base64url(SHA-256(clearingKey))>` — ALWAYS hashed.
     *
     * - Scoped by the authorisation: a clearing key is only unique per authorisation
     *   (`card_clearings` UNIQUE (authorization_id, idempotency_key)), and the unscoped
     *   `card-clearing:<key>` let two authorisations sharing a clearing key collide in
     *   transaction-service, which deduplicated the second posting away.
     * - Always hashed, never "verbatim when it fits": a mixed form shares one namespace between
     *   raw keys and digests, so a client could choose a clearing key that spells another key's
     *   digest and collide with it. One encoding for every key has no such overlap.
     * - The FULL 256-bit digest, base64url without padding (43 chars), so the key is fixed at 96
     *   characters and fits transaction-service's 100-character column; hex (64) would not.
     * - Deterministic, so a re-driven posting presents the same key.
     */
    fun of(authorizationId: UUID, clearingKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(clearingKey.toByteArray(Charsets.UTF_8))
        val key = "card-clearing:$authorizationId:h:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        check(key.length == LENGTH && key.length <= MAX_LENGTH) { "ledger key length ${key.length}" }
        return key
    }
}
