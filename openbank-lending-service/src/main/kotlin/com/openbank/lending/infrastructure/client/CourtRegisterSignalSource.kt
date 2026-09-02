// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.openbank.lending.domain.model.CourtRegisterSignalState
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where ADR-0269's enforcement and insolvency markers would come from — and, today, the statement
 * that they come from nowhere (#6646).
 *
 * ## Why this class exists rather than two `false` literals
 *
 * ADR-0269 rule 2 lists "an enforcement or insolvency marker" among the facts that suppress a
 * credit offer. `CreditOfferSuppressionCode` carries both codes, the decision table routes them,
 * and admin surfaces render them — but no service in this deployment ingests a court register, so
 * neither code has ever fired from real data and neither can. Until #6646 the adapter expressed
 * that with `hasInsolvencyProceeding = false`, which is the same value a genuine "we checked the
 * register and this customer is clear" produces. Under that encoding a permanently empty set of
 * INSOLVENCY suppressions reads as *no customer is insolvent* rather than *we never looked*, and
 * no error, metric or log anywhere distinguishes the two.
 *
 * Nothing here invents a feed. Which insolvency and enforcement registers a bank consults is a
 * jurisdiction-specific procurement decision, not something to stub: a stub would answer, and an
 * answer is exactly what must not be fabricated for a fact that decides whether the bank markets
 * credit to someone in distress. What this class does instead is make the absence **loud** and
 * **measurable**.
 *
 * ## What it changes, and what it deliberately does not
 *
 * The offer decision is unchanged: [CourtRegisterSignalState.NOT_CONFIGURED] does not suppress.
 * That trade was made in the adapter's own docs and is kept — a state that suppressed would refuse
 * every offer forever and be indistinguishable from the feature being off. Three things change:
 *
 *  - the state is its own enum value, so no caller can read "unconfigured" as "clear";
 *  - the process says so at **boot**, once, at `warn`. `@Startup` and not a lazy
 *    `@ApplicationScoped` `init {}`: a lazy bean's guard runs on first use, which for a
 *    config-sanity warning can be long after the deploy is green, or never;
 *  - `openbank_lending_court_register_feed_configured{signal=...}` exports 0/1, so the alert that
 *    matters — *this control has no input* — is expressible against a metric instead of against
 *    the absence of one.
 *
 * When a register is procured, this becomes a port with a real adapter behind it and the gauge
 * flips to 1; the meaning of every existing dashboard and alert survives that change unedited,
 * which is the point of naming the gauge for whether a feed is CONFIGURED rather than for whether
 * findings were returned.
 */
@Startup
@ApplicationScoped
class CourtRegisterSignalSource(meters: MeterRegistry) {

    /** Strongly held so the gauges are not collected out from under the registry. */
    private val gaugeValues = mutableListOf<AtomicInteger>()

    init {
        UNCONFIGURED_SIGNALS.forEach { signal ->
            LOG.warnf(
                "ADR-0269 distress floor: no %s register is configured in this deployment, so the " +
                    "%s suppression code CANNOT fire. An empty set of %s suppressions means the " +
                    "control has no input — not that no customer is affected (#6646).",
                signal,
                signal.uppercase(),
                signal.uppercase(),
            )
            val value = AtomicInteger(0)
            gaugeValues += value
            meters.gauge(FEED_CONFIGURED_GAUGE, listOf(Tag.of("signal", signal)), value)
        }
    }

    /**
     * Enforcement (execution) register state for any party.
     *
     * Takes no party id on purpose: there is nothing to look a party up in. A signature that
     * accepted one would imply a lookup happened, which is the same false impression in the type
     * system that `false` made in the data.
     */
    fun enforcementState(): CourtRegisterSignalState = CourtRegisterSignalState.NOT_CONFIGURED

    /** Insolvency register state for any party. See [enforcementState]. */
    fun insolvencyState(): CourtRegisterSignalState = CourtRegisterSignalState.NOT_CONFIGURED

    companion object {
        const val FEED_CONFIGURED_GAUGE = "openbank_lending_court_register_feed_configured"
        val UNCONFIGURED_SIGNALS = listOf("enforcement", "insolvency")
        private val LOG: Logger = Logger.getLogger(CourtRegisterSignalSource::class.java)
    }
}
