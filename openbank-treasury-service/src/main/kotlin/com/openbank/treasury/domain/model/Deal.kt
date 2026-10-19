// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import com.openbank.libs.domain.money.RoundingPolicy
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Products (ADR-0315 D2). The three money-market products are the MVP core; [FX_SPOT] is the
 * first non-money-market product (#10896).
 *
 * - [MM_PLACEMENT]: the bank LENDS to another bank (an asset, consumes that bank's credit limit).
 * - [MM_BORROWING]: the bank BORROWS from another bank (a liability, consumes no credit limit).
 * - [CNB_DEPOSIT_FACILITY]: overnight deposit at the Czech National Bank (an asset, CZK only).
 * - [CNB_LOMBARD]: overnight borrowing from the ČNB marginal lending (lombard) facility against
 *   eligible collateral (a liability, CZK only, #10896). The collateral pledge is NOT modelled.
 * - [FX_SPOT]: the bank buys or sells a foreign currency against CZK with a counterparty (see
 *   [FxTerms]). No interest, no maturity: it is final once SETTLED. It carries settlement risk
 *   until then, so it consumes the counterparty's CZK limit by its CZK equivalent while
 *   PENDING_APPROVAL, BOOKED or CONFIRMED. `isAsset = false`: it opens no placement-like claim.
 */
enum class ProductType(val isAsset: Boolean, val isCnbFacility: Boolean = false, val legacy: Boolean = false) {
    MM_PLACEMENT(isAsset = true, legacy = true),
    MM_BORROWING(isAsset = false, legacy = true),
    CNB_DEPOSIT_FACILITY(isAsset = true, isCnbFacility = true, legacy = true),
    CNB_LOMBARD(isAsset = false, isCnbFacility = true, legacy = true),
    FX_SPOT(isAsset = false, legacy = true),

    // ADR-0344 D2: instruments described by their legs; they settle, accrue, pay and mature through
    // the posting-rule matrix (ADR-0344 D7, [PostingMatrix]).
    CASH(isAsset = false),
    TREASURY_BILL(isAsset = true),
    BOND(isAsset = true),
    BILL_OF_EXCHANGE(isAsset = true),
    LOAN(isAsset = true),
    FX_FORWARD(isAsset = false),
    FX_SWAP(isAsset = false),
    FRA(isAsset = false),
    IRS(isAsset = false),
    REPO(isAsset = false),
    REVERSE_REPO(isAsset = true),
    ;

    /** A cash movement or an FX outright settles on ONE date: its maturity may equal its value date. */
    val settlesOnOneDay: Boolean get() = this == CASH || this == FX_FORWARD

    /** ADR-0344 D2: the products whose legs the client must send (no flat-field derivation). */
    val legsRequired: Boolean get() = !legacy
}

/** The bank's side of an FX spot deal, on the FOREIGN currency: BUY = the bank buys it and pays CZK. */
enum class FxSide { BUY, SELL }

/**
 * The FX-specific terms of an [ProductType.FX_SPOT] deal. The deal's `currency` is the foreign
 * currency, `principal` its amount, and `rate` the dealer-entered deal rate in CZK per 1 unit of it.
 * [counterAmount] is the CZK leg, `principal × rate` half-up to 2 dp.
 *
 * [midRate] / [rateFlag] record the tolerance check against fx-service's mid (#10896). A deal
 * outside tolerance is FLAGGED, never blocked: the four-eyes approver sees the flag and decides.
 */
data class FxTerms(
    val side: FxSide,
    val counterAmount: BigDecimal,
    val midRate: BigDecimal? = null,
    val rateFlag: String? = null,
) {
    companion object {
        /** `buy`/`sell` as a client names them: exactly one must be CZK. Returns (foreign currency, side). */
        fun fromCurrencies(buyCurrency: String, sellCurrency: String): Pair<String, FxSide> {
            require(buyCurrency != sellCurrency) { "buyCurrency and sellCurrency must differ" }
            return when (Deal.CZK) {
                sellCurrency -> buyCurrency to FxSide.BUY
                buyCurrency -> sellCurrency to FxSide.SELL
                else -> throw IllegalArgumentException(
                    "an FX spot deal is against CZK: one of buyCurrency/sellCurrency must be CZK",
                )
            }
        }
    }
}

/**
 * ADR-0315 D2 lifecycle: `DRAFT → PENDING_APPROVAL → BOOKED → CONFIRMED → SETTLED → MATURED`, plus
 * `CANCELLED` (before booking) and `REVERSED` (after). `CONFIRMED` = the counterparty's
 * confirmation of the booked terms has been received — by a back-office person, or, in the
 * sandbox, by the simulated counterparty set (ADR-0315 D9). Nothing posts on confirmation.
 * `REJECTED` is not a state: a rejection sends the deal back to `DRAFT` with the reason recorded
 * on its timeline.
 *
 * Backward compatibility (no data migration): deals BOOKED before `CONFIRMED` existed stay BOOKED.
 * Whether one may settle straight from BOOKED is a deployment decision,
 * `openbank.treasury.confirmation.required` (default true) — see [Deal.settle].
 */
enum class DealState { DRAFT, PENDING_APPROVAL, BOOKED, CONFIRMED, SETTLED, MATURED, CANCELLED, REVERSED }

/** Who acts. Derived from the authenticated principal, never from a request body. */
enum class ActorType { HUMAN, AI_AGENT, SERVICE, SYSTEM }

data class Actor(val id: String, val type: ActorType) {
    init {
        require(id.isNotBlank()) { "actor id must not be blank" }
    }

    companion object {
        /** The in-process simulated counterparty set (ADR-0315 D9) that settles and matures deals. */
        val SIMULATED_MARKET = Actor("system:simulated-market", ActorType.SYSTEM)

        /** Records an FX spot rate flag on the timeline (#10896). */
        val FX_RATE_CHECK = Actor("system:fx-rate-check", ActorType.SYSTEM)

        /**
         * Same convention as libs' AuthorizeInterceptor: an agent presents a `sub` prefixed
         * `agent:` (ADR-0031); a Keycloak client-credentials token is `service-account-<client>`.
         */
        fun fromPrincipalName(name: String): Actor = Actor(
            id = name,
            type = when {
                name.startsWith("agent:") -> ActorType.AI_AGENT
                name.startsWith("service-account-") -> ActorType.SERVICE
                else -> ActorType.HUMAN
            },
        )
    }
}

/** Raised when approver == creator (or submitter). Mapped to 422. */
class FourEyesViolationException(message: String) : RuntimeException(message)

/** Raised when a non-human principal attempts a step only a person may take. Mapped to 403. */
class ActorNotPermittedException(message: String) : RuntimeException(message)

/** Raised when a counterparty limit would be exceeded. Mapped to 422. */
class LimitBreachedException(val check: LimitCheck) :
    RuntimeException(
        "counterparty ${check.counterpartyId} limit breached: exposure ${check.exposureAfter} " +
            "> limit ${check.limit} ${check.currency}",
    )

/** One entry of a deal's timeline — who moved it from where to where, and why. */
data class DealTransition(
    val from: DealState?,
    val to: DealState,
    val actor: Actor,
    val at: Instant,
    val note: String? = null,
)

/**
 * A money-market deal (ADR-0315 D2/D3/D4). Immutable: every transition returns a new instance
 * with one more [DealTransition].
 *
 * Interest convention: **ACT/360**, simple interest paid at maturity — the CZK (PRIBOR) and EUR
 * (EURIBOR/€STR) money-market convention. `rate` is an annual percentage (4.25 means 4.25 % p.a.).
 */
@Suppress("TooManyFunctions")
data class Deal(
    val id: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val rate: BigDecimal,
    val tradeDate: LocalDate,
    val valueDate: LocalDate,
    val maturityDate: LocalDate,
    val state: DealState,
    val createdBy: Actor,
    val createdAt: Instant,
    val updatedAt: Instant,
    val submittedBy: Actor? = null,
    val approvedBy: Actor? = null,
    val limitCheck: LimitCheck? = null,
    /** An AI agent's inputs and rationale for a draft it proposed (ADR-0315 D10); null for humans. */
    val rationale: String? = null,
    /**
     * The data an AI agent's draft was built from, as a JSON object (ADR-0315 D10) — the quote,
     * curve point, headroom or position it read — so the human who submits and the approver who
     * books can check the proposal against what the agent actually saw. Null for a human draft.
     */
    val inputs: String? = null,
    /** A senior approver's recorded override of a counterparty-limit breach (ADR-0315 D4). */
    val limitOverride: LimitOverride? = null,
    /** Present exactly when `product == FX_SPOT`. */
    val fx: FxTerms? = null,
    val history: List<DealTransition> = emptyList(),
    /** ADR-0344 D2: only ACTUAL books, posts and pays. */
    val kind: DealKind = DealKind.ACTUAL,
    /** The instrument's legs; derived from the flat fields for the legacy products ([LegacyLegs]). */
    val legs: List<LegSpec> = emptyList(),
    val header: DealHeader = DealHeader.EMPTY,
    /** The terms version (1 at draft). */
    val version: Int = 1,
    /** 0 until the first cash-flow ledger is generated at submission (ADR-0344 D4). */
    val scheduleVersion: Int = 0,
    val scheduleHash: String? = null,
) {
    init {
        require(currency in SUPPORTED_CURRENCIES) { "currency must be one of $SUPPORTED_CURRENCIES" }
        require(principal.signum() > 0) { "principal must be positive" }
        require(principal.scale() <= 2) { "principal has at most 2 decimal places" }
        require(rate.signum() >= 0) { "rate must not be negative" }
        require(!valueDate.isBefore(tradeDate)) { "valueDate must not precede tradeDate" }
        if (product == ProductType.FX_SPOT) requireFxSpot() else require(fx == null) { "only FX_SPOT carries FX terms" }
        if (product != ProductType.FX_SPOT) {
            require(rate <= MAX_RATE) { "rate is an annual percentage and must not exceed $MAX_RATE" }
            if (product.settlesOnOneDay) {
                require(!maturityDate.isBefore(valueDate)) { "maturityDate must not precede valueDate" }
            } else {
                require(maturityDate.isAfter(valueDate)) { "maturityDate must be after valueDate" }
            }
        }
        if (product.isCnbFacility) {
            val facility = if (product == ProductType.CNB_LOMBARD) "lombard facility" else "deposit facility"
            require(currency == CZK) { "the ČNB $facility is CZK only" }
            require(counterpartyId == CNB_COUNTERPARTY_ID) {
                "the ČNB $facility's counterparty is $CNB_COUNTERPARTY_ID"
            }
            require(maturityDate == DealCalendars.overnight(valueDate, currency)) {
                "the ČNB $facility is overnight: maturityDate must be the next business day"
            }
            if (product == ProductType.CNB_LOMBARD) {
                require(rate.signum() > 0) { "the ČNB lombard rate must be positive" }
            }
        } else {
            require(counterpartyId != CNB_COUNTERPARTY_ID) { "interbank products cannot face the central bank" }
        }
        require(scheduleVersion >= 0 && version >= 1) { "versions are positive" }
        header.validate()
        if (product.legsRequired) {
            require(legs.isNotEmpty()) { "a $product deal is described by its legs" }
        }
        require(legs.size <= MAX_LEGS) { "a deal has at most $MAX_LEGS legs" }
        require(legs.sumOf { runCatching { it.expectedPeriods() }.getOrDefault(0L) } <= MAX_PERIODS_PER_DEAL) {
            "a deal generates at most $MAX_PERIODS_PER_DEAL periods across its legs"
        }
        if (product.legsRequired && legs.isNotEmpty()) requireFlatFieldsFromLegs()
        require(inputs == null || inputs.length <= MAX_INPUTS_CHARS) {
            "inputs is at most $MAX_INPUTS_CHARS characters"
        }
        legs.forEach { it.validate() }
    }

    val bookId: String? get() = header.bookId

    /**
     * For a leg-described product the flat fields the limit checks read MUST be the legs' own
     * figures: currency = the first leg's, principal = the largest notional in that currency,
     * value date = the earliest accrual start, maturity = the latest accrual end. A request whose
     * flat fields disagree is refused (400) — otherwise the product and counterparty limits would
     * measure one deal while the schedule books another.
     */
    private fun requireFlatFieldsFromLegs() {
        val ccy = legs.first().currency
        val notional = legs.filter { it.currency == ccy }.maxOf { it.notional }
        require(currency == ccy) { "currency $currency disagrees with the legs ($ccy)" }
        require(principal.compareTo(notional) == 0) {
            "principal $principal disagrees with the legs' notional $notional"
        }
        if (product == ProductType.BOND) {
            // A bond purchase settles on its value date somewhere within the coupon schedule
            // (secondary market): the coupon leg keeps the security's own dates.
            require(
                !valueDate.isBefore(
                    legs.minOf {
                        it.accrualStart
                    },
                ) &&
                    valueDate.isBefore(legs.maxOf { it.accrualEnd }),
            ) {
                "a bond's valueDate lies within its coupon schedule"
            }
        } else {
            require(
                valueDate == legs.minOf {
                    it.accrualStart
                },
            ) { "valueDate disagrees with the legs' earliest accrual start" }
        }
        require(
            maturityDate == legs.maxOf {
                it.accrualEnd
            },
        ) { "maturityDate disagrees with the legs' latest accrual end" }
    }

    private fun requireFxSpot() {
        val terms = requireNotNull(fx) { "an FX_SPOT deal needs its FX terms" }
        require(currency != CZK) { "an FX spot deal's currency is the foreign one, bought or sold against CZK" }
        require(rate.signum() > 0) { "an FX deal rate must be positive" }
        require(rate.scale() <= FX_RATE_SCALE) { "an FX deal rate has at most $FX_RATE_SCALE decimal places" }
        require(rate < FX_RATE_LIMIT) {
            "an FX deal rate must be less than $FX_RATE_LIMIT (deals.rate is NUMERIC(9,6): at most 3 integer digits)"
        }
        require(maturityDate == valueDate) { "an FX spot deal has no maturity: maturityDate equals valueDate" }
        require(DealCalendars.isSpotBusinessDay(valueDate, currency)) {
            "an FX spot value date must be a business day in both currencies"
        }
        val spot = DealCalendars.spotDate(tradeDate, currency)
        require(!valueDate.isAfter(spot)) {
            "an FX spot value date is at most T+2 business days ($spot); later is a forward"
        }
        require(terms.counterAmount.compareTo(counterAmountOf(principal, rate)) == 0) {
            "the CZK counter amount must be principal x rate, half-up to 2 dp"
        }
        require(terms.counterAmount.signum() > 0) {
            "the CZK counter amount must be positive (principal $principal x rate $rate rounds to " +
                "${terms.counterAmount}, which the store's fx_counter_amount > 0 constraint rejects)"
        }
    }

    /** The currency whose counterparty limit this deal consumes: CZK for an FX spot (its CZK equivalent). */
    val limitCurrency: String get() = if (product == ProductType.FX_SPOT) CZK else currency

    /** What the deal adds to the limit: principal for an asset, the CZK leg for FX spot, else zero. */
    val limitAmount: BigDecimal
        get() = when {
            product == ProductType.FX_SPOT -> checkNotNull(fx).counterAmount
            product.isAsset -> principal
            else -> BigDecimal.ZERO
        }

    /**
     * Record the tolerance check of the dealer's rate against [mid] (#10896). Flags, never blocks:
     * a deviation beyond [tolerancePercent] — or no mid to compare with — is written on the deal
     * and its timeline for the approver. Only FX_SPOT deals are checked.
     */
    fun checkRate(mid: BigDecimal?, tolerancePercent: BigDecimal, at: Instant): Deal {
        val terms = fx ?: return this
        val flag = when {
            mid == null || mid.signum() <= 0 -> "fx-service mid unavailable; deal rate $rate is unvalidated"
            else -> {
                val deviation = RoundingPolicy.RATE_PERCENT.divide((rate - mid).abs().multiply(HUNDRED), mid)
                if (deviation > tolerancePercent) {
                    "deal rate $rate deviates $deviation % from fx-service mid $mid (tolerance $tolerancePercent %)"
                } else {
                    null
                }
            }
        }
        val checked = copy(fx = terms.copy(midRate = mid, rateFlag = flag), updatedAt = at)
        return if (flag == null) {
            checked
        } else {
            checked.copy(
                history = history + DealTransition(state, state, Actor.FX_RATE_CHECK, at, "rate flagged: $flag"),
            )
        }
    }

    /**
     * True while this deal is PENDING_APPROVAL on [counterpartyId]'s [currency] limit with a senior
     * override still in force (ADR-0315 D4, #10896). Keyed on [limitCurrency], not `currency`.
     */
    fun holdsActiveLimitOverride(counterpartyId: String, currency: String): Boolean =
        state == DealState.PENDING_APPROVAL &&
            limitOverride != null &&
            consumesLimit &&
            this.counterpartyId == counterpartyId &&
            limitCurrency == currency

    /** ACT/360 day count between value and maturity date. */
    val days: Long get() = ChronoUnit.DAYS.between(valueDate, maturityDate)

    /** Interest at maturity, ACT/360, rounded half-up to the currency's 2 minor units. */
    val interest: BigDecimal get() = SimpleInterest.of(
        principal,
        rate,
        DayCountConvention.ACT_360,
        valueDate,
        maturityDate,
    )

    /**
     * ADR-0315 D4: the counterparty check is RECORDED here (a breach may still book under a senior
     * override); the product limit is ENFORCED — a deal outside its product mandate never reaches
     * an approver's queue. [productCheck] is required, never defaulted: a missing check must not
     * read as a passed one.
     */
    /**
     * ADR-0344 catalogue (draft edit): a DRAFT takes new terms. [replacement] is the draft the new
     * terms would make (built by the same path as a new draft, so every invariant holds again); its
     * identity, creator and timeline are this deal's. A human may edit any draft; an AI agent only
     * its own. The product cannot change — that is a different deal — and the terms version grows.
     */
    fun editDraft(replacement: Deal, actor: Actor, at: Instant): Deal {
        check(state == DealState.DRAFT) {
            "only a DRAFT deal can be edited (deal is $state); change a booked deal by a correction"
        }
        if (actor.type != ActorType.HUMAN && !(actor.type == ActorType.AI_AGENT && actor.id == createdBy.id)) {
            throw ActorNotPermittedException("a ${actor.type} principal may edit only its own draft")
        }
        require(replacement.product == product) { "the product of a draft cannot change (draft a new deal)" }
        return replacement.copy(
            id = id,
            state = DealState.DRAFT,
            createdBy = createdBy,
            createdAt = createdAt,
            updatedAt = at,
            version = version + 1,
            history =
            history +
                DealTransition(
                    DealState.DRAFT,
                    DealState.DRAFT,
                    actor,
                    at,
                    "draft edited (terms version ${version + 1})",
                ),
        )
    }

    fun submit(actor: Actor, check: LimitCheck, at: Instant, productCheck: ProductLimitCheck): Deal {
        requireHuman(actor, "submit")
        requireState(DealState.DRAFT, "submit")
        check(kind != DealKind.HYPOTHETICAL) { "a HYPOTHETICAL deal is priced only and can never be submitted" }
        if (productCheck.breached) throw ProductLimitBreachedException(productCheck)
        return transition(DealState.PENDING_APPROVAL, actor, at, limitNote(check))
            .copy(submittedBy = actor, limitCheck = check)
    }

    /**
     * Four-eyes booking (ADR-0315 D3). Enforced HERE, not only in OPA: a policy that is not
     * enforcing would otherwise silently allow self-approval. Order matters — the agent check
     * runs first, so an agent is refused as an agent even when it is also the creator.
     */
    fun approve(actor: Actor, check: LimitCheck, at: Instant, productCheck: ProductLimitCheck): Deal {
        requireHuman(actor, "approve")
        requireState(DealState.PENDING_APPROVAL, "approve")
        requireSecondPerson(actor)
        // ADR-0315 D4: the product limit is re-evaluated at approval — the declared mandate may have
        // been tightened since submission. Not overridable (see ProductLimit); the senior override
        // below covers the counterparty limit only.
        if (productCheck.breached) throw ProductLimitBreachedException(productCheck)
        // ADR-0315 D4: a breach blocks booking unless a second, senior approver recorded an override
        // with a reason that still covers it. The booking approver is yet another person.
        if (check.breached) requireOverrideCovers(actor, check)
        return transition(DealState.BOOKED, actor, at, limitNote(check)).copy(approvedBy = actor, limitCheck = check)
    }

    /**
     * ADR-0315 D4: a SENIOR approver records an override of a counterparty-limit breach, with a
     * reason. Only for a PENDING_APPROVAL deal whose current check IS breached (an override of a
     * deal within limit would be a blank cheque for a later, larger breach). Human only, and never
     * the deal's creator or submitter. The override covers the exposure measured now; if exposure
     * grows before booking, approval refuses again.
     */
    fun overrideLimit(actor: Actor, reason: String, check: LimitCheck, at: Instant): Deal {
        requireHuman(actor, "override the limit of")
        requireState(DealState.PENDING_APPROVAL, "override the limit of")
        require(reason.isNotBlank()) { "a limit override needs a reason" }
        check(check.breached) {
            "the deal is within its limit (headroom ${check.headroomAfter}); there is nothing to override"
        }
        val role = when (actor.id) {
            createdBy.id -> "creator"
            submittedBy?.id -> "submitter"
            else -> null
        }
        role?.let { throw FourEyesViolationException("four-eyes: the deal's $it must not override its limit") }
        val override = LimitOverride(actor, reason, at, check.exposureAfter, check.limit)
        return copy(
            limitOverride = override,
            limitCheck = check,
            updatedAt = at,
            history = history + DealTransition(
                from = state,
                to = state,
                actor = actor,
                at = at,
                note = "limit override: exposure ${check.exposureAfter} > limit ${check.limit} " +
                    "${check.currency}; reason: $reason",
            ),
        )
    }

    fun reject(actor: Actor, reason: String, at: Instant): Deal {
        requireHuman(actor, "reject")
        requireState(DealState.PENDING_APPROVAL, "reject")
        require(reason.isNotBlank()) { "a rejection needs a reason" }
        return transition(DealState.DRAFT, actor, at, "rejected: $reason")
            .copy(submittedBy = null, limitCheck = null, limitOverride = null)
    }

    fun cancel(actor: Actor, at: Instant): Deal {
        requireHuman(actor, "cancel")
        check(state == DealState.DRAFT || state == DealState.PENDING_APPROVAL) {
            "only a DRAFT or PENDING_APPROVAL deal can be cancelled (deal is $state); a booked deal is reversed"
        }
        return transition(DealState.CANCELLED, actor, at, null)
    }

    /**
     * ADR-0315 D2: the counterparty's confirmation of the booked terms was received. A back-office
     * person (never the deal's creator or submitter: the dealer who struck a trade does not also
     * attest that the other side agreed to it) or the in-process simulated counterparty
     * ([Actor.SIMULATED_MARKET], sandbox only). An AI agent or a service account never confirms,
     * whatever roles it carries. Posts nothing.
     */
    fun confirm(actor: Actor, at: Instant, reference: String? = null): Deal {
        requireHumanOrSystem(actor, "confirm")
        requireState(DealState.BOOKED, "confirm")
        if (actor.type == ActorType.HUMAN) {
            val role = when (actor.id) {
                createdBy.id -> "creator"
                submittedBy?.id -> "submitter"
                else -> null
            }
            role?.let { throw FourEyesViolationException("four-eyes: the deal's $it must not confirm it") }
        }
        require(reference == null || reference.length <= MAX_REFERENCE_LENGTH) {
            "a confirmation reference has at most $MAX_REFERENCE_LENGTH characters"
        }
        val note = "counterparty confirmation received" + (reference?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        return transition(DealState.CONFIRMED, actor, at, note)
    }

    /**
     * Settlement on or after the value date. From CONFIRMED always; from BOOKED only when the
     * deployment does not require counterparty confirmation ([confirmationRequired] false) — the
     * compatibility path for deals booked before `CONFIRMED` existed, chosen by configuration
     * instead of a data migration that would claim confirmations nobody received.
     */
    fun settle(actor: Actor, today: LocalDate, at: Instant, confirmationRequired: Boolean = true): Deal {
        requireHumanOrSystem(actor, "settle")
        check(state in settleableStates(confirmationRequired)) {
            if (state == DealState.BOOKED) {
                "cannot settle a deal in state BOOKED: counterparty confirmation is required (confirm it first)"
            } else {
                val expected = settleableStates(confirmationRequired).joinToString("/")
                "cannot settle a deal in state $state (expected $expected)"
            }
        }
        check(!valueDate.isAfter(today)) { "deal cannot settle before its value date $valueDate" }
        requirePostable("settle")
        return transition(DealState.SETTLED, actor, at, null)
    }

    fun mature(actor: Actor, today: LocalDate, at: Instant): Deal {
        requireHumanOrSystem(actor, "mature")
        check(product != ProductType.FX_SPOT) { "an FX spot deal has no maturity; it is final once SETTLED" }
        check(!product.settlesOnOneDay) { "a $product deal has no maturity; it is final once SETTLED" }
        requireState(DealState.SETTLED, "mature")
        requirePostable("mature")
        check(!maturityDate.isAfter(today)) { "deal cannot mature before its maturity date $maturityDate" }
        return transition(DealState.MATURED, actor, at, null)
    }

    /**
     * A booked, confirmed or settled deal is undone by reversal. From SETTLED the settlement journal
     * is offset in the ledger; from BOOKED or CONFIRMED nothing had posted. A matured deal is final
     * in the MVP.
     * Four-eyes applies: the creator cannot reverse their own deal.
     */
    fun reverse(actor: Actor, reason: String, at: Instant): Deal {
        requireHuman(actor, "reverse")
        check(state == DealState.BOOKED || state == DealState.CONFIRMED || state == DealState.SETTLED) {
            "only a BOOKED, CONFIRMED or SETTLED deal can be reversed (deal is $state)"
        }
        require(reason.isNotBlank()) { "a reversal needs a reason" }
        if (actor.id == createdBy.id) {
            throw FourEyesViolationException("four-eyes: the deal's creator must not reverse it")
        }
        return transition(DealState.REVERSED, actor, at, "reversed: $reason")
    }

    /**
     * ADR-0344 D7 boundary: only an ACTUAL deal moves cash or posts; PLANNED and HYPOTHETICAL never
     * do. Every product has posting rules (legacy [PostingRules], the others [PostingMatrix]).
     */
    private fun requirePostable(action: String) {
        check(kind == DealKind.ACTUAL) { "a $kind deal never posts or pays; it cannot $action" }
    }

    /** The ledger schedule generated at submission (ADR-0344 D4). */
    fun withSchedule(version: Int, hash: String): Deal {
        require(version > scheduleVersion) { "a schedule version only grows" }
        return copy(scheduleVersion = version, scheduleHash = hash)
    }

    /**
     * Record that the bank's confirmation went to the counterparty (ADR-0344 catalogue). Back office
     * or an approver, never an AI agent and never the deal's creator or submitter; changes no state
     * and posts nothing. Only a concluded deal (BOOKED or later, not CANCELLED/REVERSED) is confirmed.
     */
    fun recordConfirmationSent(actor: Actor, at: Instant, documentId: String?, channel: String?): Deal {
        requireHuman(actor, "send the confirmation of")
        check(state in setOf(DealState.BOOKED, DealState.CONFIRMED, DealState.SETTLED, DealState.MATURED)) {
            "only a booked deal is confirmed to the counterparty (deal is $state)"
        }
        val role = when (actor.id) {
            createdBy.id -> "creator"
            submittedBy?.id -> "submitter"
            else -> null
        }
        role?.let { throw FourEyesViolationException("four-eyes: the deal's $it must not send its confirmation") }
        require(documentId == null || documentId.length <= MAX_REFERENCE_LENGTH) { "documentId is too long" }
        require(channel == null || channel.length <= MAX_CHANNEL_LENGTH) { "channel is too long" }
        val sent = SentConfirmation(actor, at, documentId, channel)
        return copy(
            header = header.copy(confirmationsSent = header.confirmationsSent + sent),
            updatedAt = at,
            history = history + DealTransition(
                state,
                state,
                actor,
                at,
                "confirmation sent" + (documentId?.let { " (document $it)" } ?: "") +
                    (channel?.let { " via $it" } ?: ""),
            ),
        )
    }

    /** True while the deal still consumes its counterparty's credit limit. */
    val consumesLimit: Boolean
        get() = kind == DealKind.ACTUAL &&
            when (product) {
                ProductType.FX_SPOT -> state in FX_LIMIT_CONSUMING_STATES
                else -> product.isAsset && state in LIMIT_CONSUMING_STATES
            }

    private fun transition(to: DealState, actor: Actor, at: Instant, note: String?) = copy(
        state = to,
        updatedAt = at,
        history = history + DealTransition(from = state, to = to, actor = actor, at = at, note = note),
    )

    /** A breach books only under a senior override that still covers it, and not by that senior. */
    private fun requireOverrideCovers(actor: Actor, check: LimitCheck) {
        val override = limitOverride
        if (override == null || check.exposureAfter > override.coversExposureUpTo) throw LimitBreachedException(check)
        if (actor.id == override.by.id) {
            throw FourEyesViolationException("four-eyes: the senior who overrode the limit must not also book the deal")
        }
    }

    /** Four-eyes: the approver is neither the creator nor the submitter. */
    private fun requireSecondPerson(actor: Actor) {
        val role = when (actor.id) {
            createdBy.id -> "creator"
            submittedBy?.id -> "submitter"
            else -> return
        }
        throw FourEyesViolationException("four-eyes: the approver must not be the deal's $role")
    }

    private fun requireState(expected: DealState, action: String) =
        check(state == expected) { "cannot $action a deal in state $state (expected $expected)" }

    private fun requireHuman(actor: Actor, action: String) {
        if (actor.type != ActorType.HUMAN) {
            throw ActorNotPermittedException("a ${actor.type} principal may not $action a treasury deal")
        }
    }

    private fun requireHumanOrSystem(actor: Actor, action: String) {
        if (actor.type != ActorType.HUMAN && actor.type != ActorType.SYSTEM) {
            throw ActorNotPermittedException("a ${actor.type} principal may not $action a treasury deal")
        }
    }

    private fun limitNote(check: LimitCheck) = LimitNote.format(check)

    companion object {
        const val CZK = "CZK"
        const val EUR = "EUR"
        val SUPPORTED_CURRENCIES = setOf(CZK, EUR)
        const val CNB_COUNTERPARTY_ID = "CNB"
        private val MAX_RATE = BigDecimal("100")
        private val HUNDRED = BigDecimal("100")
        private const val FX_RATE_SCALE = 6
        private val FX_RATE_LIMIT = BigDecimal("1000")
        private const val MAX_REFERENCE_LENGTH = 200
        private const val MAX_CHANNEL_LENGTH = 64
        const val MAX_LEGS = 8
        const val MAX_PERIODS_PER_DEAL = 4000L
        const val MAX_INPUTS_CHARS = 65_536

        /** The states [settle] accepts: CONFIRMED, plus BOOKED only when confirmation is not required. */
        fun settleableStates(confirmationRequired: Boolean): Set<DealState> =
            if (confirmationRequired) setOf(DealState.CONFIRMED) else setOf(DealState.BOOKED, DealState.CONFIRMED)

        /**
         * An FX spot consumes its counterparty's CZK limit (settlement risk on its CZK leg) only
         * while PENDING_APPROVAL, BOOKED or CONFIRMED: once both legs are exchanged nothing is left
         * at risk.
         * The repository's `exposure` query reads the same set, never a second literal list.
         */
        val FX_LIMIT_CONSUMING_STATES: Set<DealState> =
            setOf(DealState.PENDING_APPROVAL, DealState.BOOKED, DealState.CONFIRMED)

        /** The CZK leg of an FX spot: foreign amount x deal rate, half-up to 2 dp. */
        fun counterAmountOf(principal: BigDecimal, rate: BigDecimal): BigDecimal =
            RoundingPolicy.TREASURY_AMOUNT.round(principal.multiply(rate))

        /**
         * The single source of truth for "on book" (ADR-0315, treasury limit utilisation, #10896):
         * a deal in one of these states still consumes its counterparty's credit limit. Both the
         * booking-time [LimitCheck] (via [consumesLimit] / the repository's `exposure` query) and
         * the read-only limit-utilisation view MUST derive from this one set — duplicating it as a
         * second literal list anywhere else is exactly the divergence this constant exists to rule
         * out (a limit-utilisation view unable to disagree with the check that actually blocks
         * booking is worth nothing if it silently reads a different rule).
         */
        val LIMIT_CONSUMING_STATES: Set<DealState> =
            setOf(DealState.PENDING_APPROVAL, DealState.BOOKED, DealState.CONFIRMED, DealState.SETTLED)

        /**
         * A new draft. A human dealer or an AI agent may draft (ADR-0315 D3); a service account or
         * the system may not. An agent's draft must carry its rationale and the inputs it used
         * (ADR-0315 D10); a draft never consumes a limit and only a human can submit it.
         */
        @Suppress("LongParameterList")
        fun draft(
            id: UUID,
            product: ProductType,
            counterpartyId: String,
            currency: String,
            principal: BigDecimal,
            rate: BigDecimal,
            tradeDate: LocalDate,
            valueDate: LocalDate,
            maturityDate: LocalDate?,
            actor: Actor,
            at: Instant,
            rationale: String? = null,
            inputs: String? = null,
            fxSide: FxSide? = null,
            kind: DealKind = DealKind.ACTUAL,
            legs: List<LegSpec> = emptyList(),
            header: DealHeader = DealHeader.EMPTY,
        ): Deal {
            if (actor.type != ActorType.HUMAN && actor.type != ActorType.AI_AGENT) {
                throw ActorNotPermittedException("a ${actor.type} principal may not draft a treasury deal")
            }
            if (actor.type == ActorType.AI_AGENT) {
                require(!rationale.isNullOrBlank()) { "an agent-drafted deal must carry its rationale (ADR-0315 D10)" }
                require(!inputs.isNullOrBlank()) {
                    "an agent-drafted deal must carry the inputs it used (ADR-0315 D10)"
                }
            }
            require((product == ProductType.FX_SPOT) == (fxSide != null)) {
                "an FX side (buy/sell currencies) is given exactly for FX_SPOT"
            }
            val maturity = when {
                product == ProductType.FX_SPOT -> valueDate
                product.isCnbFacility -> DealCalendars.overnight(valueDate, currency)
                maturityDate == null -> DealCalendars.overnight(valueDate, currency) // overnight
                else -> maturityDate
            }
            return Deal(
                id = id,
                product = product,
                counterpartyId = counterpartyId,
                currency = currency,
                principal = principal,
                rate = rate,
                tradeDate = tradeDate,
                valueDate = valueDate,
                maturityDate = maturity,
                state = DealState.DRAFT,
                createdBy = actor,
                createdAt = at,
                updatedAt = at,
                rationale = rationale,
                inputs = inputs,
                fx = fxSide?.let { FxTerms(it, counterAmountOf(principal, rate)) },
                history = listOf(DealTransition(from = null, to = DealState.DRAFT, actor = actor, at = at)),
                kind = kind,
                legs = legs,
                header = header,
            ).let { d -> if (product.legacy && legs.isEmpty()) d.copy(legs = LegacyLegs.derive(d)) else d }
        }
    }
}

/** A senior approver's override of a limit breach, bounded to the exposure it was granted for (ADR-0315 D4). */
data class LimitOverride(
    val by: Actor,
    val reason: String,
    val at: Instant,
    val coversExposureUpTo: BigDecimal,
    val limitAtOverride: BigDecimal,
)
