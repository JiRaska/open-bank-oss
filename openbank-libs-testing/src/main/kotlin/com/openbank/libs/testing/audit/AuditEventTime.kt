// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.audit

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant

/**
 * The rule `openbank-audit-service`'s `AuditConsumer` applies to a domain-event payload, expressed
 * once so a producer's own test can assert what its event will become in the audit trail (#3914).
 *
 * Why this lives in a shared module rather than as a copy per service: the thing under test is an
 * agreement between two artefacts in different Gradle modules, and the repo has already paid for
 * letting such an agreement be maintained as two hand-kept copies — a second copy moves with the
 * first and keeps passing against a contract neither side honours. There is exactly one copy of the
 * rule here; if `AuditConsumer.eventTime` changes, every producer test changes with it.
 *
 * The rule, verbatim from `AuditConsumer.eventTime`:
 *  - read `occurredAt` and ONLY `occurredAt` — `at` and `timestamp` are deliberately not accepted,
 *    because a second accepted spelling is a second silent path (#3907);
 *  - parse it as an ISO-8601 [Instant]; an unparseable value counts as absent.
 *
 * Absent or unparseable ⇒ the audit row stores the CONSUMER's clock and is flagged
 * [Source.INGEST]; present and parseable ⇒ the producer's own instant, flagged [Source.EVENT].
 *
 * What this helper can and cannot prove. It proves the payload a producer really writes carries an
 * event time the consumer will accept — the producer half of the path. It does not run Kafka or the
 * consumer, so it cannot prove the topic wiring; that is established separately (the publisher emits
 * `entry.payload` verbatim, the topic is in `audit-events-in`, dispatch is enabled) and does not
 * change per payload.
 */
object AuditEventTime {

    /** Which clock the audit row's `occurred_at` will hold — mirrors `audit`'s `OccurredAtSource`. */
    enum class Source { EVENT, INGEST }

    private val mapper = ObjectMapper()

    /** The producer's own event time, or null when the payload carries none the consumer accepts. */
    fun eventTimeOf(payloadJson: String): Instant? {
        val raw = mapper.readTree(payloadJson)["occurredAt"]?.asText() ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    /** How the audit trail will source this payload's `occurred_at`. */
    fun classify(payloadJson: String): Source = if (eventTimeOf(payloadJson) == null) Source.INGEST else Source.EVENT

    /**
     * Assert the payload lands in the audit trail as [Source.EVENT] carrying [expected].
     *
     * Both halves matter and neither implies the other: a payload can carry a parseable
     * `occurredAt` that is the wrong instant (a blanket `Instant.now()` taken at serialisation
     * rather than the instant the business event happened), which is worse than an honest
     * [Source.INGEST] because it is indistinguishable from a real one.
     */
    fun assertRecordedAsEventTime(payloadJson: String, expected: Instant) {
        assertThat(classify(payloadJson))
            .describedAs("audit occurred_at_source for payload %s", payloadJson)
            .isEqualTo(Source.EVENT)
        assertThat(eventTimeOf(payloadJson))
            .describedAs("audit occurred_at for payload %s", payloadJson)
            .isEqualTo(expected)
    }
}
