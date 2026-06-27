// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application.port.out

import com.openbank.libs.analytics.AggregateKey

/**
 * Current-state authority on the **OLTP source-of-record** side, as `AggregateKey -> maxVersion`.
 *
 * Implementations ask each domain service (or read-replica) for a cheap `GROUP BY aggregate max(version)`
 * — no payloads cross the wire, so the drift check does not load the operational system. Default
 * binding [com.openbank.analytics.infrastructure.reconcile.NoOpReconciliationSource] returns empty.
 */
interface ReconciliationSource {
    suspend fun currentVersions(): Map<AggregateKey, Long>

    /**
     * Row counts per aggregate type on the OLTP side (`GROUP BY aggregate_type count()`), for the
     * independent count tie-out (F4). Default empty so non-count adapters need not implement it.
     */
    suspend fun rowCountsByType(): Map<String, Long> = emptyMap()
}

/**
 * Current-state authority on the **warehouse** side, as `AggregateKey -> maxVersion`.
 *
 * Implementations run `SELECT aggregate_type, aggregate_id, max(aggregate_version) ... GROUP BY ...`
 * over the ClickHouse bronze/silver layer. Default binding
 * [com.openbank.analytics.infrastructure.reconcile.NoOpWarehouseStateReader] returns empty.
 */
interface WarehouseStateReader {
    suspend fun currentVersions(): Map<AggregateKey, Long>

    /** Row counts per aggregate type on the warehouse side, for the count tie-out (F4). Default empty. */
    suspend fun rowCountsByType(): Map<String, Long> = emptyMap()

    /**
     * Versions present per aggregate in bronze (`groupArray(aggregate_version) GROUP BY`), for the
     * completeness gap check (F5) — proves no event was lost mid-sequence. Default empty.
     */
    suspend fun versionsByAggregate(): Map<AggregateKey, Collection<Long>> = emptyMap()
}
