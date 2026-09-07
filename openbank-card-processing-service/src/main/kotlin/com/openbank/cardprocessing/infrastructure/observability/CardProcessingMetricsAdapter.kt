// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.observability

import com.openbank.cardprocessing.application.port.out.CardProcessingMetricsPort
import com.openbank.cardprocessing.application.port.out.FraudScoringOutcome
import com.openbank.cardprocessing.application.port.out.PostingOutcome
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Micrometer adapter for [CardProcessingMetricsPort].
 *
 * Every series is named for **what can actually be established here**, never for an outcome this
 * service cannot observe. The precedent is the push adapter that counted a skipped send as a
 * delivery: `accepted` is knowable, `delivered` is not, and a name that overstates what happened
 * cannot be corrected by a dashboard (ADR-0252 phase 0, #4348).
 *
 *  - `openbank_card_processing_authorizations_total{approved,reason}` — the decision mix. The
 *    per-reason decline rate is the only signal separating a misconfigured control from a strict one.
 *  - `openbank_card_processing_presentments_total{fully_cleared}` — clearings applied.
 *  - `openbank_card_processing_hold_releases_total{kind}` — reversals and expiries, operationally
 *    different: a rising EXPIRY share means acquirers are not presenting, a rising REVERSAL share
 *    means they are cancelling.
 *  - `openbank_card_processing_ledger_postings_total{outcome}` — POSTED / SKIPPED_DISABLED / FAILED
 *    as three distinct values. **`SKIPPED_DISABLED > 0` is the alert that matters**: an adapter
 *    skipping and reporting nothing wrong is how card spend silently never reaches the books.
 *  - `openbank_card_processing_fraud_scores_total{outcome}` — the same three-way split, shadow only.
 */
@ApplicationScoped
class CardProcessingMetricsAdapter(private val registry: MeterRegistry?) : CardProcessingMetricsPort {

    // Explicit @Inject constructor: without it ArC sees two constructors, registers no bean, and
    // every injection point of this adapter is unsatisfied at build time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    override fun authorizationDecided(approved: Boolean, reason: String?) {
        val r = registry ?: return
        Counter.builder("openbank.card.processing.authorizations")
            .tag("service", SERVICE)
            .tag("approved", approved.toString())
            // An approval has no reason; the label still has to exist or the series changes shape
            // between the two cases and no single query covers both.
            .tag("reason", reason ?: REASON_NONE)
            .description("Card authorisation decisions by outcome and decline reason")
            .register(r)
            .increment()
    }

    override fun presentmentApplied(fullyCleared: Boolean) {
        val r = registry ?: return
        Counter.builder("openbank.card.processing.presentments")
            .tag("service", SERVICE)
            .tag("fully_cleared", fullyCleared.toString())
            .description("Clearing presentments applied to card authorisations")
            .register(r)
            .increment()
    }

    override fun holdReleased(kind: String) {
        val r = registry ?: return
        Counter.builder("openbank.card.processing.hold.releases")
            .tag("service", SERVICE)
            .tag("kind", kind)
            .description("Card holds released without a presentment (reversal or expiry)")
            .register(r)
            .increment()
    }

    override fun ledgerPosting(outcome: PostingOutcome) {
        val r = registry ?: return
        Counter.builder("openbank.card.processing.ledger.postings")
            .tag("service", SERVICE)
            .tag("outcome", outcome.name)
            .description("Attempts to post cleared card spend to the books, by outcome")
            .register(r)
            .increment()
    }

    override fun fraudScoring(outcome: FraudScoringOutcome) {
        val r = registry ?: return
        Counter.builder("openbank.card.processing.fraud.scores")
            .tag("service", SERVICE)
            .tag("outcome", outcome.name)
            .description("Shadow fraud scoring attempts for card authorisations, by outcome")
            .register(r)
            .increment()
    }

    private companion object {
        const val SERVICE = "card-processing"
        const val REASON_NONE = "none"
    }
}
