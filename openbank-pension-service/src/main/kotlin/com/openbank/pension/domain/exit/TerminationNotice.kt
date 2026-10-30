// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.exit

import com.openbank.libs.domain.identifiers.Ids
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/**
 * Early-termination notice lifecycle. QUOTED is the binding preview; the participant signs exactly
 * that quote (SCA dynamic linking over [TerminationNotice.quoteHash]) and from then on no amount
 * is ever recomputed — what is paid is what was shown.
 */
enum class TerminationStatus {
    QUOTED,
    SIGNED,
    REDEEMED,
    PAID,
    COMPLETED,
    EXPIRED,

    /** A death claim was registered during the notice period; the claim settles the contract. */
    SUPERSEDED,
    ;

    fun canMoveTo(target: TerminationStatus): Boolean = target in EDGES.getValue(this)

    private companion object {
        val EDGES: Map<TerminationStatus, Set<TerminationStatus>> = mapOf(
            QUOTED to setOf(SIGNED, EXPIRED),
            SIGNED to setOf(REDEEMED, SUPERSEDED),
            REDEEMED to setOf(PAID),
            PAID to setOf(COMPLETED),
            COMPLETED to emptySet(),
            EXPIRED to emptySet(),
            SUPERSEDED to emptySet(),
        )
    }
}

data class TerminationNotice(
    val id: UUID,
    val contractId: UUID,
    val participantPartyId: UUID,
    val status: TerminationStatus,
    val quote: TerminationQuote,
    val quotedAt: Instant,
    val quoteExpiresAt: Instant,
    val payoutIban: String? = null,
    val scaChallengeId: String? = null,
    val signedAt: Instant? = null,
    /** The day the notice period ends and the payout becomes due. */
    val effectiveDate: LocalDate? = null,
    val idempotencyKey: String? = null,
    /** Proceeds the fund actually delivered; any difference to the quote is the provider's, never the client's. */
    val redeemedAmount: BigDecimal? = null,
    val paymentRef: String? = null,
    val updatedAt: Instant,
    /** Optimistic-lock version of the stored row (set on load, checked on save; ADR-0334 S8). */
    val version: Int = 0,
) {
    /** SHA-256 of the quote the participant signs; bound into the SCA challenge (RTS Art. 5). */
    val quoteHash: String get() = sha256("$id|$contractId|${quote.canonical()}|$quoteExpiresAt")

    /**
     * What the SCA challenge signs (ADR-0334 S8): the quote AND the destination account. Execution
     * pays only the account stored with this signature, so the account cannot be swapped after the
     * participant approved the amount.
     */
    fun signingHash(iban: String): String = sha256("$quoteHash|payout-account|$iban")

    /** Fund proceeds minus the binding quote: positive = provider gain, negative = provider loss. */
    val navVariance: BigDecimal? get() = redeemedAmount?.subtract(quote.redemptionValue)

    fun sign(
        iban: String,
        scaChallengeId: String,
        idempotencyKey: String,
        noticePeriodDays: Int,
        now: Instant,
        today: LocalDate,
    ): TerminationNotice {
        check(now.isBefore(quoteExpiresAt)) { "the termination quote expired at $quoteExpiresAt; request a new one" }
        return moveTo(TerminationStatus.SIGNED, now).copy(
            payoutIban = iban,
            scaChallengeId = scaChallengeId,
            signedAt = now,
            effectiveDate = today.plusDays(noticePeriodDays.toLong()),
            idempotencyKey = idempotencyKey,
        )
    }

    fun markRedeemed(proceeds: BigDecimal, now: Instant) =
        moveTo(TerminationStatus.REDEEMED, now).copy(redeemedAmount = ExitMoney.round(proceeds))

    fun markPaid(ref: String, now: Instant) = moveTo(TerminationStatus.PAID, now).copy(paymentRef = ref)

    fun complete(now: Instant) = moveTo(TerminationStatus.COMPLETED, now)

    fun expire(now: Instant) = moveTo(TerminationStatus.EXPIRED, now)

    fun supersede(now: Instant) = moveTo(TerminationStatus.SUPERSEDED, now)

    private fun moveTo(target: TerminationStatus, now: Instant): TerminationNotice {
        check(status.canMoveTo(target)) { "termination notice $status -> $target is not allowed" }
        return copy(status = target, updatedAt = now)
    }

    companion object {
        @Suppress("LongParameterList")
        fun quote(
            contractId: UUID,
            participantPartyId: UUID,
            quote: TerminationQuote,
            validityDays: Int,
            now: Instant,
        ) = TerminationNotice(
            id = Ids.newId(),
            contractId = contractId,
            participantPartyId = participantPartyId,
            status = TerminationStatus.QUOTED,
            quote = quote,
            quotedAt = now,
            quoteExpiresAt = now.plus(Duration.ofDays(validityDays.toLong())),
            updatedAt = now,
        )
    }
}

internal fun sha256(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
