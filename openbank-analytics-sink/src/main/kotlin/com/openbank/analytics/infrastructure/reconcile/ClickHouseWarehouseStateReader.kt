// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.openbank.analytics.application.port.out.WarehouseStateReader
import com.openbank.analytics.infrastructure.clickhouse.ClickHouseClient
import com.openbank.libs.analytics.AggregateKey
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject

/**
 * ClickHouse-backed [WarehouseStateReader]: the warehouse side of the reconciliation tie-out
 * (ADR-0023, F4 count tie-out + F5 completeness), reading the bronze layer over HTTP.
 *
 * It is the `@Alternative @Priority(100)` binding behind the `@Default`
 * [NoOpWarehouseStateReader], gated at build time by `openbank.analytics.sink.type=clickhouse`, so
 * the default profile still reconciles as a clean no-op with zero infrastructure.
 *
 * All three reads are cheap aggregate scans — **no payloads cross the wire** — so the drift check
 * never loads the operational system. SQL building and the TabSeparated parsing are pure and
 * unit-tested without a server.
 */
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "openbank.analytics.sink.type", stringValue = "clickhouse")
open class ClickHouseWarehouseStateReader : WarehouseStateReader {

    @Inject
    lateinit var clickhouse: ClickHouseClient

    override suspend fun currentVersions(): Map<AggregateKey, Long> =
        parseVersions(clickhouse.query(CURRENT_VERSIONS_SQL))

    override suspend fun rowCountsByType(): Map<String, Long> = parseCounts(clickhouse.query(ROW_COUNTS_SQL))

    override suspend fun versionsByAggregate(): Map<AggregateKey, Collection<Long>> =
        parseVersionLists(clickhouse.query(VERSIONS_BY_AGGREGATE_SQL))

    /** `type \t id \t maxVersion` rows → `AggregateKey -> maxVersion`. */
    internal fun parseVersions(tsv: String): Map<AggregateKey, Long> = rows(tsv).mapNotNull { cols ->
        if (cols.size < 3) {
            null
        } else {
            AggregateKey(cols[0], cols[1]) to (cols[2].toLongOrNull() ?: return@mapNotNull null)
        }
    }.toMap()

    /** `type \t count` rows → `type -> count`. */
    internal fun parseCounts(tsv: String): Map<String, Long> = rows(tsv).mapNotNull { cols ->
        if (cols.size < 2) null else cols[0] to (cols[1].toLongOrNull() ?: return@mapNotNull null)
    }.toMap()

    /** `type \t id \t "v1,v2,v3"` rows → `AggregateKey -> [versions]`. */
    internal fun parseVersionLists(tsv: String): Map<AggregateKey, Collection<Long>> = rows(tsv).mapNotNull { cols ->
        if (cols.size < 3) return@mapNotNull null
        val versions = cols[2].split(',').mapNotNull { it.trim().toLongOrNull() }
        AggregateKey(cols[0], cols[1]) to versions
    }.toMap()

    private fun rows(tsv: String): List<List<String>> =
        tsv.lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()

    private companion object {
        // upper(aggregate_type), not the raw column (issue #4604, the #4553 follow-up). bronze holds
        // both `ACCOUNT`/`Account` and `Transaction`/`Consent`-only spellings for the same domains, and
        // AggregateKey's equals/hashCode are case-sensitive — so an unfolded GROUP BY produces TWO
        // AggregateKeys for one real aggregate. ReconciliationJob compares this reader's output against
        // HttpReconciliationSource's, which reports each OLTP service's OWN (consistently-cased)
        // convention: a pre-#4576 mixed-case account would then show up as BOTH missingInWarehouse
        // (source key not found under its case) AND missingInSource (warehouse key not found under
        // its case) — a false DRIFT the periodic job would seal to WORM as audit evidence, and an
        // operator would investigate a backfill that does not exist. Folding here merges the
        // case-variant rows via ClickHouse's own GROUP BY, taking the true max version and the full
        // version sequence across both spellings — the same semantics silver_current_state's argMax
        // reduction already gives the rest of the warehouse.
        const val CURRENT_VERSIONS_SQL =
            "SELECT upper(aggregate_type), aggregate_id, max(aggregate_version) " +
                "FROM bronze_events GROUP BY upper(aggregate_type), aggregate_id FORMAT TabSeparated"

        // count(DISTINCT aggregate_id): a per-type aggregate count, so whole-aggregate loss is caught
        // even when versions still line up (F4). Independent of the version tie-out.
        const val ROW_COUNTS_SQL =
            "SELECT upper(aggregate_type), count(DISTINCT aggregate_id) " +
                "FROM bronze_events GROUP BY upper(aggregate_type) FORMAT TabSeparated"

        // groupUniqArray + arraySort: the distinct, ordered version sequence per aggregate, so the
        // completeness check (F5) can spot a hole in the monotonic sequence (a provably lost event).
        // Folding here is not just cosmetic: an unfolded GROUP BY would split one account's version
        // history across two sequences (e.g. [1,3] under `ACCOUNT` and [2,4] under `Account`), and
        // Completeness.gapsFromVersions would report two holes that do not exist in the real,
        // interleaved sequence [1,2,3,4].
        const val VERSIONS_BY_AGGREGATE_SQL =
            "SELECT upper(aggregate_type), aggregate_id, " +
                "arrayStringConcat(arraySort(groupUniqArray(aggregate_version)), ',') " +
                "FROM bronze_events GROUP BY upper(aggregate_type), aggregate_id FORMAT TabSeparated"
    }
}
