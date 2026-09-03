// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class CardStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    BLOCKED,
    EXPIRED,
    CANCELLED,

    /**
     * A single-use card that has been used. Terminal.
     *
     * Distinct from CANCELLED and EXPIRED because it is the promise being kept, not a failure: the
     * card authorised once and closed itself. Collapsing it into CANCELLED would tell a customer
     * their disposable card was cancelled, which reads as something going wrong.
     */
    CONSUMED,
}

/** Why a card reached a terminal status. Null while the card is alive. */
enum class CardClosedReason {
    SINGLE_USE_CONSUMED,
    VALIDITY_EXPIRED,
    LOST_OR_STOLEN,
    CUSTOMER_CANCEL,
}

/**
 * Form factor of a card.
 *
 * [VIRTUAL] and [SINGLE_USE] have no plastic: their PAN only ever exists in this service, so they
 * are the only two types whose synthetic PAN/CVV may be read back through the secure-details
 * endpoint (a physical card's PAN is printed on the plastic — re-serving it here would turn this
 * service into a PAN oracle for a card the caller may not be holding).
 *
 * [SINGLE_USE] is, for now, a *labelling* distinction only: it marks a virtual card intended for
 * one merchant / one purchase. **This service does NOT auto-cancel it after an authorisation** —
 * there is no authorisation flow here at all (card-issuance owns the lifecycle, not the rails), so
 * nothing in this codebase observes a SINGLE_USE card being spent. Enforcing "one use" needs an
 * authorisation feed (scheme/processor) that does not exist yet; until then a SINGLE_USE card must
 * be cancelled explicitly like any other. The gap is stated here rather than implied by the name.
 */
enum class CardType { DEBIT, CREDIT, PREPAID, VIRTUAL, SINGLE_USE }
enum class CardNetwork { VISA, MASTERCARD, AMEX, UNIONPAY }

