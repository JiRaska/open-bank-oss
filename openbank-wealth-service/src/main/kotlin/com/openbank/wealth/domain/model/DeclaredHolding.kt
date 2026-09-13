// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Which way a holding moves net worth. Fixed BY the [HoldingType]; never supplied separately. */
enum class Side { ASSET, LIABILITY }

/**
 * What kind of thing the customer says they hold outside the bank (ADR-0301 D1).
 *
 * A CLOSED vocabulary on purpose. Every value here is a thing the platform can neither see nor
 * price, so the list is the whole contract between the customer's claim and any consumer of it;
 * a free-text type would make [DeclaredHolding] unqueryable and the ADR-0301 D7 aggregate signal
 * (counts and totals by type) uncomputable.
 *
 * [Side] is a PROPERTY of the type, not a field beside it. The first cut of this enum mixed the two
 * axes — one `EXTERNAL_LIABILITY` value sitting among seven asset classes, with the side recovered
 * by an equality check — which bought a single junk bucket for every debt a customer has and made
 * `MORTGAGE` and `PRIVATE_DEBT` indistinguishable. Giving `side` its own field would be worse
 * still: it would let a caller send REAL_ESTATE + LIABILITY, a combination that means nothing.
 * Deriving it from the type makes the nonsense unrepresentable.
 */
enum class HoldingType(val side: Side) {
    REAL_ESTATE(Side.ASSET),
    COLLECTIBLE(Side.ASSET),
    EXTERNAL_SECURITIES(Side.ASSET),
    EXTERNAL_COMPANY_STAKE(Side.ASSET),
    VEHICLE(Side.ASSET),
    EXTERNAL_DEPOSIT(Side.ASSET),
    PRIVATE_CLAIM(Side.ASSET),

    MORTGAGE(Side.LIABILITY),
    CONSUMER_CREDIT(Side.LIABILITY),
    PRIVATE_DEBT(Side.LIABILITY),
    OTHER_LIABILITY(Side.LIABILITY),
    ;

    /** Convenience for readers; the authority is [side], which the type itself fixes. */
    val isLiability: Boolean get() = side == Side.LIABILITY
}

/**
 * Where a [Valuation]'s number came from — and the reason this is an enum and not a boolean.
 *
 * ADR-0301 D2 requires the wire to label a customer-typed figure so no consumer can read it as a
 * bank position, and D7 bars any derived use beyond counts and totals. A two-state flag would put
 * "the customer typed it" and "an appraiser signed it" on the same footing as soon as a third case
 * appeared; the estate has already paid for a skipped/succeeded flag collapsing two outcomes into
 * one (`PushResult.skipped()` counting as delivered, ADR-0252 phase 0).
 */
enum class ValuationSource {
    /** The customer typed it. Never verified, never an input to pricing or credit. */
    CUSTOMER_DECLARED,

    /** A named appraiser attested it; [Valuation.appraiserReference] identifies the document. */
    EXPERT_APPRAISAL,

    /** A caller supplied a market figure and named its source. This service fetches no prices. */
    MARKET_REFERENCE,
}

/** Lifecycle of a declared holding. [WITHDRAWN] is terminal; [PLEDGED] is reversible by lending. */
enum class HoldingStatus { ACTIVE, PLEDGED, WITHDRAWN }

/**
 * A value assertion at a point in time. [valuedAt] is when the value was *established*, which is
 * not when the row was written — a 2019 appraisal declared today is `valuedAt = 2019`, and a
 * consumer deciding whether the figure is stale needs that date, not the write timestamp.
 */
data class Valuation(
    val amount: BigDecimal,
    val currency: String,
    val valuedAt: LocalDate,
    val source: ValuationSource,
    val appraiserReference: String? = null,
) {
    init {
        require(amount >= BigDecimal.ZERO) { "valuation amount must not be negative" }
        require(currency.length == CURRENCY_CODE_LENGTH) { "currency must be an ISO 4217 alpha-3 code" }
        require(source != ValuationSource.EXPERT_APPRAISAL || !appraiserReference.isNullOrBlank()) {
            "an EXPERT_APPRAISAL valuation must name its appraiser"
        }
    }

    private companion object {
        const val CURRENCY_CODE_LENGTH = 3
    }
}

