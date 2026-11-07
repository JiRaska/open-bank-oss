// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.transfer.TransferDirection
import java.math.BigDecimal
import java.time.Duration

/**
 * Business and technical signals of the pension service (ADR-0334 observability, #12350).
 *
 * A port so the application layer stays framework-free; the Micrometer adapter lives in
 * `infrastructure/observability`. Every method has a no-op default and [NONE] is the default
 * collaborator of every service that takes one, so a hand-built service in a unit test needs no
 * registry and a missing metric never changes behaviour.
 *
 * Label discipline (cardinality + GDPR): labels carry CLOSED vocabularies only — enum names,
 * ISO currency, jurisdiction code, a registry partner id. Never a contract, party, payout or
 * payment id, never an IBAN, never free text.
 *
 * Names say what this service can ESTABLISH (#4348): a contribution is "received" when it is
 * stored, an incentive claim "submitted" when it is durably filed, a payout amount "confirmed"
 * when the participant confirmed it — never "paid out" for a payment this service only handed to
 * the payment hub.
 */
@Suppress("TooManyFunctions") // one port per signal family would scatter one observability seam
interface PensionMetrics {

    /** An aggregate changed status (`from` is null when it was created). Counted after the write returned. */
    fun transition(aggregate: String, from: String?, to: String) = Unit

    /** An onboarding application reached ACTIVATED; [elapsed] is created -> activated. */
    fun onboardingActivated(productLine: ProductLine, jurisdiction: String, elapsed: Duration) = Unit

    /** A transfer reached a terminal status; [amount] only for a COMPLETED one. */
    fun transferFinished(
        direction: TransferDirection,
        outcome: String,
        elapsed: Duration,
        amount: BigDecimal?,
        currency: String?,
    ) = Unit

    /** A payment was credited to a contract, recognised as a duplicate, or parked as unmatched. */
    fun contributionReceived(
        source: ContributionSource,
        channel: ContributionChannel,
        outcome: ReceiptKind,
        amount: BigDecimal,
        currency: String,
    ) = Unit

    /** An operator resolved a parked payment. */
    fun unmatchedResolved(resolution: UnmatchedResolution) = Unit

    /** State-incentive claims: [count] claims moved by [event]; [amount] where money is known. */
    fun incentiveClaims(event: IncentiveClaimEvent, count: Int, amount: BigDecimal?, currency: String?) = Unit

    /** State-contribution returns (§18 ZDPS): reported to the agency, confirmed, refused, settled. */
    fun stateContributionReturns(event: StateReturnEvent, count: Int, amount: BigDecimal?, currency: String?) = Unit

    /** A payout changed status; [amount] (gross) only on CONFIRMED. */
    fun payout(form: PayoutForm, status: String, amount: BigDecimal?, currency: String?) = Unit

    /** The outcome of a payout-account change request. */
    fun payoutAccountChange(outcome: String) = Unit

    /** One partner's answer to an annuity quote request. */
    fun annuityQuote(partnerId: String, outcome: AnnuityQuoteOutcome) = Unit

    /** A participant selected an annuity offer of [partnerId]. */
    fun annuitySelected(partnerId: String) = Unit

    /** A strategy election was stored, or refused. */
    fun strategyChange(outcome: String) = Unit

    /** The answer of sca-service to a challenge consume. */
    fun scaConsume(outcome: ScaConsumeOutcome) = Unit

    /** An HTTP request was answered from the idempotency store. */
    fun idempotencyReplay(method: String) = Unit

    /**
     * An optimistic-lock conflict on [aggregate]: `detected` at the write, `retried` when a retry
     * loop re-read and re-applied it.
     */
    fun optimisticLockConflict(aggregate: String, outcome: String) = Unit

    companion object {
        val NONE: PensionMetrics = object : PensionMetrics {}
    }
}

enum class ReceiptKind { CREDITED, DUPLICATE, UNMATCHED }

enum class UnmatchedResolution { ASSIGNED, RETURNED }

enum class IncentiveClaimEvent { SUBMITTED, RECEIVED, REJECTED, RETURNED }

enum class StateReturnEvent { REPORTED, CONFIRMED, REFUSED, SETTLED }

enum class AnnuityQuoteOutcome { OFFERED, NO_VALID_OFFER, FAILED }

/**
 * `CONSUMED`: sca-service spent the challenge for this binding. `REFUSED`: it said no (400/403/
 * 404/409/422, or a 2xx that is not a completed APPROVAL of this party). `INVALID`: refused here
 * before any call (no reserved binding, malformed id). `UNAVAILABLE`: it could not answer.
 */
enum class ScaConsumeOutcome { CONSUMED, REFUSED, INVALID, UNAVAILABLE }
