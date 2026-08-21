// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.BorrowerDistressPort
import com.openbank.lending.application.port.out.CreditOffersConsentPort
import com.openbank.lending.domain.model.BorrowerDistressSignals
import com.openbank.lending.domain.model.CreditOfferDecision
import com.openbank.lending.domain.model.CreditOfferEligibility
import com.openbank.lending.domain.model.CreditOfferPolicy
import com.openbank.lending.domain.model.CreditOfferSuppressionCode
import com.openbank.lending.domain.model.OfferSurface
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The one place any credit offer is cleared for a customer (ADR-0269 rules 1 and 2).
 *
 * Everything upstream of an offer — the pre-approved read at the edge, a credit campaign step, an
 * L2 agent capability — asks this service and acts on nothing else. A second implementation of the
 * same question is how one caller ends up offering to someone the other would have suppressed.
 *
 * The decision is recomputed per call and never cached. Arrears and a returned direct debit change
 * the answer within a day, and a cached "eligible" outlives the fact it was based on.
 */
@ApplicationScoped
class CreditOfferEligibilityService(
    private val consent: CreditOffersConsentPort,
    private val distress: BorrowerDistressPort,
    private val clock: Clock,
) {
    /*
     * NOT a constructor parameter with a Kotlin default. A default generates a synthetic
     * constructor, Arc resolves the bean through it, and the whole bean then fails to resolve —
     * the same class of defect CustomerIntakeConfig documents, where a Kotlin fallback quietly
     * replaced injected configuration. Here it surfaced as an UnsatisfiedResolutionException that
     * took down every Quarkus test in the module, which is the loud version and the lucky one.
     *
     * When the thresholds need to move per environment this becomes a @ConfigProperty-driven bean,
     * not a defaulted parameter.
     */
    private val policy: CreditOfferPolicy = CreditOfferPolicy.V1

    /**
     * Evaluate the gate for [partyId].
     *
     * [surface] decides whether the consent half applies. It is not a convenience flag: ADR-0269's
     * whole shape is pull-only, and the two surfaces are exactly the two sides of that rule.
     *
     *  - [OfferSurface.PUSH] — the bank is about to say something unprompted (a pre-approved limit,
     *    a campaign step, an agent nudge). Requires `credit_offers` consent AND passes the distress
     *    floor.
     *  - [OfferSurface.PULL] — the customer asked (opened the financing screen, requested a quote).
     *    Consent is NOT what gates this: requiring an opt-in before answering a question the
     *    customer just asked would be a dark pattern in the other direction, and the ADR's rule is
     *    that the bank does not initiate, not that it refuses to answer.
     *
     * The distress floor applies to BOTH. A customer in arrears asking "what would this cost" must
     * not be handed a tailored instalment either; that is the case where the answer itself is the
     * harm, regardless of who started the conversation.
     */
    suspend fun evaluate(partyId: UUID, surface: OfferSurface): CreditOfferDecision {
        if (surface == OfferSurface.PUSH) {
            val hasConsent = failClosed("consent", null) { consent.hasCreditOffersConsent(partyId) }
                ?: return CreditOfferDecision.Suppressed(
                    policy.version,
                    CreditOfferSuppressionCode.SIGNALS_UNAVAILABLE,
                )
            if (!hasConsent) {
                return CreditOfferDecision.Suppressed(policy.version, CreditOfferSuppressionCode.CONSENT_ABSENT)
            }
        }
        val signals = failClosed("distress-signals", UNREADABLE) { distress.signalsFor(partyId) }
        return CreditOfferEligibility.evaluate(true, signals, Instant.now(clock), policy, surface)
    }

    /**
     * Run [block], and on ANY failure return [fallback] instead — the fail-closed path.
     *
     * `runCatching` rather than a `catch` clause, with cancellation rethrown explicitly: a bare
     * `runCatching` in a suspend function swallows `CancellationException` and turns a cancelled
     * coroutine into a fake business outcome, which is a defect this codebase has already paid for
     * once elsewhere.
     */
    private suspend fun <T> failClosed(source: String, fallback: T, block: suspend () -> T): T =
        runCatching { block() }.getOrElse { e ->
            if (e is CancellationException) throw e
            LOG.warn("credit-offer eligibility failing closed: $source unreadable (${e.javaClass.simpleName})")
            fallback
        }

    companion object {
        private val LOG = org.jboss.logging.Logger.getLogger(CreditOfferEligibilityService::class.java)

        /**
         * The value a failed read produces. Every distress flag is set to its *dangerous* value as
         * well as `complete = false`, so that even a future caller that forgets to honour the flag
         * still refuses rather than offers.
         */
        private val UNREADABLE = BorrowerDistressSignals(
            hasArrears = true,
            hasNegativeBalance = true,
            hasEnforcementOrder = true,
            hasInsolvencyProceeding = true,
            inHardshipArrangement = true,
            lastAffordabilityFailureAt = null,
            bufferDays = null,
            lastCreditContactAt = null,
            inputsChangedSinceLastContact = false,
            complete = false,
        )
    }
}
