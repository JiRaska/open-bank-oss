// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.sink

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.DeadLetterRecord
import com.openbank.analytics.application.port.out.DeadLetterSink
import com.openbank.analytics.infrastructure.clickhouse.ClickHouseClient
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * CDI priority of the durable ClickHouse bindings over their `@Default` logging fallbacks — the same
 * value [ClickHouseAnalyticsSink] and the WORM mirror use. Declared as a constant (not an inline
 * literal) so detekt's `MagicNumber` needs no baseline entry; it sits ABOVE the annotated class on
 * purpose, since a top-level declaration placed *between* an annotation and its class silently steals
 * the annotation (see the fleet CLAUDE.md `@Path` footgun).
 */
private const val CLICKHOUSE_ALTERNATIVE_PRIORITY = 100

/**
 * Durable [DeadLetterSink] that quarantines an un-projectable message into the ClickHouse
 * `dead_letter_events` table (ADR-0022).
 *
 * This is the adapter the port's KDoc, ADR-0022, both service diagrams, both language overview docs
 * and the `dead_lettered` Grafana panel have always assumed exists. Until it did, `quarantine()`
 * resolved to [LoggingDeadLetterSink] and "quarantined" meant *logged*: the row an operator would go
 * looking for was never written, the documented "replay `raw_payload` through the normal mapping
 * path" recovery had no source to replay from, and the panel read a **structural** zero — a value it
 * could not have exceeded for any dead-letter rate, which reads as evidence of health (#5761).
 *
 * Note the shape that let that ship: [LoggingDeadLetterSink.quarantine] returns normally, and a
 * normal return is indistinguishable from a successful durable write at every call site. Nothing in
 * the consumer, the metrics or the logs could have disagreed.
 *
 * Same binding idiom as [ClickHouseAnalyticsSink] and
 * [com.openbank.analytics.infrastructure.worm.ClickHouseWormArchive]: an `@Alternative`
 * `@Priority(CLICKHOUSE_ALTERNATIVE_PRIORITY)` over the `@Default` logging fallback, gated at **build time** by
 * `openbank.analytics.sink.type=clickhouse`, so the zero-infrastructure default still boots and
 * tests without a ClickHouse server.
 *
 * Idempotency is delegated to the table engine exactly as [DeadLetterRecord] promises:
 * `ReplacingMergeTree(failed_at)` ordered by `content_hash` collapses a re-delivered or replayed
 * poison message to one row at merge / `FINAL`, so at-least-once delivery cannot inflate the DLQ.
 *
 * [DeadLetterRecord.rawPayload] is written **whole** and unmasked — it is by definition a message the
 * masking projection could not parse, and a truncated payload is not replayable. The table's 1-year
 * TTL (`V1__analytics_bronze_silver.sql`) bounds that exposure; it is operational quarantine, not the
 * log of record.
 */
@ApplicationScoped
@Alternative
@Priority(CLICKHOUSE_ALTERNATIVE_PRIORITY)
@IfBuildProperty(name = "openbank.analytics.sink.type", stringValue = "clickhouse")
open class ClickHouseDeadLetterSink : DeadLetterSink {

    @Inject
    lateinit var clickhouse: ClickHouseClient

    @Inject
    lateinit var mapper: ObjectMapper

    override suspend fun quarantine(record: DeadLetterRecord) {
        clickhouse.insert(TABLE, rowJson(record))
    }

    /** Serialises a record into a `dead_letter_events` JSONEachRow object. Pure / unit-testable. */
    internal fun rowJson(record: DeadLetterRecord): String {
        val row = linkedMapOf<String, Any?>(
            "content_hash" to record.contentHash,
            "raw_payload" to record.rawPayload,
            "error" to record.error,
            "failed_at" to DT.format(record.failedAt),
        )
        return mapper.writeValueAsString(row)
    }

    private companion object {
        const val TABLE = "dead_letter_events"

        // ClickHouse DateTime64(3,'UTC') literal format: 'yyyy-MM-dd HH:mm:ss.SSS'.
        val DT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}
