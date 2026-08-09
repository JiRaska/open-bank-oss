// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import java.time.OffsetDateTime
import java.util.UUID

enum class ScaMethod {
    PUSH_NOTIFICATION,
    TOTP,
    BIOMETRIC,
}

enum class ScaPurpose {
    CONSENT_GRANT,
    PAYMENT_INITIATION,
    LOGIN,
    AGENT_ACTION,
    SENSITIVE_DATA_ACCESS,

    /** Biometric approval of a specific e-signature act (ADR-0169 D2) — dynamic-linked to a
     * document, not a payment: [DynamicLinkingData.documentSha256]/[DynamicLinkingData.ceremonyId]
     * are populated instead of amount/currency/creditor. */
    DOCUMENT_SIGNING,

    /** Step-up approval of a card lifecycle/management act — dynamic-linked to a specific card
     * and action ([DynamicLinkingData.cardId]/[DynamicLinkingData.cardAction]) rather than to a
     * payment or a document. Raising a limit or revealing a virtual card's PAN/CVV is a
     * sensitive, card-scoped operation: the device-signed evidence must say "I authorised THIS
     * action on THIS card", never a bare "I completed some challenge". */
    CARD_MANAGEMENT,

    /** The grantor's half of an ADR-0232 D4 delegation ceremony: "I am handing this capability
     * over my product to another party". Carries no [DynamicLinkingData] today, so a consume
     * that states no operation is what authorises it — the binding that IS enforced is party +
     * purpose + single-use. Linking the challenge to the grant's content (resource, capability
     * set, ceilings) needs a delegation-shaped [DynamicLinkingData] and is tracked separately. */
    DELEGATION_GRANT,

    /** The grantee's half of the same ceremony — accepting an OFFERED grant. Separate from
     * [DELEGATION_GRANT] on purpose: a challenge completed by the grantor must never be
     * spendable as the grantee's acceptance, and purpose equality is what enforces that. */
    DELEGATION_ACCEPT,

    /** The account owner approving a delegate's propose-only savings withdrawal (ADR-0232 D8).
     * The delegate holds SAVINGS_PROPOSE_WITHDRAW and can never execute; this challenge IS the
     * owner's half of that maker-checker split, so it must be its own purpose — a challenge
     * raised for anything else must never be spendable as a withdrawal approval, and purpose
     * equality is what enforces that. Carries no [DynamicLinkingData] today, so a consume that
     * states no operation is what authorises it; binding it to the proposal's amount and
     * account needs a proposal-shaped [DynamicLinkingData] and is tracked separately. */
    SAVINGS_WITHDRAW_APPROVAL,
}

enum class ScaStatus {
    PENDING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED,
}

data class ScaChallenge(
    val id: UUID = UUID.randomUUID(),
    val partyId: UUID,
    val purpose: ScaPurpose,
    val method: ScaMethod,
    val status: ScaStatus = ScaStatus.PENDING,
    val expiresAt: OffsetDateTime,
    val completedAt: OffsetDateTime? = null,
    val failedAt: OffsetDateTime? = null,
    val failureReason: String? = null,
    val attemptCount: Int = 0,
    val maxAttempts: Int = 3,
    val dynamicLinkingData: DynamicLinkingData? = null,
    val redirectUrl: String? = null,
    /**
     * Set once the approved challenge has been spent on the operation it authorised
     * (single-use, RTS Art. 5 replay protection). Written atomically by the repository's
     * compare-and-consume update — never twice.
     */
    val consumedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime,
) {
    fun isExpired(now: OffsetDateTime): Boolean = now.isAfter(expiresAt)
    fun isCompleted(): Boolean = status == ScaStatus.COMPLETED
    fun canAttempt(now: OffsetDateTime): Boolean =
        attemptCount < maxAttempts && status == ScaStatus.PENDING && !isExpired(now)

    fun complete(now: OffsetDateTime): ScaChallenge = copy(
        status = ScaStatus.COMPLETED,
        completedAt = now,
    )

    fun fail(reason: String, now: OffsetDateTime): ScaChallenge = copy(
        status = if (attemptCount + 1 >= maxAttempts) ScaStatus.FAILED else ScaStatus.PENDING,
        attemptCount = attemptCount + 1,
        failedAt = if (attemptCount + 1 >= maxAttempts) now else null,
        failureReason = if (attemptCount + 1 >= maxAttempts) reason else null,
    )
}

