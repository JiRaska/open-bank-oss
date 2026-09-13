// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.sink

import com.openbank.analytics.application.port.out.DeadLetterRecord
import com.openbank.analytics.application.port.out.DeadLetterSink
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Default
import org.jboss.logging.Logger

/**
 * Default [DeadLetterSink] that emits the quarantined message at WARN with its content hash.
 *
 * WARN (not INFO) so it trips alerting — a non-zero DLQ rate means a producer is emitting malformed
 * events and bronze is losing rows. The payload is logged truncated, so a message quarantined through
 * this binding is recoverable only from the log pipeline, only for as long as log retention holds, and
 * only up to 500 characters — it is NOT the durable, replayable `dead_letter_events` row that
 * ADR-0022, the diagrams, the overview docs and the `dead_lettered` Grafana panel describe. That row
 * is written by [ClickHouseDeadLetterSink], which this fallback yields to when
 * `openbank.analytics.sink.type=clickhouse`. Any deployment relying on the documented "replay
 * `raw_payload`" recovery, or reading that panel as a dead-letter rate, must select that binding —
 * under this one the panel is a structural zero (#5761).
 */
@ApplicationScoped
@Default
class LoggingDeadLetterSink : DeadLetterSink {
    private val log = Logger.getLogger("openbank.analytics.dlq")

    override suspend fun quarantine(record: DeadLetterRecord) {
        log.warnf(
            "analytics dead-letter hash=%s error=%s payload=%s",
            record.contentHash,
            record.error,
            record.rawPayload.take(500),
        )
    }
}
