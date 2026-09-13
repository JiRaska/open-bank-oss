// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.AnalyticsSink
import com.openbank.analytics.application.port.out.DeadLetterRecord
import com.openbank.analytics.application.port.out.DeadLetterSink
import com.openbank.libs.analytics.AnalyticsEnvelope
import com.openbank.libs.messaging.EventRetry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The ack/nack contract of [AnalyticsConsumer] (#5698/#5745).
 *
 * This consumer takes MANUAL acknowledgement (its signature is `Message<String>`), and it used to ack
 * in a `finally` — so a ClickHouse write that failed was logged, quarantined to a WARN line, and
 * ACKED. Bronze is the ≥10-year log of record and the only extraction path, so that is a permanent
 * hole that nothing reports: consumer lag is zero because the message was acked, and the row simply
 * is not there.
 *
 * The split under test: a sink failure is retried and then NACKed; an un-projectable payload is still
 * quarantined and ACKed, because replaying it fails identically forever.
 */
/** A named transient failure, so the fakes below never `throw RuntimeException` (detekt). */
private class SinkUnavailable(message: String) : RuntimeException(message)

class AnalyticsConsumerFailureTest {

    private val mapper = ObjectMapper()

    private class CapturingDlq : DeadLetterSink {
        val records = mutableListOf<DeadLetterRecord>()
        override suspend fun quarantine(record: DeadLetterRecord) {
            records += record
        }
    }

    private class FailingSink(private val failures: Int) : AnalyticsSink {
        var attempts = 0
        val written = mutableListOf<AnalyticsEnvelope>()
        override suspend fun write(envelope: AnalyticsEnvelope) {
            attempts++
            if (attempts <= failures) throw SinkUnavailable("clickhouse unreachable")
            written += envelope
        }
    }

    /** A [Message] that records which acknowledgement the consumer actually chose. */
    private class Outcome(payload: String) {
        var acked = false
        var nacked: Throwable? = null
        val message: Message<String> = Message.of(
            payload,
            {
                acked = true
                CompletableFuture.completedFuture(null) as CompletionStage<Void>
            },
            { t: Throwable ->
                nacked = t
                CompletableFuture.completedFuture(null) as CompletionStage<Void>
            },
        )
    }

    private fun consumerWith(sink: AnalyticsSink, dlq: DeadLetterSink) = AnalyticsConsumer().apply {
        this.sink = sink
        this.deadLetters = dlq
        objectMapper = mapper
        clock = Clock.systemUTC()
    }

    private val goodPayload = """{"eventId":"11111111-1111-1111-1111-111111111111","aggregateType":"ACCOUNT",""" +
        """"aggregateId":"acc-1","eventType":"account.opened","occurredAt":"2026-01-01T00:00:00Z"}"""

    /**
     * The assertion that matters: a persistent sink failure must NOT ack. Against the pre-fix
     * consumer this fails on both counts — the `finally` acked and nothing was ever nacked.
     */
    @Test
    fun `a persistent sink failure is retried and then NACKED, never acked`(): Unit = runBlocking {
        val sink = FailingSink(failures = Int.MAX_VALUE)
        val dlq = CapturingDlq()
        val outcome = Outcome(goodPayload)

        consumerWith(sink, dlq).consume(outcome.message)

        assertThat(sink.attempts).isEqualTo(EventRetry.DEFAULT_MAX_ATTEMPTS)
        assertThat(outcome.nacked).isInstanceOf(SinkUnavailable::class.java)
        assertThat(outcome.acked).isFalse()
        // A transient dependency failure is NOT a poison pill: quarantining it here would lose the
        // row just as thoroughly, since the only DLQ binding is a log line.
        assertThat(dlq.records).isEmpty()
    }

    @Test
    fun `a transient sink failure is retried, written, and acked`(): Unit = runBlocking {
        val sink = FailingSink(failures = 1)
        val dlq = CapturingDlq()
        val outcome = Outcome(goodPayload)

        consumerWith(sink, dlq).consume(outcome.message)

        assertThat(sink.attempts).isEqualTo(2)
        assertThat(sink.written).hasSize(1)
        assertThat(outcome.acked).isTrue()
        assertThat(outcome.nacked).isNull()
    }

    /**
     * The other half of the split, unchanged by the fix and deliberately so. A blanket rethrow here
     * would wedge the partition on a payload that can never succeed.
     */
    @Test
    fun `an un-projectable payload is quarantined and still ACKED, and never reaches the sink`(): Unit = runBlocking {
        val sink = FailingSink(failures = 0)
        val dlq = CapturingDlq()
        val outcome = Outcome("{ this is not valid json")

        consumerWith(sink, dlq).consume(outcome.message)

        assertThat(sink.attempts).isZero()
        assertThat(dlq.records).hasSize(1)
        assertThat(outcome.acked).isTrue()
        assertThat(outcome.nacked).isNull()
    }

    @Test
    fun `a healthy event is written once and acked`(): Unit = runBlocking {
        val sink = FailingSink(failures = 0)
        val dlq = CapturingDlq()
        val outcome = Outcome(goodPayload)

        consumerWith(sink, dlq).consume(outcome.message)

        assertThat(sink.written).hasSize(1)
        assertThat(dlq.records).isEmpty()
        assertThat(outcome.acked).isTrue()
    }
}