// Three dynamic-linking shapes (payment / document / card) share one flat value object rather
// than a sealed hierarchy, so the signed-payload builder and `authorises` stay a single
// byte-exact code path. Splitting it would mean three payload builders — the one thing that must
// never diverge.
@Suppress("LongParameterList")
data class DynamicLinkingData(
    val amount: String?,
    val currency: String?,
    val creditorIban: String?,
    val creditorName: String?,
    val reference: String?,
    /** Content address (SHA-256) of the exact document the device asserted, for [ScaPurpose.DOCUMENT_SIGNING]. */
    val documentSha256: String? = null,
    /** The signature ceremony (document-service) this challenge is scoped to, for [ScaPurpose.DOCUMENT_SIGNING]. */
    val ceremonyId: String? = null,
    /** The card this challenge is bound to, for [ScaPurpose.CARD_MANAGEMENT]. */
    val cardId: String? = null,
    /**
     * The card operation being authorised, for [ScaPurpose.CARD_MANAGEMENT] — the caller's
     * vocabulary (`LIMIT_INCREASE`, `REVEAL_DETAILS`, `ISSUE`, `CANCEL`, ...). Deliberately a
     * free-form String, exactly like [ceremonyId]: sca-service does not own the card domain's
     * action vocabulary, and pinning it to a server-side enum would make every new card action a
     * lock-step deployment of two services. It is opaque here and compared byte-for-byte.
     */
    val cardAction: String? = null,
) {
    /**
     * Does this signed linking data authorise exactly the operation the caller is about to
     * execute? Amount compares numerically ("250.0" == "250.00"), currency case-insensitively,
     * and the creditor account ignoring spaces/case — what the DEVICE signed must equal what
     * the EDGE forwards, or the consume is refused (RTS Art. 5 dynamic linking).
     *
     * [documentSha256]/[ceremonyId] are compared the same way (exact match, [documentSha256]
     * case-insensitively since hex casing isn't semantically meaningful): a document-signing
     * challenge's evidence must be spent on the SAME document/ceremony it was raised for, never a
     * different one — this is what makes the biometric approval evidence "I authorised THIS
     * contract" rather than a bare "I completed some challenge" (ADR-0169 D2).
     *
     * [cardId]/[cardAction] follow the same rule for card management: the evidence must be spent
     * on the SAME card and the SAME action it was raised for. Because every field is compared —
     * not just the ones the caller happens to supply — this is also what keeps the three shapes
     * from bleeding into one another: a payment challenge (card fields null) consumed with a
     * cardId is refused, and a card challenge (amount null) can never authorise a money movement.
     */
    fun authorises(
        amount: String?,
        currency: String?,
        creditor: String?,
        documentSha256: String? = null,
        ceremonyId: String? = null,
        cardId: String? = null,
        cardAction: String? = null,
    ): Boolean {
        if (!amountEq(this.amount, amount)) return false
        if (!normEq(this.currency, currency)) return false
        if (this.creditorIban != null && !normEq(this.creditorIban, creditor)) return false
        if (!normEq(this.documentSha256, documentSha256)) return false
        if (this.ceremonyId != ceremonyId) return false
        if (!normEq(this.cardId, cardId)) return false
        if (this.cardAction != cardAction) return false
        return true
    }
}

/** Numeric amount equality: "250.0" == "250.00", and a malformed amount never authorises. */
private fun amountEq(a: String?, b: String?): Boolean = when {
    a == null && b == null -> true
    a == null || b == null -> false
    else -> runCatching { java.math.BigDecimal(a).compareTo(java.math.BigDecimal(b)) == 0 }.getOrDefault(false)
}

/** Equality ignoring spacing and case — how IBANs, currencies and hex ids are compared. */
private fun normEq(a: String?, b: String?): Boolean = norm(a) == norm(b)

private fun norm(s: String?) = s?.replace(" ", "")?.uppercase()