data class Card(
    val id: UUID,
    val idempotencyKey: String,
    val partyId: UUID,
    val accountId: UUID,
    val productCode: String,
    val cardType: CardType,
    val network: CardNetwork,
    val maskedPan: String,
    val cardholderName: String,
    val embossedName: String,
    val expiryDate: LocalDate,
    val status: CardStatus,
    val dailyLimitMinorUnits: Long,
    val monthlyLimitMinorUnits: Long,
    val currency: String,
    val deliveryAddress: String?,
    val activatedAt: Instant?,
    val blockedAt: Instant?,
    val blockedReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    // Customer channel controls (#1): which rails the card may transact on. Default all-on.
    val contactlessEnabled: Boolean = true,
    val onlineEnabled: Boolean = true,
    val atmEnabled: Boolean = true,
    val abroadEnabled: Boolean = true,
    // Synthetic PAN vault (#4): AES-256-GCM ciphertext, never the clear value. Nullable because
    // cards issued before the pan_encrypted/cvv_encrypted migration have no stored PAN at all.
    /**
     * When a SINGLE_USE card stops being usable even if never presented. Null for every other type.
     * A disposable card that is never spent must still stop being a live PAN.
     */
    val expiresAt: Instant? = null,

    /** Why the card reached a terminal status; null while it is alive. */
    val closedReason: CardClosedReason? = null,

    val panEncrypted: String? = null,
    val cvvEncrypted: String? = null,
    /**
     * The delegation grant that authorised issuing this card to someone other than the account
     * owner (ADR-0249 D1 — "dodatková karta"). NULL for an ordinary card, which is every card
     * issued before this existed: absence means "the holder is the account owner", not "unknown".
     *
     * It is set once, at issue, and never updated — the whole point is that it names the authority
     * this instrument rests on. When that authority ends, [Card] does not merely become invisible:
     * ADR-0249 D2 requires the card to be BLOCKED, because a card that still transacts after its
     * authorisation ended is the failure everyone would remember. That is what makes this column a
     * load-bearing link rather than a decorative back-reference.
     */
    val delegationGrantId: UUID? = null,
) {
    /** True when this card's PAN only ever existed digitally — see [CardType]. */
    val isVirtualForm: Boolean get() = cardType in VIRTUAL_FORM_TYPES

    /**
     * True when this card was issued to a delegate under a grant (ADR-0249 D1) rather than to the
     * account's own owner. Derived from [delegationGrantId] alone: `partyId != accountOwner` cannot
     * be computed here, because this aggregate does not know who owns the account.
     */
    val isDelegated: Boolean get() = delegationGrantId != null

    fun activate(now: Instant) = also {
        require(status == CardStatus.PENDING) { "Only PENDING cards can be activated, current: $status" }
    }.copy(status = CardStatus.ACTIVE, activatedAt = now, updatedAt = now)

    fun block(reason: String, now: Instant) = also {
        require(status in setOf(CardStatus.ACTIVE, CardStatus.SUSPENDED)) { "Cannot block card in status $status" }
        require(reason.isNotBlank()) { "Block reason required" }
    }.copy(status = CardStatus.BLOCKED, blockedAt = now, blockedReason = reason, updatedAt = now)

    /**
     * The single-use card authorised once and closed itself.
     *
     * Driven by the processor's lifecycle event, not by us: the authorize-once guarantee lives at
     * the processor, and this records the outcome so the customer can see it. Only SINGLE_USE
     * reaches it — marking any other card CONSUMED would be a lie about why it stopped working.
     */
    fun consume(now: Instant) = also {
        require(cardType == CardType.SINGLE_USE) { "Only SINGLE_USE cards can be consumed, this is $cardType" }
        require(status == CardStatus.ACTIVE) { "Only ACTIVE cards can be consumed, current: $status" }
    }.copy(
        status = CardStatus.CONSUMED,
        closedReason = CardClosedReason.SINGLE_USE_CONSUMED,
        updatedAt = now,
    )

    /**
     * The card's validity window ran out before it was ever used. EXPIRED rather than CONSUMED:
     * nothing was spent, and the difference is what the customer is told.
     */
    fun expireUnused(now: Instant) = also {
        require(status in LIVE_STATUSES) { "Cannot expire card in status $status" }
    }.copy(
        status = CardStatus.EXPIRED,
        closedReason = CardClosedReason.VALIDITY_EXPIRED,
        updatedAt = now,
    )

    fun suspend(now: Instant) = also {
        require(status == CardStatus.ACTIVE) { "Only ACTIVE cards can be suspended" }
    }.copy(status = CardStatus.SUSPENDED, updatedAt = now)

    fun resume(now: Instant) = also {
        require(status == CardStatus.SUSPENDED) { "Only SUSPENDED cards can be resumed" }
    }.copy(status = CardStatus.ACTIVE, updatedAt = now)

    /**
     * Close the card for good. Allowed from every non-terminal status **including BLOCKED** — a
     * customer who reported a card lost (→ BLOCKED) routinely then closes it — and CANCELLED is
     * itself terminal: no transition, limit change or control change may follow it (the other
     * `require` guards already exclude CANCELLED, since none of them lists it as a legal source).
     * EXPIRED is likewise terminal: an expired card is already dead, cancelling it is a no-op the
     * caller should not be silently granted.
     *
     * [reason] is carried on the emitted `CardStatusChanged` event; it also overwrites
     * [blockedReason], the aggregate's single "why is this card not usable" note. Cancelling a
     * BLOCKED card with no reason therefore preserves the original block reason.
     */
    fun cancel(reason: String?, now: Instant) = also {
        require(status in CANCELLABLE_STATUSES) { "Cannot cancel card in status $status" }
    }.copy(status = CardStatus.CANCELLED, blockedReason = reason ?: blockedReason, updatedAt = now)

    /**
     * Customer-set spending limits. Only a live card may be re-limited (a BLOCKED/CANCELLED/EXPIRED
     * card has no spend to cap). Limits are non-negative and daily must not exceed monthly.
     */
    fun withLimits(dailyMinor: Long, monthlyMinor: Long, now: Instant) = also {
        require(status in setOf(CardStatus.ACTIVE, CardStatus.SUSPENDED, CardStatus.PENDING)) {
            "Cannot change limits on a card in status $status"
        }
        require(dailyMinor >= 0 && monthlyMinor >= 0) { "Limits must be non-negative" }
        require(dailyMinor <= monthlyMinor) { "Daily limit ($dailyMinor) cannot exceed monthly ($monthlyMinor)" }
    }.copy(dailyLimitMinorUnits = dailyMinor, monthlyLimitMinorUnits = monthlyMinor, updatedAt = now)

    /**
     * Customer channel controls (#1): turn contactless / online (e-commerce) / ATM / abroad usage
     * on or off. Only a live card may be re-controlled — a terminal card has no usage to gate.
     */
    fun withControls(contactless: Boolean, online: Boolean, atm: Boolean, abroad: Boolean, now: Instant) = also {
        require(status in setOf(CardStatus.ACTIVE, CardStatus.SUSPENDED, CardStatus.PENDING)) {
            "Cannot change controls on a card in status $status"
        }
    }.copy(
        contactlessEnabled = contactless,
        onlineEnabled = online,
        atmEnabled = atm,
        abroadEnabled = abroad,
        updatedAt = now,
    )

    companion object {
        /** Statuses a card may be cancelled from. CANCELLED and EXPIRED are terminal. */
        val CANCELLABLE_STATUSES = setOf(
            CardStatus.PENDING,
            CardStatus.ACTIVE,
            CardStatus.SUSPENDED,
            CardStatus.BLOCKED,
        )

        /** Statuses that consume a product's card quota — a dead card must not hold a slot. */
        val LIVE_STATUSES = setOf(CardStatus.PENDING, CardStatus.ACTIVE, CardStatus.SUSPENDED)

        /** Card types with no plastic, i.e. the only ones whose PAN may be re-served digitally. */
        val VIRTUAL_FORM_TYPES = setOf(CardType.VIRTUAL, CardType.SINGLE_USE)

        /**
         * Statuses a card can never leave — the complement of [CANCELLABLE_STATUSES]. A card here
         * is dead: no transition applies, and nothing should be provisioned for it (the PAN-vault
         * backfill skips them for exactly that reason).
         */
        val TERMINAL_STATUSES = setOf(CardStatus.CANCELLED, CardStatus.EXPIRED, CardStatus.CONSUMED)
    }
}
