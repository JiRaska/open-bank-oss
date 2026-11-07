// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter

/**
 * Captures the CURRENT span as a W3C `traceparent` string, for storage on an outbox row.
 *
 * The outbox decouples the business transaction from the broker send: the dispatcher runs on its
 * own scheduler thread with no request, so whatever trace it sits in is not the request's. Storing
 * the traceparent in the same transaction as the event lets the dispatcher re-attach the original
 * trace when it publishes.
 *
 * Returns `null` when there is no valid current span (no tracing configured, SDK disabled, or the
 * OpenTelemetry API is not on the classpath), so a service that has not wired tracing is unaffected.
 */
object TraceparentCapture {
    private const val VERSION = "00"

    fun current(): String? = try {
        format(Span.current().spanContext)
    } catch (_: NoClassDefFoundError) {
        // opentelemetry-api is compileOnly in this module: a service without it must still boot.
        null
    }

    /**
     * Rebuilds an OpenTelemetry [Context] from a stored traceparent, to parent the relay span on the
     * request that wrote the event. `null` for a missing, blank or malformed value.
     */
    fun parentContext(traceparent: String?): Context? {
        if (traceparent.isNullOrBlank()) return null
        return try {
            val extracted = W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), traceparent, SingleHeaderGetter)
            if (Span.fromContext(extracted).spanContext.isValid) extracted else null
        } catch (_: NoClassDefFoundError) {
            null
        }
    }

    private object SingleHeaderGetter : TextMapGetter<String> {
        override fun keys(carrier: String): Iterable<String> = listOf("traceparent")

        override fun get(carrier: String?, key: String): String? = if (key == "traceparent") carrier else null
    }

    internal fun format(context: SpanContext): String? =
        if (context.isValid) "$VERSION-${context.traceId}-${context.spanId}-${context.traceFlags.asHex()}" else null
}
