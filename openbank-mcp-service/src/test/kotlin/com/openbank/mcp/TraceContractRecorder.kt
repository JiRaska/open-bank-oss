// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.mcp

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
