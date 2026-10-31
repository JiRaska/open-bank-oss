// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.temporal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenTelemetryContextPropagatorTest {

    private val traceId = "0af7651916cd43dd8448eb211c80319c"
    private val spanId = "b7ad6b7169203331"
    private val propagator = OpenTelemetryContextPropagator()

    private fun callerSpan() =
        Span.wrap(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()))

    @Test
    fun `the caller's trace survives serialize then deserialize then set on another thread`() {
        val sent = callerSpan().makeCurrent().use { propagator.getCurrentContext() }
        val onTheWire = propagator.serializeContext(sent)
        var seenOnWorker: String? = null

        val worker = Thread {
            propagator.setCurrentContext(propagator.deserializeContext(onTheWire))
            seenOnWorker = Span.current().spanContext.traceId
        }
        worker.start()
        worker.join()

        assertThat(onTheWire).containsKey("traceparent")
        assertThat(seenOnWorker).describedAs("trace id seen by the activity thread").isEqualTo(traceId)
    }

    @Test
    fun `a later task on the same thread replaces the earlier trace, and an empty one clears it`() {
        val sent = callerSpan().makeCurrent().use { propagator.getCurrentContext() }
        val empty = propagator.getCurrentContext()
        var afterFirst: Boolean? = null
        var afterEmpty: Boolean? = null

        val worker = Thread {
            propagator.setCurrentContext(sent)
            afterFirst = Span.current().spanContext.isValid
            propagator.setCurrentContext(empty)
            afterEmpty = Span.current().spanContext.isValid
        }
        worker.start()
        worker.join()

        assertThat(afterFirst).isTrue()
        assertThat(afterEmpty).describedAs("an activity started with no trace must not inherit the last one").isFalse()
        assertThat(Context.current()).describedAs("the calling thread is untouched").isEqualTo(Context.root())
    }

    @Test
    fun `a caller with no trace sends an empty context that restores to no trace`() {
        val sent = propagator.getCurrentContext()
        var valid: Boolean? = null

        val worker = Thread {
            propagator.setCurrentContext(propagator.deserializeContext(propagator.serializeContext(sent)))
            valid = Span.current().spanContext.isValid
        }
        worker.start()
        worker.join()

        assertThat(valid).isFalse()
    }

    @Test
    fun `the production client options register the propagator`() {
        val config = mockk<TemporalConfig>()
        every { config.namespace() } returns "openbank-test"

        val options = TemporalClientProducer(config, SimpleMeterRegistry()).workflowClientOptions()

        assertThat(options.contextPropagators.map { it.name }).contains(OpenTelemetryContextPropagator.NAME)
        assertThat(options.namespace).isEqualTo("openbank-test")
    }
}
