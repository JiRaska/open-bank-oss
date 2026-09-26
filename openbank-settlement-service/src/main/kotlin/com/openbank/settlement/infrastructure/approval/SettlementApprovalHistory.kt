// SPDX-License-Identifier: Apache-2.0
package com.openbank.settlement.infrastructure.approval

import com.openbank.libs.approval.PendingApproval
import com.openbank.settlement.infrastructure.persistence.entity.SettlementOperatorProposalEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** Retained evidence reads never authorize a decision or execution, even before expiry. */
@ApplicationScoped
class SettlementApprovalHistory(
    private val approvals: PostgresApprovalStore,
    private val proposals: SettlementProposalStore,
    private val clock: Clock,
) {
    suspend fun readRecord(id: String): SettlementApprovalRecord? {
        val key = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return Panache.withSession {
            approvals.findById(key).flatMap { approval ->
                if (approval == null) {
                    Uni.createFrom().nullItem<SettlementApprovalRecord>()
                } else {
                    proposals.findBound(approval).map { proposal ->
                        SettlementApprovalRecord(
                            approval.toDomain(),
                            approval.expiresAt,
                            !approval.expiresAt.isAfter(OffsetDateTime.now(clock)),
                            approval.claimedAt,
                            proposal,
                        )
                    }
                }
            }
        }.awaitSuspending()
    }
}

/** A read snapshot, separate from the expiring ApprovalStore authorization contract. */
data class SettlementApprovalRecord(
    val approval: PendingApproval,
    val expiresAt: OffsetDateTime,
    val expired: Boolean,
    val claimedAt: OffsetDateTime?,
    val proposal: SettlementOperatorProposalEntity?,
)
