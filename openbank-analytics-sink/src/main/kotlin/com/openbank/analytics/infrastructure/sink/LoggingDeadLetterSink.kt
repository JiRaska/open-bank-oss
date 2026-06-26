// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
 * events and bronze is losing rows. The full payload is logged (truncated) so the message is
 * recoverable from the log pipeline even before the ClickHouse `dead_letter_events` adapter exists.
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
            record.rawPayload.take(500)
        )
    }
}
