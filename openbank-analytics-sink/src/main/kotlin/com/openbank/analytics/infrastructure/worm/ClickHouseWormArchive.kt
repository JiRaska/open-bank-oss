// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.worm

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.IntegrityAnchor
import com.openbank.analytics.application.port.out.WormArchive
import com.openbank.analytics.infrastructure.clickhouse.ClickHouseClient
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.Priority
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ClickHouse-backed [WormArchive] mirror: appends each [IntegrityAnchor] to the `integrity_anchors`
 * table and reads the most recent one back for chaining (ADR-0023, F1+F2).
 *
 * IMPORTANT — this is the *queryable mirror*, NOT the authoritative WORM store. ClickHouse is
 * operator-mutable, so it cannot itself be write-once-read-many; the durable, genuinely immutable
 * seal is the S3-Object-Lock (compliance-mode) adapter that remains the documented follow-up. This
 * mirror exists so the anchor chain is *queryable* alongside bronze (and re-derivable for a tamper
 * challenge), and so the chaining/`latest()` logic runs against real storage. It logs at WARN on boot
 * so an operator is never under the illusion the anchors are sealed in immutable storage here.
 *
 * It is the `@Alternative @Priority(100)` binding behind the `@Default` [LoggingWormArchive], gated
 * at build time by `openbank.analytics.sink.type=clickhouse`.
 */
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "openbank.analytics.sink.type", stringValue = "clickhouse")
open class ClickHouseWormArchive : WormArchive {

    @Inject
    lateinit var clickhouse: ClickHouseClient

    @Inject
    lateinit var mapper: ObjectMapper

    private val log = Logger.getLogger(ClickHouseWormArchive::class.java)

    @PostConstruct
    fun warn() {
        log.warn(
            "Using ClickHouseWormArchive: integrity anchors are mirrored to a queryable (operator-mutable) " +
                "ClickHouse table, NOT an immutable WORM store. Bind the S3-Object-Lock adapter for the " +
                "authoritative seal in production (ADR-0023 F1+F2)."
        )
    }

    override suspend fun seal(anchor: IntegrityAnchor) {
        clickhouse.insert("integrity_anchors", anchorJson(anchor))
    }

    override suspend fun latest(): IntegrityAnchor? =
        parseLatest(clickhouse.query(LATEST_SQL))

    /** Serialises an anchor into an `integrity_anchors` JSONEachRow object. Pure / unit-testable. */
    internal fun anchorJson(anchor: IntegrityAnchor): String {
        val row = linkedMapOf<String, Any?>(
            "anchor_id" to anchor.anchorId,
            "merkle_root" to anchor.merkleRoot,
            "previous_anchor_hash" to anchor.previousAnchorHash,
            "record_count" to anchor.recordCount,
            "source" to anchor.source,
            "sealed_at" to DT.format(anchor.sealedAt)
        )
        return mapper.writeValueAsString(row)
    }

    /** Parses the single-row TabSeparated `latest` result; null/blank → no anchor yet. */
    internal fun parseLatest(tsv: String): IntegrityAnchor? {
        val line = tsv.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val c = line.split('\t')
        if (c.size < 6) return null
        return IntegrityAnchor(
            anchorId = c[0],
            merkleRoot = c[1],
            // ClickHouse TabSeparated encodes a NULL as the literal \N.
            previousAnchorHash = c[2].takeUnless { it == "\\N" },
            recordCount = c[3].toIntOrNull() ?: 0,
            source = c[4],
            sealedAt = parseDt(c[5])
        )
    }

    private fun parseDt(text: String): Instant =
        LocalDateTime.parse(text.trim(), DT_PARSE).toInstant(ZoneOffset.UTC)

    private companion object {
        const val LATEST_SQL =
            "SELECT anchor_id, merkle_root, previous_anchor_hash, record_count, source, sealed_at " +
                "FROM integrity_anchors ORDER BY sealed_at DESC, anchor_id DESC LIMIT 1 FORMAT TabSeparated"

        val DT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
        // Lenient on the fractional part so a value serialised without millis still parses.
        val DT_PARSE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")
    }
}