/**
 * An asset or liability the customer declares and **the bank does not hold** (ADR-0301 D1).
 *
 * This is the one fact no existing aggregate could carry: party-service is identity,
 * account-service is a bank product, and lending's `Collateral` is a credit-risk input bound to a
 * `loanId` and summed into IFRS 9 LGD. A painting the customer merely *has* is none of those.
 *
 * Evidence is referenced, never stored: [documentIds] point at document-service (ADR-0161/0162),
 * so this service holds no binary content and inherits WORM storage and the PAdES seal.
 */
data class DeclaredHolding(
    val id: UUID,
    val ownerPartyId: UUID,
    val holdingType: HoldingType,
    val label: String,
    val valuation: Valuation,
    val ownershipShare: BigDecimal = BigDecimal.ONE,
    val externalReference: String? = null,
    val documentIds: List<UUID> = emptyList(),
    val status: HoldingStatus = HoldingStatus.ACTIVE,
    val pledgedToLoanId: UUID? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(label.isNotBlank()) { "label must not be blank" }
        require(ownershipShare > BigDecimal.ZERO && ownershipShare <= BigDecimal.ONE) {
            "ownershipShare must be in (0, 1]"
        }
        require((status == HoldingStatus.PLEDGED) == (pledgedToLoanId != null)) {
            "PLEDGED and pledgedToLoanId must agree"
        }
    }

    /** The owner's economic share of the valuation. Not a bank figure; see [ValuationSource]. */
    val attributableAmount: BigDecimal get() = valuation.amount.multiply(ownershipShare)

    /**
     * How old the asserted value is, in days, as of [asOf].
     *
     * Exposed because `valuedAt` alone makes a 2019 number and a 2026 number look identical to
     * every consumer that does not do the subtraction itself, and ADR-0301 D7 already permits a
     * segment rule to read totals. A segment populated from a figure someone typed once, years
     * ago, is the wrong-number failure this service is most likely to produce. There is no
     * per-class validity policy yet — deciding that property is stale after 12 months and a listed
     * security after a day is real domain work, tracked separately — so this reports the age and
     * refuses to pretend it can judge it.
     */
    fun valuationAgeDays(asOf: LocalDate): Long = ChronoUnit.DAYS.between(valuation.valuedAt, asOf)

    /**
     * Restate the value. Revaluing does NOT move any loan's ECL: lending copied the figure into
     * its own `Collateral` row at registration time and owns it from then on (ADR-0301 D3).
     */
    fun revalue(newValuation: Valuation, at: Instant): DeclaredHolding {
        check(status != HoldingStatus.WITHDRAWN) { "a withdrawn holding cannot be revalued" }
        return copy(valuation = newValuation, updatedAt = at)
    }

    /**
     * Remove the holding from the customer's picture.
     *
     * Refused while [HoldingStatus.PLEDGED], because lending is relying on it as registered
     * collateral. The release arrives as a lending event, never as a synchronous call into a
     * money-path service (ADR-0301 D3).
     */
    fun withdraw(at: Instant): DeclaredHolding {
        check(status != HoldingStatus.PLEDGED) {
            "holding is pledged to loan $pledgedToLoanId — lending must release it first"
        }
        check(status != HoldingStatus.WITHDRAWN) { "holding is already withdrawn" }
        return copy(status = HoldingStatus.WITHDRAWN, updatedAt = at)
    }

    /**
     * Mark as collateral for [loanId].
     *
     * NOTHING CALLS THIS TODAY. There is no REST route and no Kafka consumer: ADR-0301 D3 puts the
     * trigger in a lending event, and that consumer is issue #9773. The transition and its
     * invariant live here now because they are what [withdraw] refuses against, and because a rule
     * written at the same time as the state it guards is a rule; one bolted on later is a hope.
     * Said out loud rather than left to be discovered, because a method that looks wired and is not
     * is this estate's dominant defect (ADR-0253).
     */
    fun pledge(loanId: UUID, at: Instant): DeclaredHolding {
        check(status == HoldingStatus.ACTIVE) { "only an ACTIVE holding can be pledged" }
        return copy(status = HoldingStatus.PLEDGED, pledgedToLoanId = loanId, updatedAt = at)
    }

    /** Lending released the collateral; the holding becomes withdrawable again. Also uncalled — see [pledge]. */
    fun release(at: Instant): DeclaredHolding {
        check(status == HoldingStatus.PLEDGED) { "only a PLEDGED holding can be released" }
        return copy(status = HoldingStatus.ACTIVE, pledgedToLoanId = null, updatedAt = at)
    }
}
