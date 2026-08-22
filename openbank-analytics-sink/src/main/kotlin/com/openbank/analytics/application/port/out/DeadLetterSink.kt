// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application.port.out

import java.time.Instant

/**
 * A message that could not be projected into an [com.openbank.libs.analytics.AnalyticsEnvelope]
 * (malformed JSON, missing required field, mapping failure).
 *
 * [contentHash] is a stable digest of [rawPayload], so a durable sink can make quarantine idempotent
 * — the same poison message re-delivered (at-least-once) or hit again on replay would not create
 * duplicate rows.
 *
 * **No such sink is bound today.** The only implementation is
 * [com.openbank.analytics.infrastructure.sink.LoggingDeadLetterSink], one `log.warnf`. So the hash is
 * currently a grep key rather than a dedupe key, "quarantined" means "logged at WARN", and a
 * quarantined payload survives exactly as long as the log pipeline retains it. Operator replay is
 * whatever can be reconstructed from those lines by hand.
 */
data class DeadLetterRecord(val contentHash: String, val rawPayload: String, val error: String, val failedAt: Instant)

/**
 * Outbound port for quarantining un-projectable messages (ADR-0022).
 *
 * Replaces the old "log and swallow" behaviour: a dropped event is an invisible gap in the bronze
 * layer, which undermines the "complete, replayable log of record" guarantee.
 *
 * Two bindings. [com.openbank.analytics.infrastructure.sink.ClickHouseDeadLetterSink] is the durable
 * one — it writes the `dead_letter_events` row this KDoc describes — and is selected at build time by
 * `openbank.analytics.sink.type=clickhouse`, alongside the durable warehouse sink. Otherwise the
 * zero-infrastructure [com.openbank.analytics.infrastructure.sink.LoggingDeadLetterSink] applies, and
 * "quarantined" then means **logged**: recoverable for as long as log retention holds, not from the
 * table. Read a `quarantine()` that returns normally as proof of nothing — the two are
 * indistinguishable at the call site (#5761).
 *
 * This port also covers only the *unretryable* case. A transient sink failure is no longer quarantined
 * at all — [com.openbank.analytics.application.AnalyticsConsumer] retries it and then nacks, because
 * a ClickHouse outage is not a bad event and parking it here would lose it just as thoroughly.
 */
interface DeadLetterSink {
    suspend fun quarantine(record: DeadLetterRecord)
}
