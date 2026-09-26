// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.settlement.infrastructure.approval

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.settlement.infrastructure.persistence.entity.SettlementOperatorApprovalEntity
import com.openbank.settlement.infrastructure.persistence.entity.SettlementOperatorProposalEntity
import com.openbank.settlement.infrastructure.rest.CreateSettlementRequest
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** A captured proposal has no financial effect. Its approval reference is committed separately. */
@ApplicationScoped
class SettlementProposalStore(private val clock: Clock) :
    PanacheRepositoryBase<SettlementOperatorProposalEntity, UUID> {
    suspend fun capture(instruction: CreateSettlementRequest, maker: String): SettlementOperatorProposalEntity {
        require(maker.isNotBlank()) { "Maker is required" }
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeMutationQuery(INSERT)
                    .setParameter("id", Ids.newId())
                    .setParameter("maker", maker)
                    .setParameter("fingerprint", instruction.approvalFingerprint)
                    .setParameter("key", instruction.idempotencyKey)
                    .setParameter("payer", instruction.payerAccountId)
                    .setParameter("payee", instruction.payeeAccountId)
                    .setParameter("amount", instruction.amount.stripTrailingZeros().toString())
                    .setParameter("currency", instruction.currency)
                    .setParameter("created", OffsetDateTime.now(clock))
                    .executeUpdate()
            }.flatMap { findForMaker(maker, instruction.approvalFingerprint) }
        }.awaitSuspending().let { stored ->
            checkNotNull(stored) { "Stored proposal is missing" }
            check(stored.instruction().approvalFingerprint == instruction.approvalFingerprint) {
                "Stored proposal does not match the submitted instruction"
            }
            stored
        }
    }

    /** Called inside the approval transaction; the composite FK preserves maker + digest binding. */
    fun findForMaker(maker: String, fingerprint: String): Uni<SettlementOperatorProposalEntity?> =
        find("makerId = ?1 and fingerprint = ?2", maker, fingerprint).firstResult()

    /** Binding validation shared by authorization and retained evidence reads. */
    fun findBound(approval: SettlementOperatorApprovalEntity): Uni<SettlementOperatorProposalEntity?> {
        val proposalId = approval.proposalId ?: return Uni.createFrom().nullItem()
        return findById(proposalId).map { proposal ->
            check(
                proposal != null &&
                    proposal.makerId == approval.makerId &&
                    proposal.instruction().approvalFingerprint == approval.resourceId,
            ) { "Proposal binding is invalid" }
            proposal
        }
    }

    private companion object {
        const val INSERT = """
            INSERT INTO settlement_operator_proposals
                (id, maker_id, fingerprint, idempotency_key, payer_account_id, payee_account_id, amount, currency, created_at)
            VALUES (:id, :maker, :fingerprint, :key, :payer, :payee, :amount, :currency, :created)
            ON CONFLICT (maker_id, fingerprint) DO NOTHING
        """
    }
}
