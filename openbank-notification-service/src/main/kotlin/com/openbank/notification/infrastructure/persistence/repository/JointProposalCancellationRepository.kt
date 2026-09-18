// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.repository

import com.openbank.notification.infrastructure.persistence.entity.JointProposalCancellationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@ApplicationScoped
class JointProposalCancellationRepository : PanacheRepositoryBase<JointProposalCancellationEntity, UUID> {
    fun isCancelled(operationId: UUID): Uni<Boolean> = Panache.withSession {
        findById(operationId).map { it != null }
    }

    /** Idempotent exact replay; conflicting evidence for one operation fails into the channel DLQ. */
    fun record(
        operationId: UUID,
        principalPartyId: UUID,
        actorId: UUID,
        operationKind: String,
        cancelledAt: Instant,
    ): Uni<Void> = Panache.withTransaction {
        val storedAt = cancelledAt.truncatedTo(ChronoUnit.MICROS)
        Panache.getSession().chain { session ->
            session.createNativeMutationQuery(INSERT_SQL)
                .setParameter("operationId", operationId)
                .setParameter("principalPartyId", principalPartyId)
                .setParameter("actorId", actorId)
                .setParameter("operationKind", operationKind)
                .setParameter("cancelledAt", storedAt)
                .executeUpdate()
        }.chain { _: Int ->
            findById(operationId).invoke { existing ->
                check(
                    existing != null &&
                        existing.principalPartyId == principalPartyId &&
                        existing.actorId == actorId &&
                        existing.operationKind == operationKind &&
                        existing.cancelledAt == storedAt,
                ) { "conflicting joint proposal cancellation fact" }
            }
        }
    }.replaceWithVoid()

    private companion object {
        const val INSERT_SQL = """
            INSERT INTO joint_proposal_cancellations
                (operation_id, principal_party_id, actor_id, operation_kind, cancelled_at)
            VALUES (:operationId, :principalPartyId, :actorId, :operationKind, :cancelledAt)
            ON CONFLICT (operation_id) DO NOTHING
        """
    }
}
