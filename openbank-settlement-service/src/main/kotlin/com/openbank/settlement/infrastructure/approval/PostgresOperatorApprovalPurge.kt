// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.approval

import com.openbank.settlement.application.port.out.SettlementOperatorApprovalPurge
import com.openbank.settlement.infrastructure.persistence.entity.SettlementOperatorApprovalEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Retention delete for `settlement_operator_approvals` (V6 `idx_settlement_operator_approvals_retention`). A
 * separate bean from [PostgresApprovalStore] so the four-eyes path never holds a delete.
 *
 * Every row whose authorization expired before the cutoff is terminal, whatever its status: an
 * expired PENDING approval can never be decided or claimed (both transitions refuse an expired
 * row), so it is evidence like any other. The cutoff is always in the past (retention >= 1 day), so
 * a still-live approval — PENDING or APPROVED — can never match. The delete re-checks the predicate,
 * so a row cannot be removed on the strength of a stale read.
 */
@ApplicationScoped
class PostgresOperatorApprovalPurge :
    SettlementOperatorApprovalPurge,
    PanacheRepositoryBase<SettlementOperatorApprovalEntity, UUID> {

    override suspend fun purgeTerminalExpiredBefore(cutoff: OffsetDateTime, batchSize: Int): Int {
        require(batchSize > 0) { "Purge batch size must be positive" }
        return Panache.withTransaction {
            find("expiresAt < ?1 order by expiresAt, id", cutoff)
                .page<SettlementOperatorApprovalEntity>(0, batchSize)
                .list<SettlementOperatorApprovalEntity>()
                .flatMap { rows: List<SettlementOperatorApprovalEntity> ->
                    if (rows.isEmpty()) {
                        Uni.createFrom().item(0L)
                    } else {
                        delete("id in ?1 and expiresAt < ?2", rows.map { it.id }, cutoff)
                    }
                }
        }.awaitSuspending().toInt()
    }
}
