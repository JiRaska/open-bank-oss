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
import io.opentelemetry.context.propagation.TextMapGetter
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

    // Inspect the actual delegate boundary without timing or executing its vulnerable regex.
    // This is intentionally private reflection rather than a new production ABI/test hook.
    @Suppress("UNCHECKED_CAST")
    private fun extractionGetter(): TextMapGetter<Map<String, String>> {
        val type = Class.forName("${OpenTelemetryContextPropagator::class.java.name}\$MapGetter")
        val instance = type.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        return instance as TextMapGetter<Map<String, String>>
    }

    private fun parent(): String = "00-$traceId-$spanId-01"

    private fun extracted(carrier: Map<String, String>): SpanContext = try {
        propagator.setCurrentContext(carrier)
        Span.current().spanContext
    } finally {
        propagator.setCurrentContext(emptyMap<String, String>())
    }

    @Test
    fun `malformed long whitespace state never reaches the delegate but traceparent is unchanged`() {
        val badState = "vendor=" + " \t".repeat(4096) + "!"
        val carrier = mapOf("traceparent" to parent(), "tracestate" to badState)
        val getter = extractionGetter()

        assertThat(getter.get(carrier, "tracestate")).isNull()
        assertThat(getter.get(carrier, "traceparent")).isSameAs(carrier["traceparent"])
        val restored = extracted(carrier)
        assertThat(restored.traceId).isEqualTo(traceId)
        assertThat(restored.spanId).isEqualTo(spanId)
        assertThat(restored.isSampled).isTrue()
        assertThat(restored.isRemote).isTrue()
        assertThat(restored.traceState.isEmpty).isTrue()
    }

    @Test
    fun `legal long boundary whitespace and empty members are normalized before the delegate`() {
        val state = " \t".repeat(4096) + ",a=  opaque value,\t ,b=two " + "\t ".repeat(4096)
        val canonical = extractionGetter().get(mapOf("tracestate" to state), "tracestate")

        assertThat(canonical).isEqualTo("a=  opaque value,b=two")
        val restored = extracted(mapOf("traceparent" to parent(), "tracestate" to state))
        assertThat(restored.traceState["a"]).isEqualTo("  opaque value")
        assertThat(restored.traceState["b"]).isEqualTo("two")
        assertThat(restored.traceState.asMap().keys.toList()).containsExactly("a", "b")
    }

    @Test
    fun `canonical valid state above 512 characters retains identity values and order`() {
        val value = "x".repeat(256)
        val state = "a=$value,b=$value"
        assertThat(state.length).isGreaterThan(512)
        assertThat(extractionGetter().get(mapOf("tracestate" to state), "tracestate")).isSameAs(state)

        val restored = extracted(mapOf("traceparent" to parent(), "tracestate" to state))
        assertThat(restored.traceState["a"]).isEqualTo(value)
        assertThat(restored.traceState["b"]).isEqualTo(value)
        assertThat(restored.traceState.asMap().keys.toList()).containsExactly("a", "b")
    }

    @Test
    fun `maximum simple and tenant keys and 32 entries remain valid`() {
        val simple = "a" + "z".repeat(255)
        val tenant = "9" + "z".repeat(240) + "@" + "s".repeat(14)
        val state = "$simple=v,$tenant=w"
        val getter = extractionGetter()
        assertThat(getter.get(mapOf("tracestate" to state), "tracestate")).isSameAs(state)
        val thirtyTwo = (0 until 32).joinToString(",") { "v$it=x" }
        assertThat(getter.get(mapOf("tracestate" to thirtyTwo), "tracestate")).isSameAs(thirtyTwo)
        assertThat(getter.get(mapOf("tracestate" to "$thirtyTwo,extra=x"), "tracestate")).isNull()
    }

    @Test
    fun `malformed grammar and duplicate keys are dropped as state without changing other headers`() {
        val invalid = listOf(
            "A=x", "1bad=x", "a@=x", "a@@b=x", "a=b=c", "a=", "a=b\tc", "a=b\nc",
            "a=b\u0000c", "a=b,a=c", "a=b,good=", "a=" + "x".repeat(257),
            "a".repeat(257) + "=x", "a".repeat(242) + "@s=x", "a@" + "s".repeat(15) + "=x",
        )
        val getter = extractionGetter()
        for (state in invalid) {
            val carrier = mapOf("tracestate" to state, "traceparent" to parent(), "other" to "untouched")
            assertThat(getter.get(carrier, "tracestate")).describedAs("malformed state").isNull()
            assertThat(getter.get(carrier, "other")).isEqualTo("untouched")
            assertThat(getter.get(carrier, "traceparent")).isEqualTo(parent())
        }
    }

    @Test
    fun `empty state members are legal while the grammar member limit is retained`() {
        val getter = extractionGetter()
        assertThat(getter.get(mapOf("tracestate" to ""), "tracestate")).isEmpty()
        assertThat(getter.get(mapOf("tracestate" to " \t,,a=x,, "), "tracestate")).isEqualTo("a=x")
        assertThat(getter.get(mapOf("tracestate" to ",".repeat(31)), "tracestate")).isEmpty()
        assertThat(getter.get(mapOf("tracestate" to ",".repeat(32)), "tracestate")).isNull()
    }

    @Test
    fun `an invalid traceparent does not request tracestate and injection still carries valid state`() {
        val carrier = object : AbstractMap<String, String>() {
            override val entries: Set<Map.Entry<String, String>> = mapOf("traceparent" to "invalid").entries

            override fun get(key: String): String? {
                check(key != "tracestate") { "tracestate must not be read after invalid traceparent" }
                return super.get(key)
            }
        }
        assertThat(extracted(carrier).isValid).isFalse()
        val state = TraceState.builder().put("vendor", "value").build()
        val span = Span.wrap(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), state))
        val sent = span.makeCurrent().use { propagator.getCurrentContext() }
        val wire = propagator.serializeContext(sent)
        val restored = extracted(propagator.deserializeContext(wire) as Map<String, String>)
        assertThat(restored.traceId).isEqualTo(traceId)
        assertThat(restored.traceState["vendor"]).isEqualTo("value")
    }
}
