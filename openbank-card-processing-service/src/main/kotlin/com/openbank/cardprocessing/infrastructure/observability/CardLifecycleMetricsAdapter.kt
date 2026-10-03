// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.observability

import com.openbank.cardprocessing.application.port.out.CardLifecycleMetricsPort
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Micrometer adapter for [CardLifecycleMetricsPort].
 *
 * Every counter carries its refusal reason as a LABEL rather than being split into success and
 * failure series. A refusal rate is the question these paths are actually asked — "is the binding
 * working?" — and it cannot be computed from two series that may be scraped at different moments.
 *
 *  - `openbank_card_token_provisions_total{scheme,refusal}` — `refusal=none` is a provisioned token.
 *    `refusal=SCHEME_UNAVAILABLE` on every call is what a capability with no bound vendor adapter
 *    looks like, and it must be visible rather than inferred from an empty success series.
 *  - `openbank_card_token_status_changes_total{scheme,status,refusal}`
 *  - `openbank_card_token_reads_total{source}` — NETWORK or LOCAL_MIRROR. **A rising LOCAL_MIRROR
 *    share is the alert-worthy one**: the screens still render, so nothing else about a degraded
 *    read is observable from outside.
 *  - `openbank_card_disputes_opened_total{scheme,refusal}` — `scheme=none` marks the refusals that
 *    happened before any network was asked, so a dashboard cannot blame a network that was not called.
 *  - `openbank_card_dispute_evidence_total{refusal}`
 */
@ApplicationScoped
class CardLifecycleMetricsAdapter(private val registry: MeterRegistry?) : CardLifecycleMetricsPort {

    // Explicit @Inject constructor, as on the sibling adapter: without it ArC sees two constructors,
    // registers no bean, and every injection point is unsatisfied at build time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    override fun tokenProvisioned(scheme: String, refusal: String?) {
        val r = registry ?: return
        Counter.builder("openbank.card.token.provisions")
            .tag("service", SERVICE)
            .tag("scheme", scheme)
            .tag("refusal", refusal ?: NONE)
            .description("Network-token provisioning attempts by scheme and refusal reason")
            .register(r)
            .increment()
    }

    override fun tokenStatusChanged(scheme: String, status: String, refusal: String?) {
        val r = registry ?: return
        Counter.builder("openbank.card.token.status.changes")
            .tag("service", SERVICE)
            .tag("scheme", scheme)
            .tag("status", status)
            .tag("refusal", refusal ?: NONE)
            .description("Network-token status changes by target status and refusal reason")
            .register(r)
            .increment()
    }

    override fun tokenListServed(source: String) {
        val r = registry ?: return
        Counter.builder("openbank.card.token.reads")
            .tag("service", SERVICE)
            .tag("source", source)
            .description("Token list reads by provenance — the network, or this bank's mirror")
            .register(r)
            .increment()
    }

    override fun disputeOpened(scheme: String, refusal: String?) {
        val r = registry ?: return
        Counter.builder("openbank.card.disputes.opened")
            .tag("service", SERVICE)
            .tag("scheme", scheme)
            .tag("refusal", refusal ?: NONE)
            .description("Chargeback cases opened, by scheme and refusal reason")
            .register(r)
            .increment()
    }

    override fun disputeEvidenceSubmitted(refusal: String?) {
        val r = registry ?: return
        Counter.builder("openbank.card.dispute.evidence")
            .tag("service", SERVICE)
            .tag("refusal", refusal ?: NONE)
            .description("Evidence submissions against chargeback cases, by refusal reason")
            .register(r)
            .increment()
    }

    private companion object {
        // The same value the sibling adapter tags with: one service label, or a dashboard filter that
        // covers the money path silently drops these series.
        const val SERVICE = "card-processing"

        /** The label value for "no refusal" — the series must keep one shape across both cases. */
        const val NONE = "none"
    }
}
