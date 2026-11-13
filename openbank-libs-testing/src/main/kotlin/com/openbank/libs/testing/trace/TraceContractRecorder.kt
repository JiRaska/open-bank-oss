// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.trace

import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * Test-only wiring that lets a trace contract observe the spans the REAL runtime emits.
 *
 * Quarkus' OpenTelemetry SDK builder injects every CDI [SpanProcessor] bean, so producing a
 * [SimpleSpanProcessor] over a [RecordingSpanExporter] here captures the service's own
 * auto-instrumented HTTP/DB spans without touching `src/main` or the OTLP wire.
 * [TraceContractProfile] disables automatic exporters so the default CDI exporter does not also
 * deliver the same spans through a delayed batch processor. This explicit processor remains active.
 *
 * It lives in a jar, so ArC only sees it when that jar is indexed: [TraceContractProfile] switches
 * `quarkus.index-dependency` on for libs-testing. A test that does not run under that profile gets
 * no recorder, which is the isolation the profile exists to provide.
 */
class TraceContractRecorder {

    @Produces
    @Singleton
    fun recordingExporter(): RecordingSpanExporter = RecordingSpanExporter()

    @Produces
    @Singleton
    fun recordingSpanProcessor(exporter: RecordingSpanExporter): SpanProcessor = SimpleSpanProcessor.create(exporter)
}
