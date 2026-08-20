// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application.port.out

import java.time.Instant

/**
 * A message that could not be projected into an [com.openbank.libs.analytics.AnalyticsEnvelope]
 * (malformed JSON, missing required field, mapping failure).
 *
 * [contentHash] is a stable digest of [rawPayload] so the DLQ is **idempotent** — the same poison
 * message re-delivered (at-least-once) or hit again on replay does not create duplicate quarantine
 * rows. Once the producer bug is fixed, an operator replays the quarantined payloads through the
 * normal mapping path; until then they are visible and counted, never silently lost.
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
 */
interface DeadLetterSink {
    suspend fun quarantine(record: DeadLetterRecord)
}
