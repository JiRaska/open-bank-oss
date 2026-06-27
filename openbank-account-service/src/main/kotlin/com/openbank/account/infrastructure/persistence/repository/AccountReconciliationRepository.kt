// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.repository

import com.openbank.account.infrastructure.persistence.entity.AccountEntity
import com.openbank.libs.analytics.AggregateVersion
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Source-side reconciliation projection over the `accounts` table (ADR-0026, Phase 1).
 *
 * Reports the aggregate's optimistic-lock `@Version` as the `max(version)` authority — for the `accounts`
 * table that is one row per aggregate, so no aggregation is needed and the read needs no new index. The
 * [aggregateType] token is the literal `"Account"`, matching exactly what [AccountEvents] emit and what the
 * warehouse stores from `AnalyticsEnvelope.aggregateType`; the two must line up or the keys will not match
 * in the sink's drift check. Account pockets emit no events and are therefore out of reconciliation scope.
 */
@ApplicationScoped
class AccountReconciliationRepository : PanacheRepository<AccountEntity> {

    /** Aggregates plus the high-water mark (max `updated_at`) of the rows considered when `since` is used. */
    data class Projection(val aggregates: List<AggregateVersion>, val watermark: Instant?)

    fun summary(since: Instant?): Uni<Projection> = Panache.withSession {
        (if (since == null) findAll() else find("updatedAt >= ?1", since)).list()
    }.map { rows ->
        Projection(
            aggregates = rows.map { AggregateVersion("Account", it.id.toString(), it.version) },
            watermark = if (since != null) rows.maxOfOrNull { it.updatedAt } else null,
        )
    }
}
