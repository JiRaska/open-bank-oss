// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.libs.testing.trace

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class TraceContractTest {
    @Test
    fun `asserts span topology attributes status and duration without exposing attribute values`() {
        val exporter = RecordingSpanExporter()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val parent = provider.get("test").spanBuilder("journey.checkout").startSpan()
        val child = provider.get("test")
            .spanBuilder("payment.authorize")
            .setParent(io.opentelemetry.context.Context.current().with(parent))
            .startSpan()
        child.setAllAttributes(
            Attributes.builder()
                .put("openbank.journey.id", "customer-fixture-never-in-output")
                .build(),
        )
        child.end()
        parent.end()

        exporter.contract()
            .requiresSpan("journey.checkout")
            .requiresSpan("payment.authorize")
            .requiresAttribute("payment.authorize", "openbank.journey.id")
            .requiresSameTrace("journey.checkout", "payment.authorize")
            .hasNoErrorSpan()
            .spanCompletesWithin("payment.authorize", Duration.ofSeconds(1))

        provider.close()
    }

    @Test
    fun `failure message does not include sensitive attribute value or trace id`() {
        val exporter = RecordingSpanExporter()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val span = provider.get("test").spanBuilder("payment.authorize").startSpan()
        span.setAttribute("openbank.secret-shaped-fixture", "do-not-print-me")
        span.setStatus(StatusCode.ERROR)
        span.end()

        assertThatThrownBy { exporter.contract().hasNoErrorSpan() }
            .hasMessageNotContaining("do-not-print-me")
            .hasMessageNotContaining(span.spanContext.traceId)

        provider.close()
    }

    @Test
    fun `split-trace failure message does not include either trace id`() {
        val exporter = RecordingSpanExporter()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val request = provider.get("test").spanBuilder("POST /payments").setNoParent().startSpan()
        request.end()
        val write = provider.get("test").spanBuilder("INSERT payments").setNoParent().startSpan()
        write.end()

        assertThatThrownBy { exporter.contract().requiresSameTrace("POST /payments", "INSERT payments") }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("expected named spans to share one trace")
            .hasMessageNotContaining(request.spanContext.traceId)
            .hasMessageNotContaining(write.spanContext.traceId)

        provider.close()
    }

    @Test
    fun `evidence marker requires a successful assertion and bounded public id`() {
        assertThatThrownBy { TraceContract.from(emptyList()).verifiedAs("agent-run") }
            .isInstanceOf(IllegalArgumentException::class.java)

        val exporter = RecordingSpanExporter()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        provider.get("test").spanBuilder("agent.run").startSpan().end()

        assertThatThrownBy {
            exporter.contract().requiresSpan("agent.run").verifiedAs("Customer supplied / trace id")
        }.isInstanceOf(IllegalArgumentException::class.java)

        provider.close()
    }
}
