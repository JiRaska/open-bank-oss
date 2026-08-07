// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

/** How the card was presented. Mirrors the channel toggles the customer already controls. */
enum class AuthorizationChannel { CONTACTLESS, ONLINE, ATM, CHIP_AND_PIN }

/** Why an authorisation was refused. Surfaced to the customer, so each value must be explainable. */
enum class DeclineReason {
    CARD_NOT_ACTIVE,
    CHANNEL_DISABLED,
    ABROAD_DISABLED,
    CATEGORY_BLOCKED,
    CATEGORY_LIMIT_EXCEEDED,
    DAILY_LIMIT_EXCEEDED,
    MONTHLY_LIMIT_EXCEEDED,
}

/**
 * One authorisation request as the acquirer presents it.
 *
 * [amountMinorUnits] is the amount being authorised; the spend figures are what has already been
 * authorised in the relevant window and are supplied by the caller, because this object is a pure
 * decision input with no database of its own.
 */
data class AuthorizationRequest(
    val amountMinorUnits: Long,
    val channel: AuthorizationChannel,
    val mcc: String?,
    val countryCode: String?,
    val spentTodayMinorUnits: Long = 0,
    val spentThisMonthMinorUnits: Long = 0,
    val spentThisMonthInCategoryMinorUnits: Long = 0,
)

/** Per-category configuration the customer set for a card. */
data class CategoryRule(val category: String, val blocked: Boolean = false, val monthlyLimitMinorUnits: Long? = null)

data class AuthorizationDecision(
    val approved: Boolean,
    val reason: DeclineReason? = null,
    /** The category the request was judged as — returned even on approval, so the ledger can keep it. */
    val category: String = MerchantCategoryTaxonomy.UNMAPPED,
)

/**
 * Decides whether a card authorisation is approved.
 *
 * **This is the enforcement point.** Until it existed, the channel toggles a customer set in the
 * app — contactless, online, ATM, abroad — were stored, returned by the API and never consulted by
 * anything: turning off "payments abroad" changed a boolean and nothing else. A control that
 * answers 200 and does not control is worse than an absent one, because the customer believes they
 * are protected.
 *
 * Pure by construction: no repository, no clock, no I/O. Everything it needs — the card, the
 * customer's category rules, and the spend already authorised — arrives as arguments, so every
 * branch below is reachable in a unit test. A money-path decision that can only be exercised
 * against a live acquirer is a decision nobody checks.
 *
 * Order matters and is deliberate: the card's own state first, then what the customer switched
 * off, then the amounts. The first failing rule wins, so the reason the customer is shown is the
 * one they can act on — "you turned gambling off", not "you are over a limit you did not set".
 */
object CardAuthorizationPolicy {
    /** Statuses that may authorise. Everything else — including PENDING — declines. */
    private val AUTHORIZABLE = setOf(CardStatus.ACTIVE)

    /** ISO-3166 alpha-2 of the issuing market; see the note at the abroad check. */
    const val HOME_COUNTRY: String = "CZ"

    fun decide(card: Card, request: AuthorizationRequest, rules: List<CategoryRule>): AuthorizationDecision {
        val category = MerchantCategoryTaxonomy.categoryOf(request.mcc)
        fun decline(reason: DeclineReason) = AuthorizationDecision(false, reason, category)

        if (card.status !in AUTHORIZABLE) return decline(DeclineReason.CARD_NOT_ACTIVE)
        if (isChannelDisabled(card, request.channel)) return decline(DeclineReason.CHANNEL_DISABLED)

        // Absent country is treated as domestic. An acquirer that omits it is far more often a
        // domestic terminal with a sparse message than a foreign one, and declining on missing data
        // would break ordinary spend to enforce a control the customer may not even have set.
        //
        // "Home" is the bank's market rather than a per-card field, because no such field exists on
        // the card: a Czech bank's cards are issued in CZ. When the bank issues outside CZ this
        // becomes a card attribute, and this constant is where that change starts.
        val abroad = request.countryCode != null && !request.countryCode.equals(HOME_COUNTRY, ignoreCase = true)
        if (abroad && !card.abroadEnabled) return decline(DeclineReason.ABROAD_DISABLED)

        val rule = rules.firstOrNull { it.category == category }
        categoryDecline(rule, category, request)?.let { return decline(it) }
        amountDecline(card, request)?.let { return decline(it) }

        return AuthorizationDecision(approved = true, category = category)
    }

    private fun isChannelDisabled(card: Card, channel: AuthorizationChannel): Boolean = when (channel) {
        AuthorizationChannel.CONTACTLESS -> !card.contactlessEnabled
        AuthorizationChannel.ONLINE -> !card.onlineEnabled
        AuthorizationChannel.ATM -> !card.atmEnabled
        // Chip and PIN is the fallback rail; there is no toggle for it, and inventing one would let
        // a customer lock themselves out of every terminal with no way back.
        AuthorizationChannel.CHIP_AND_PIN -> false
    }

    /** A block the customer set, or their cap for this category, whichever bites first. */
    private fun categoryDecline(rule: CategoryRule?, category: String, request: AuthorizationRequest): DeclineReason? {
        if (rule == null) return null
        if (rule.blocked && MerchantCategoryTaxonomy.isBlockable(category)) return DeclineReason.CATEGORY_BLOCKED
        val limit = rule.monthlyLimitMinorUnits ?: return null
        val after = request.spentThisMonthInCategoryMinorUnits + request.amountMinorUnits
        return if (after > limit) DeclineReason.CATEGORY_LIMIT_EXCEEDED else null
    }

    /** The card's own daily and monthly ceilings, checked last. */
    private fun amountDecline(card: Card, request: AuthorizationRequest): DeclineReason? = when {
        request.spentTodayMinorUnits + request.amountMinorUnits > card.dailyLimitMinorUnits ->
            DeclineReason.DAILY_LIMIT_EXCEEDED
        request.spentThisMonthMinorUnits + request.amountMinorUnits > card.monthlyLimitMinorUnits ->
            DeclineReason.MONTHLY_LIMIT_EXCEEDED
        else -> null
    }
}
