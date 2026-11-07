// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class TraceparentCaptureTest {

    private val traceId = "0af7651916cd43dd8448eb211c80319c"
    private val spanId = "b7ad6b7169203331"

    @Test
    fun `no current span captures nothing`() {
        assertThat(TraceparentCapture.current()).isNull()
    }

    @Test
    fun `a current sampled span is captured as a W3C traceparent`() {
        val context = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault())

        Span.wrap(context).makeCurrent().use {
            assertThat(TraceparentCapture.current()).isEqualTo("00-$traceId-$spanId-01")
        }
    }

    @Test
    fun `an unsampled span keeps its flags`() {
        val context = SpanContext.create(traceId, spanId, TraceFlags.getDefault(), TraceState.getDefault())

        assertThat(TraceparentCapture.format(context)).isEqualTo("00-$traceId-$spanId-00")
    }

    @Test
    fun `the traced entity hands its trace context to the dispatcher entry`() {
        val entity = PanacheOutboxEntityTraced().apply {
            eventId = UUID.randomUUID()
            aggregateId = UUID.randomUUID()
            eventType = "document.generated"
            payload = "{}"
            status = "PENDING"
            createdAt = Instant.EPOCH
            updatedAt = Instant.EPOCH
            traceContext = "00-$traceId-$spanId-01"
        }

        val entry = entity.toEntry()

        assertThat(entry.traceparent).isEqualTo("00-$traceId-$spanId-01")
        assertThat(OutboxKafkaHeaders.headersFor(entry)[OutboxKafkaHeaders.HEADER_TRACEPARENT])
            .isEqualTo("00-$traceId-$spanId-01")
    }

    @Test
    fun `capture stamps the current span and a row written outside any span stays null`() {
        val entity = PanacheOutboxEntityTraced()
        entity.capture()
        assertThat(entity.traceContext).isNull()

        val context = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault())
        Span.wrap(context).makeCurrent().use { entity.capture() }
        assertThat(entity.traceContext).isEqualTo("00-$traceId-$spanId-01")
    }

    @Test
    fun `a stored traceparent rebuilds a context carrying the same trace id`() {
        val parent = TraceparentCapture.parentContext("00-$traceId-$spanId-01")

        assertThat(parent).isNotNull()
        assertThat(Span.fromContext(parent!!).spanContext.traceId).isEqualTo(traceId)
        assertThat(Span.fromContext(parent).spanContext.spanId).isEqualTo(spanId)
    }

    @Test
    fun `missing blank or malformed traceparents yield no context`() {
        assertThat(TraceparentCapture.parentContext(null)).isNull()
        assertThat(TraceparentCapture.parentContext("  ")).isNull()
        assertThat(TraceparentCapture.parentContext("not-a-traceparent")).isNull()
        assertThat(TraceparentCapture.parentContext("00-00000000000000000000000000000000-0000000000000000-01")).isNull()
    }
}
