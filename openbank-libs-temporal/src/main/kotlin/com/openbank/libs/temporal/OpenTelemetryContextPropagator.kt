// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.temporal

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import io.temporal.api.common.v1.Payload
import io.temporal.common.context.ContextPropagator
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter

/**
 * Carries the W3C trace context (`traceparent`, `tracestate`) from whoever starts a workflow into
 * the workflow and every activity it runs, so an activity span shares the caller's trace id.
 *
 * Without this the trace stops at the Temporal boundary: the workflow task and each activity run on
 * worker threads that have never seen the request, so everything they do lands in traces of its own
 * and the request that started a payment cannot be followed into its activities.
 *
 * **Why a [ContextPropagator] and not the `temporal-opentracing` interceptors.** The propagator is
 * applied by the SDK on both sides from `WorkflowClientOptions`: the client captures the context
 * when a workflow is started or signalled, and every `WorkerFactory` built from that client
 * restores it around workflow and activity execution. So one registration in
 * [TemporalClientProducer] covers the ~19 service-local worker registrars with no edit to any of
 * them, and it needs no new dependency (OpenTracing and its OpenTelemetry shim are not on the
 * fleet's verified classpath). The cost: no dedicated Temporal spans (StartWorkflow / RunActivity),
 * only context continuity. The activity's own spans, and anything it calls, join the caller's trace.
 *
 * The OpenTelemetry API is `compileOnly` here; a service without it on the classpath never loads
 * this class because [TemporalClientProducer] only touches it inside [install].
 */
class OpenTelemetryContextPropagator : ContextPropagator {

    private val converter: DataConverter = DefaultDataConverter.newDefaultInstance()

    override fun getName(): String = NAME

    /** Snapshot of the calling thread's trace context; empty when there is none. */
    override fun getCurrentContext(): Any {
        val carrier = linkedMapOf<String, String>()
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, MapSetter)
        return carrier
    }

    override fun serializeContext(context: Any): Map<String, Payload> =
        asCarrier(context).mapValues { (_, value) -> converter.toPayload(value).orElseThrow() }

    override fun deserializeContext(header: Map<String, Payload>): Any =
        header.mapValues { (_, payload) -> converter.fromPayload(payload, String::class.java, String::class.java) }

    /**
     * Make the restored trace context current on the worker thread.
     *
     * Temporal SDK 1.25 has no "unset" callback, so nothing tells us when the activity ends. Each
     * call therefore closes the scope the PREVIOUS call opened on this thread before opening its
     * own, and an empty context resets the thread to no trace. The trace left behind on an idle
     * pooled worker thread is the last one it ran; those threads only execute Temporal tasks, and
     * the SDK calls this before every one of them, so nothing reads the stale value.
     */
    override fun setCurrentContext(context: Any) {
        activeScope.get()?.close()
        val restored = W3CTraceContextPropagator.getInstance().extract(Context.root(), asCarrier(context), MapGetter)
        activeScope.set(restored.makeCurrent())
    }

    @Suppress("UNCHECKED_CAST")
    private fun asCarrier(context: Any): Map<String, String> = context as Map<String, String>

    private object MapSetter : TextMapSetter<MutableMap<String, String>> {
        override fun set(carrier: MutableMap<String, String>?, key: String, value: String) {
            carrier?.put(key, value)
        }
    }

    private object MapGetter : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys

        override fun get(carrier: Map<String, String>?, key: String): String? =
            if (key == "tracestate") TracestateInput.canonicalize(carrier?.get(key)) else carrier?.get(key)
    }

    companion object {
        const val NAME: String = "openbank-otel-trace-context"

        private val activeScope = ThreadLocal<Scope?>()

        /**
         * The propagators to register on a client, or none when the OpenTelemetry API is absent.
         * Probing here (not in the caller) keeps the class unloaded for a service without it.
         */
        fun install(): List<ContextPropagator> = try {
            Class.forName("io.opentelemetry.api.trace.Span")
            listOf(OpenTelemetryContextPropagator())
        } catch (_: ClassNotFoundException) {
            emptyList()
        }
    }
}

/**
 * Validate only when the delegate requests tracestate, after its traceparent check. Literal comma
 * scanning avoids feeding unbounded whitespace to the affected OTel delimiter regex (#14757).
 * The limits below are W3C member/key/value grammar limits, not a combined-header size cap.
 */
private object TracestateInput {
    private const val MAX_MEMBERS = 32
    private const val MAX_KEY = 256
    private const val MAX_VALUE = 256
    private const val MAX_TENANT_ID = 241
    private const val MAX_SYSTEM_ID = 14

    fun canonicalize(raw: String?): String? = if (raw.isNullOrEmpty()) raw else canonicalizePresent(raw)

    private fun canonicalizePresent(raw: String): String? {
        val keys = HashSet<String>()
        var cursor = 0
        var members = 0
        var needsNormalization = false
        while (true) {
            val comma = raw.indexOf(',', cursor)
            val end = if (comma < 0) raw.length else comma
            if (++members > MAX_MEMBERS) return null
            var last = end
            val first = skipLeadingOws(raw, cursor, last)
            while (last > first && isOws(raw[last - 1])) last--
            if (first == last) {
                needsNormalization = true
            } else {
                if (!validMember(raw, first, last, keys)) return null
                if (first != cursor || last != end) needsNormalization = true
            }
            if (comma < 0) break
            cursor = comma + 1
        }
        return if (needsNormalization) normalized(raw) else raw
    }

    private fun validMember(raw: String, first: Int, last: Int, keys: MutableSet<String>): Boolean {
        if (last - first > MAX_KEY + 1 + MAX_VALUE) return false
        val equals = raw.indexOf('=', first)
        if (equals <= first || equals >= last) return false
        val key = raw.substring(first, equals)
        if (!validKey(key) || !keys.add(key)) return false
        if (last - equals - 1 !in 1..MAX_VALUE) return false
        return (equals + 1 until last).all { raw[it] in ' '..'~' && raw[it] != '=' }
    }

    private fun validKey(key: String): Boolean {
        if (key.length !in 1..MAX_KEY) return false
        val at = key.indexOf('@')
        if (at < 0) return key[0] in 'a'..'z' && key.all(::isKeyChar)
        if (at !in 1..MAX_TENANT_ID || key.length - at - 1 !in 1..MAX_SYSTEM_ID) return false
        if (key[0] !in 'a'..'z' && key[0] !in '0'..'9') return false
        if (key[at + 1] !in 'a'..'z') return false
        return key.indices.all { it == at || isKeyChar(key[it]) }
    }

    private fun isKeyChar(char: Char): Boolean =
        char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-' || char == '*' || char == '/'

    private fun isOws(char: Char): Boolean = char == ' ' || char == '\t'

    private fun skipLeadingOws(raw: String, start: Int, end: Int): Int {
        var first = start
        while (first < end && isOws(raw[first])) first++
        return first
    }

    private fun normalized(raw: String): String {
        val result = StringBuilder()
        var cursor = 0
        while (true) {
            val comma = raw.indexOf(',', cursor)
            var last = if (comma < 0) raw.length else comma
            val first = skipLeadingOws(raw, cursor, last)
            while (last > first && isOws(raw[last - 1])) last--
            if (first < last) {
                if (result.isNotEmpty()) result.append(',')
                result.append(raw, first, last)
            }
            if (comma < 0) return result.toString()
            cursor = comma + 1
        }
    }
}
