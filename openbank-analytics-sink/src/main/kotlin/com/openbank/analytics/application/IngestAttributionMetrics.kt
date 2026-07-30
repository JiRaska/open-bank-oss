// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.openbank.libs.analytics.AnalyticsEnvelope
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Makes an unattributable ingest **visible** (issue #2598, ask 2).
 *
 * Half of the #2598 defect was the classifier; the other half — the half that let it survive — is
 * that losing attribution cost nothing observable. The row landed in bronze, the consumer stayed
 * healthy and the pipeline reported success, so the broken state was indistinguishable from the
 * healthy one and no alert could ever have fired. Fixing only the classifier would leave that
 * property intact and the next producer to publish an unrecognised shape would be equally silent.
 *
 * So an event that still cannot be attributed after every fallback is **counted and logged**:
 *
 *  - `openbank_analytics_unattributed_total{field="aggregate_type"|"event_type"|"source_service"}`
 *    — a Prometheus counter that is flat at zero in a healthy pipeline, so `increase(...) > 0` is
 *    a usable alert expression rather than a threshold someone has to guess.
 *  - one WARN per affected event naming the topic and the fields that stayed unknown, so the
 *    producer is identifiable from logs without a ClickHouse query.
 *
 * Deliberately NOT a quarantine: the row still goes to bronze. The event is real and its payload
 * is intact, and diverting it to the dead-letter table would turn a partial-attribution problem
 * into actual data loss. Counted-and-kept is the honest outcome; counted-and-alertable is what
 * stops it recurring silently.
 */
@ApplicationScoped
class IngestAttributionMetrics {

    @Inject lateinit var registry: MeterRegistry

    private val log = Logger.getLogger(IngestAttributionMetrics::class.java)

    /**
     * Records the attribution outcome for one envelope. Returns the fields that stayed unknown
     * (empty = fully attributed) so callers and tests can assert on it directly.
     */
    fun record(envelope: AnalyticsEnvelope, topic: String?): Set<String> {
        val unresolved = buildSet {
            if (envelope.aggregateType == UNKNOWN) add(FIELD_AGGREGATE_TYPE)
            if (envelope.eventType == UNKNOWN) add(FIELD_EVENT_TYPE)
            if (envelope.sourceService == UNKNOWN_SERVICE) add(FIELD_SOURCE_SERVICE)
        }
        if (unresolved.isEmpty()) return emptySet()
        for (field in unresolved) {
            registry.counter("openbank_analytics_unattributed_total", "field", field).increment()
        }
        log.warnf(
            "Unattributed analytics event: topic=%s eventId=%s unresolved=%s — " +
                "the row is kept in bronze but cannot be filed under a domain (#2598)",
            topic ?: "unknown",
            envelope.eventId,
            unresolved.sorted().joinToString(","),
        )
        return unresolved
    }

    companion object {
        const val UNKNOWN: String = "UNKNOWN"
        const val UNKNOWN_SERVICE: String = "unknown"
        const val FIELD_AGGREGATE_TYPE: String = "aggregate_type"
        const val FIELD_EVENT_TYPE: String = "event_type"
        const val FIELD_SOURCE_SERVICE: String = "source_service"
    }
}
