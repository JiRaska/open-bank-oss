// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.libs.testing.trace

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import java.util.Collections

/**
 * Assert the observable distributed-system contract of a test without exporting test data.
 *
 * A trace contract deliberately works on the SDK's already-exported [SpanData], so an integration,
 * E2E or synthetic test proves the same spans that an OTLP exporter would see. Failure messages name
 * only span names and attribute *keys*: test fixture values, trace ids and request payloads stay out
 * of CI output and Test Intelligence evidence.
 */
class TraceContract private constructor(private val spans: List<SpanData>) {
    private var successfulAssertions = 0

    fun requiresSpan(name: String): TraceContract {
        assertThat(spans.any { it.name == name })
            .describedAs("expected trace to contain span '%s'; observed span names only: %s", name, spanNames())
            .isTrue()
        successfulAssertions++
        return this
    }

    fun requiresAttribute(spanName: String, attributeKey: String): TraceContract {
        val matching = spans.filter { it.name == spanName }
        assertThat(matching.isNotEmpty())
            .describedAs("cannot require attribute key '%s': trace has no span '%s'", attributeKey, spanName)
            .isTrue()
        assertThat(matching.any { span -> span.attributes.asMap().keys.any { it.key == attributeKey } })
            .describedAs("expected span '%s' to carry attribute key '%s'", spanName, attributeKey)
            .isTrue()
        successfulAssertions++
        return this
    }

    fun requiresSameTrace(vararg spanNames: String): TraceContract {
        require(spanNames.isNotEmpty()) { "at least one span name is required" }
        spanNames.forEach(::requiresSpan)
        val traceIds = spans.filter { it.name in spanNames }.map { it.spanContext.traceId }.toSet()
        assertThat(traceIds)
            .describedAs("expected named spans to share one trace; trace ids are deliberately redacted")
            .hasSize(1)
        successfulAssertions++
        return this
    }

    fun hasNoErrorSpan(): TraceContract {
        val errorSpanNames = spans
            .filter { it.status.statusCode == StatusCode.ERROR }
            .map { it.name }
        assertThat(spans.none { it.status.statusCode == StatusCode.ERROR })
            .describedAs("expected no error span; error span names only: %s", errorSpanNames)
            .isTrue()
        successfulAssertions++
        return this
    }

    fun spanCompletesWithin(name: String, maximum: Duration): TraceContract {
        require(!maximum.isNegative) { "maximum duration must not be negative" }
        val matching = spans.filter { it.name == name }
        assertThat(matching.isNotEmpty()).describedAs("cannot assess duration: trace has no span '%s'", name).isTrue()
        assertThat(matching.all { Duration.ofNanos(it.endEpochNanos - it.startEpochNanos) <= maximum })
            .describedAs("expected span '%s' to complete within %s", name, maximum)
            .isTrue()
        successfulAssertions++
        return this
    }

    /**
     * Emit a bounded JUnit marker after this contract has proved at least one assertion.
     *
     * The canonical run-envelope collector turns this marker into executed `trace` evidence.
     * Only a caller-chosen low-entropy contract id leaves the JVM; trace ids, attribute values and
     * fixture data remain private. Call this last in the assertion chain so later assertions cannot
     * fail after evidence was emitted.
     */
    fun verifiedAs(contractId: String): TraceContract {
        require(successfulAssertions > 0) { "trace evidence requires at least one successful assertion" }
        require(TRACE_CONTRACT_ID.matches(contractId)) {
            "trace contract id must contain only lowercase letters, digits, dots, underscores or hyphens"
        }
        println("$TRACE_CONTRACT_MARKER$contractId")
        return this
    }

    private fun spanNames(): List<String> = spans.map { it.name }.distinct().sorted()

    companion object {
        private const val TRACE_CONTRACT_MARKER = "OPENBANK_TRACE_CONTRACT_V1:"
        private val TRACE_CONTRACT_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        fun from(spans: Collection<SpanData>): TraceContract = TraceContract(spans.toList())
    }
}

/** Thread-safe in-memory SDK exporter for a test's trace contract. */
class RecordingSpanExporter : SpanExporter {
    private val recorded = Collections.synchronizedList(mutableListOf<SpanData>())

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        recorded.addAll(spans)
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

    fun contract(): TraceContract = synchronized(recorded) { TraceContract.from(recorded) }
}
