// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.trace

import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins what the shared profile hands every adopter, and that a subclass's extra keys are ADDED
 * rather than replacing the baseline: a profile that quietly lost `quarkus.otel.sdk.disabled=false`
 * would leave every trace IT green on zero spans.
 */
@Suppress("FunctionNaming")
class TraceContractProfileTest {

    private class WithExtras : TraceContractProfile() {
        override fun extraConfigOverrides() = mapOf("authz.enforce" to "false")
    }

    private class NoOutbox : TraceContractProfile() {
        override val disableOutboxDispatcher = false
    }

    @Test
    fun `the baseline turns the SDK on, the dispatcher off and indexes this jar for the recorder`() {
        assertThat(TraceContractProfile().configOverrides).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "quarkus.otel.sdk.disabled" to "false",
                "quarkus.otel.traces.exporter" to "none",
                "openbank.outbox.dispatch-enabled" to "false",
                "quarkus.index-dependency.openbank-libs-testing.group-id" to "com.openbank",
                "quarkus.index-dependency.openbank-libs-testing.artifact-id" to "openbank-libs-testing",
            ),
        )
    }

    @Test
    fun `a subclass adds its keys to the baseline instead of replacing it`() {
        val overrides = WithExtras().configOverrides
        assertThat(overrides).containsEntry("authz.enforce", "false")
        assertThat(overrides).containsEntry("quarkus.otel.sdk.disabled", "false")
        assertThat(overrides).containsEntry("quarkus.otel.traces.exporter", "none")
        assertThat(overrides).containsEntry("openbank.outbox.dispatch-enabled", "false")
    }

    @Test
    fun `a service without an outbox drops only the dispatcher key`() {
        val overrides = NoOutbox().configOverrides
        assertThat(overrides).doesNotContainKey("openbank.outbox.dispatch-enabled")
        assertThat(overrides).containsEntry("quarkus.otel.sdk.disabled", "false")
        assertThat(overrides).containsEntry("quarkus.otel.traces.exporter", "none")
    }

    @Test
    fun `the recorder produces the exporter and a span processor over that same exporter`() {
        val produced = TraceContractRecorder::class.java.declaredMethods
            .filter { it.isAnnotationPresent(Produces::class.java) }
        assertThat(produced.map { it.name }).containsExactlyInAnyOrder("recordingExporter", "recordingSpanProcessor")
        assertThat(produced).allMatch { it.isAnnotationPresent(Singleton::class.java) }
        val recorder = TraceContractRecorder()
        val exporter = recorder.recordingExporter()
        val processor = recorder.recordingSpanProcessor(exporter)
        assertThat(processor.isStartRequired).isFalse()
        assertThat(processor.isEndRequired).isTrue()
    }
}
