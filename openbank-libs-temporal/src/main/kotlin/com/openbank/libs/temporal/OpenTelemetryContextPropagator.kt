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

        override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
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
