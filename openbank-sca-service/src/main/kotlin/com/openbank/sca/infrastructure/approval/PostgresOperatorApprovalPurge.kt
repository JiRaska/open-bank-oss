// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.approval

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.sca.application.port.out.ScaOperatorApprovalEvidencePurge
import com.openbank.sca.infrastructure.persistence.entity.ScaOperatorApprovalEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Retention delete for `sca_operator_approvals` (V15 `idx_sca_operator_approvals_retention`). A
 * separate bean from [PostgresApprovalStore] so the four-eyes path never holds a delete.
 *
 * Terminal rows only: the query names the three terminal statuses rather than `<> PENDING`, so a
 * status added later is retained until someone decides it is terminal. The delete re-checks the
 * status, so a row cannot be removed on the strength of a stale read.
 */
@ApplicationScoped
class PostgresOperatorApprovalPurge :
    ScaOperatorApprovalEvidencePurge,
    PanacheRepositoryBase<ScaOperatorApprovalEntity, UUID> {

    override suspend fun purgeTerminalExpiredBefore(cutoff: OffsetDateTime, batchSize: Int): Int {
        require(batchSize > 0) { "Purge batch size must be positive" }
        return Panache.withTransaction {
            find("status in ?1 and expiresAt < ?2 order by expiresAt, id", TERMINAL, cutoff)
                .page<ScaOperatorApprovalEntity>(0, batchSize)
                .list<ScaOperatorApprovalEntity>()
                .flatMap { rows: List<ScaOperatorApprovalEntity> ->
                    if (rows.isEmpty()) {
                        Uni.createFrom().item(0L)
                    } else {
                        delete("id in ?1 and status in ?2", rows.map { it.id }, TERMINAL)
                    }
                }
        }.awaitSuspending().toInt()
    }

    private companion object {
        val TERMINAL = listOf(ApprovalStatus.APPROVED, ApprovalStatus.REJECTED, ApprovalStatus.EXECUTED)
    }
}
