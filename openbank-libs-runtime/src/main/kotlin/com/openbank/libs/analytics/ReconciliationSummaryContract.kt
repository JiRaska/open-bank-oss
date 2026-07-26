// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.analytics

import com.openbank.libs.security.Roles
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import java.time.Instant

/**
 * One aggregate's source-of-record version authority: `max(version)` for a `(type, id)` (ADR-0026).
 *
 * On the OLTP side this is just the aggregate row's optimistic-lock `@Version` column — the same
 * monotonic value the outbox emits as `AnalyticsEnvelope.aggregateVersion`, so a healthy system has
 * `source.maxVersion == warehouse.max(aggregate_version)` for every key. The [aggregateType] token
 * MUST match the string the event envelope carries, or the keys will not line up in [Reconciliation.diff].
 */
data class AggregateVersion(val aggregateType: String, val aggregateId: String, val maxVersion: Long)

/**
 * A domain service's complete reconciliation summary — the **OLTP source side** of the F4/F5 tie-out
 * (ADR-0026, closing the ADR-0023 F4/F5 source-side follow-up).
 *
 * Carries only versions and counts, never payloads, so the analytics-sink's drift check never loads
 * the operational store. [aggregates] feeds the per-aggregate `max(version)` comparison
 * ([Reconciliation.diff]); [countsByType] feeds the independent row-count tie-out
 * ([Reconciliation.countDiff], F4). No version *sequence* is exposed: the OLTP store keeps only current
 * state, so the completeness check (F5) stays inherently warehouse-only.
 */
data class ServiceReconciliationSummary(
    val service: String,
    val generatedAt: Instant,
    /** Distinct-aggregate count per aggregate type (source side of the F4 count tie-out). */
    val countsByType: Map<String, Long>,
    /** Per-aggregate `max(version)` — the current-state authority compared to the warehouse. */
    val aggregates: List<AggregateVersion>,
    /** High-water mark of this pass (max `updated_at` considered); set when the `since` window is used. */
    val watermark: Instant? = null,
)

/**
 * Shared HTTP contract every in-scope event-emitting domain service implements so the analytics-sink
 * can reconcile its warehouse against the OLTP source of record (ADR-0026).
 *
 * The contract lives here in `openbank-libs` (not a separate `openbank-contracts` module) next to the
 * reconciliation primitives both sides already depend on, so the path, media type and — critically —
 * the **authorization gate** are defined exactly once and cannot drift or be weakened per service.
 * A service implements this interface and supplies only the data (its repository projection).
 *
 * **Security:** role-gated, never `@PermitAll` (the metadata is audit material). The caller is the
 * analytics-sink calling service-to-service under the `openbank-services` client-credentials grant, so
 * [Roles.API] must be allowed; the human audit roles let an examiner-facing operator read it too.
 *
 * **Performance:** implementations must serve this off the customer path — read-only, off-peak (the
 * sink drives it from its off-peak cron), ideally a read-replica, and `since`-windowed where the table
 * is large enough that a full dump would be expensive (see ADR-0026 §D5). `max(version)` needs no new
 * index (one row per aggregate); only `updated_at` for the incremental window on large tables.
 */
@Path("/api/v1/analytics/reconciliation-summary")
interface ReconciliationSummaryContract {

    /**
     * Returns a [Uni] so reactive services (Hibernate Reactive) implement it natively; a blocking
     * service would return `Uni.createFrom().item { … }`. The analytics-sink does not implement this
     * interface — it reads the JSON over the JDK HttpClient — so the reactive return type is internal
     * to the producing services.
     *
     * @param since optional ISO-8601 instant; when present, the summary covers only aggregates whose
     *   `updated_at` is at or after it (incremental window). Omitted ⇒ a full pass.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.ADMIN, Roles.COMPLIANCE)
    fun reconciliationSummary(@QueryParam("since") since: String?): Uni<ServiceReconciliationSummary>
}
