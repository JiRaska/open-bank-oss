// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.approval

import com.openbank.libs.approval.PendingApproval
import com.openbank.settlement.infrastructure.persistence.entity.SettlementOperatorApprovalEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Evidence reads of `settlement_operator_approvals`: one approval in ANY status, expired included.
 * Unlike [PostgresApprovalStore.find] — the authorization read, which hides an expired row — this
 * never authorizes anything; the interceptor and the decide/claim transitions do not call it.
 */
@ApplicationScoped
class SettlementApprovalRecords(private val clock: Clock) :
    PanacheRepositoryBase<SettlementOperatorApprovalEntity, UUID> {

    suspend fun findRecord(id: String): SettlementApprovalRecord? {
        val key = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return Panache.withSession {
            findById(key).map { row ->
                row?.let {
                    SettlementApprovalRecord(
                        approval = it.toDomain(),
                        expiresAt = it.expiresAt,
                        expired = !it.expiresAt.isAfter(OffsetDateTime.now(clock)),
                        claimedAt = it.claimedAt,
                    )
                }
            }
        }.awaitSuspending()
    }
}

/** A read snapshot of one approval, separate from the expiring ApprovalStore authorization contract. */
data class SettlementApprovalRecord(
    val approval: PendingApproval,
    val expiresAt: OffsetDateTime,
    val expired: Boolean,
    val claimedAt: OffsetDateTime?,
)
