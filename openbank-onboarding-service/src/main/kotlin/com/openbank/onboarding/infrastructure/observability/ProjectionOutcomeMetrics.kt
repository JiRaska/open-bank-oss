// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.onboarding.infrastructure.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * The outcome of every message the onboarding projection consumes, per topic (#4353).
 *
 * `openbank_onboarding_projection_events_total{service="onboarding",topic,outcome}`
 *
 * The signal that was missing. `OnboardingEventConsumer` drops an unrecognised event on the
 * quiet path — `parse(...) ?: return` — with no log and no error, which is correct for a
 * read-model (a poison pill must not wedge the consumer group) and is also why a whole event
 * type could be discarded indefinitely without a trace. sca-service published 15
 * `DEVICE_ENROLLED` events that every consumer accepted and none projected, because the
 * discriminator was absent from the payload body; the topic had no lag, the pod was healthy,
 * the handler and its unit tests were correct, and nothing anywhere disagreed.
 *
 * An error rate could not have caught that — there were no errors. The alertable state is the
 * SUCCESS state: a topic delivering messages of which none are [Outcome.PROJECTED]. Hence
 * [Outcome.UNRECOGNISED] is its own value rather than being folded into a generic "handled",
 * the same reason `PushSendOutcome.SKIPPED` is not a flag shared with success.
 *
 * Suggested rule (per topic, over a window in which the topic delivered anything at all):
 *   sum by (topic) (rate(...{outcome="UNRECOGNISED"}[1h])) > 0
 *     and sum by (topic) (rate(...{outcome="PROJECTED"}[1h])) == 0
 *
 * Cardinality is bounded: three topics, three outcomes.
 *
 * Service-local [MeterRegistry], null-safe via [Instance] — same shape as
 * [OnboardingFunnelGauge] and notification-service's `PushMetricsAdapter` (ADR-0085 §2).
 */
@ApplicationScoped
class ProjectionOutcomeMetrics(private val registry: MeterRegistry?) {

    // Explicit @Inject constructor: without it ArC sees two constructors, registers no bean,
    // and OnboardingEventConsumer is left with an unsatisfied dependency at build time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    enum class Outcome {
        /** Parsed, recognised, and applied to the read model. */
        PROJECTED,

        /**
         * Well-formed JSON the parser returned no event for — an unknown or absent `eventType`,
         * or a required field that would not coerce. NOT an error, and NOT a success: this is
         * the state in which a producer and a consumer disagree about the wire format while
         * both report healthy.
         */
        UNRECOGNISED,

        /** Malformed payload, or the projection itself threw. Already logged at ERROR. */
        FAILED,
    }

    fun record(topic: String, outcome: Outcome) {
        registry?.let { r ->
            Counter.builder(METRIC)
                .tag("service", SERVICE)
                .tag("topic", topic)
                .tag("outcome", outcome.name)
                .description("Onboarding projection outcomes per consumed message")
                .register(r)
                .increment()
        }
    }

    companion object {
        private const val SERVICE = "onboarding"
        private const val METRIC = "openbank.onboarding.projection.events"
    }
}
