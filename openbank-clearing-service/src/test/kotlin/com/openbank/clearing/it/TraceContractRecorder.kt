// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.it

import com.openbank.libs.testing.trace.RecordingSpanExporter
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
 */
class TraceContractRecorder {

    @Produces
    @Singleton
    fun recordingExporter(): RecordingSpanExporter = RecordingSpanExporter()

    @Produces
    @Singleton
    fun recordingSpanProcessor(exporter: RecordingSpanExporter): SpanProcessor = SimpleSpanProcessor.create(exporter)
}
