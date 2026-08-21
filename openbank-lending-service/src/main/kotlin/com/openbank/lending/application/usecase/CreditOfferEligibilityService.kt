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
    private val policy: CreditOfferPolicy = CreditOfferPolicy.V1,
) {
    suspend fun evaluate(partyId: UUID): CreditOfferDecision {
        val hasConsent = failClosed("consent", null) { consent.hasCreditOffersConsent(partyId) }
            ?: return CreditOfferDecision.Suppressed(policy.version, CreditOfferSuppressionCode.SIGNALS_UNAVAILABLE)
        if (!hasConsent) {
            return CreditOfferDecision.Suppressed(policy.version, CreditOfferSuppressionCode.CONSENT_ABSENT)
        }
        val signals = failClosed("distress-signals", UNREADABLE) { distress.signalsFor(partyId) }
        return CreditOfferEligibility.evaluate(true, signals, Instant.now(clock), policy)
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
