// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.persistence.repository

import com.openbank.libs.domain.money.Money
import com.openbank.swift.application.port.out.InstitutionTransferRejection
import com.openbank.swift.application.port.out.InstitutionTransferRepository
import com.openbank.swift.application.port.out.SwiftOutboxMessage
import com.openbank.swift.domain.institution.InstitutionParty
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.infrastructure.persistence.entity.InstitutionTransferEntity
import com.openbank.swift.infrastructure.persistence.entity.InstitutionTransferRejectionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

/**
 * Bank-own transfers on reactive Panache. The id is application-assigned (the payment id), so
 * writes go through `find` + in-place update or `merge`, never `persist` (an assigned `@Id`
 * makes `persist` INSERT-only). Every write that carries outbox rows chains them inside the same
 * `Panache.withTransaction`, the property `InstitutionTransferRailIT` measures by `xmin`.
 */
@ApplicationScoped
class InstitutionTransferRepositoryImpl(private val outboxRepoImpl: SwiftOutboxRepositoryImpl) :
    InstitutionTransferRepository,
    PanacheRepository<InstitutionTransferEntity> {

    override suspend fun findByIdempotencyKey(key: String): InstitutionTransfer? =
        Panache.withSession { find("idempotencyKey", key).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByPaymentId(paymentId: UUID): InstitutionTransfer? =
        Panache.withSession { find("paymentId", paymentId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun saveWithOutbox(
        transfer: InstitutionTransfer,
        outbox: List<SwiftOutboxMessage>,
    ): InstitutionTransfer = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.find(InstitutionTransferEntity::class.java, transfer.id).flatMap { existing ->
                if (existing != null) {
                    Uni.createFrom().item(existing.apply { applyFrom(transfer) })
                } else {
                    session.merge(InstitutionTransferEntity().apply { applyFrom(transfer) })
                }
            }
        }.flatMap { entity -> persistAll(outbox).replaceWith(entity) }
    }.awaitSuspending().toDomain()

    private companion object {
        const val OUTCOME_TRANSFER = "TRANSFER"
        const val OUTCOME_REJECTION = "REJECTION"
    }

    /**
     * The claim decides, inside the transaction that writes the outcome, which of the two paths
     * owns a payment: `INSERT ... ON CONFLICT DO NOTHING` on `swift_institution_transfer_claims`
     * (payment id PK, idempotency key UNIQUE). Concurrent claimants serialise on the unique index;
     * the loser inserts zero rows and writes NOTHING — no row, no event.
     */
    @Suppress("MagicNumber") // positional SQL parameters
    private fun claim(session: Mutiny.Session, paymentId: UUID, key: String?, outcome: String): Uni<Boolean> =
        session.createNativeQuery<Any>(
            "INSERT INTO swift_institution_transfer_claims (payment_id, idempotency_key, outcome, created_at) " +
                "VALUES (?1, ?2, ?3, now()) ON CONFLICT DO NOTHING",
        ).setParameter(1, paymentId).setParameter(2, key).setParameter(3, outcome)
            .executeUpdate()
            .map { it == 1 }

    override suspend fun insertClaimed(transfer: InstitutionTransfer, outbox: List<SwiftOutboxMessage>): Boolean =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                claim(session, transfer.paymentId, transfer.idempotencyKey, OUTCOME_TRANSFER).flatMap { won ->
                    if (!won) {
                        Uni.createFrom().item(false)
                    } else {
                        session.merge(InstitutionTransferEntity().apply { applyFrom(transfer) })
                            .flatMap { persistAll(outbox) }
                            .replaceWith(true)
                    }
                }
            }
        }.awaitSuspending()

    override suspend fun recordRejectionOnce(
        rejection: InstitutionTransferRejection,
        outbox: SwiftOutboxMessage,
    ): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            claim(session, rejection.paymentId, rejection.idempotencyKey, OUTCOME_REJECTION).flatMap { won ->
                if (!won) {
                    Uni.createFrom().item(false)
                } else {
                    session.merge(rejection.toEntity())
                        .flatMap { outboxRepoImpl.persistWithinCurrentTransaction(outbox) }
                        .replaceWith(true)
                }
            }
        }
    }.awaitSuspending()

    /** Optimistic: only the caller holding the current version, and only once, may take it. */
    override suspend fun takeResendMarker(transfer: InstitutionTransfer): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Any>(
                "UPDATE swift_institution_transfers SET resend_taken = TRUE, version = version + 1 " +
                    "WHERE id = ?1 AND version = ?2 AND resend_taken = FALSE " +
                    "AND status IN ('SUBMITTING', 'SUBMISSION_UNKNOWN')",
            ).setParameter(1, transfer.id).setParameter(2, transfer.version).executeUpdate().map { it == 1 }
        }
    }.awaitSuspending()

    private fun persistAll(outbox: List<SwiftOutboxMessage>): Uni<Unit> =
        outbox.fold(Uni.createFrom().item(Unit)) { chain, message ->
            chain.flatMap { outboxRepoImpl.persistWithinCurrentTransaction(message).replaceWith(Unit) }
        }
}

private fun InstitutionTransferEntity.applyFrom(t: InstitutionTransfer) {
    id = t.id
    paymentId = t.paymentId
    idempotencyKey = t.idempotencyKey
    rail = t.rail
    currency = t.currency
    amountMinorUnits = t.amountMinorUnits
    valueDate = t.valueDate
    debtorBic = t.debtor.bic
    debtorAccount = t.debtor.accountNumber
    debtorGlAccountingClass = t.debtorGlAccountingClass
    creditorBic = t.creditor.bic
    creditorAccount = t.creditor.accountNumber
    creditorName = t.creditorName
    intermediaryBic = t.intermediaryBic
    remittanceInformation = t.remittanceInformation
    dealIds = t.dealIds.joinToString(",")
    flowIds = t.flowIds.joinToString(",")
    releasedBy = t.releasedBy
    releasedAt = t.releasedAt
    synthetic = t.synthetic
    status = t.status
    uetr = t.uetr
    reasonCode = t.reasonCode
    reason = t.reason
    rawPacs009 = t.rawPacs009
    rawMt202 = t.rawMt202
    createdAt = t.createdAt
    updatedAt = t.updatedAt
    ssiVersion = t.ssiVersion
    messageId = t.messageId
    messageCreatedAt = t.messageCreatedAt
}

private fun InstitutionTransferRejection.toEntity() = InstitutionTransferRejectionEntity().also {
    it.paymentId = paymentId
    it.idempotencyKey = idempotencyKey
    it.reasonCode = reasonCode
    it.reason = reason
    it.synthetic = synthetic
    it.createdAt = createdAt
}

private fun InstitutionTransferEntity.toDomain(): InstitutionTransfer = InstitutionTransfer(
    id = id,
    paymentId = paymentId,
    idempotencyKey = idempotencyKey,
    rail = rail,
    amount = moneyOf(amountMinorUnits, currency),
    valueDate = valueDate,
    debtor = InstitutionParty(debtorBic, debtorAccount),
    debtorGlAccountingClass = debtorGlAccountingClass,
    creditor = InstitutionParty(creditorBic, creditorAccount),
    creditorName = creditorName,
    intermediaryBic = intermediaryBic,
    remittanceInformation = remittanceInformation,
    dealIds = ids(dealIds),
    flowIds = ids(flowIds),
    releasedBy = releasedBy,
    releasedAt = releasedAt,
    synthetic = synthetic,
    status = status,
    uetr = uetr,
    reasonCode = reasonCode,
    reason = reason,
    rawPacs009 = rawPacs009,
    rawMt202 = rawMt202,
    createdAt = createdAt,
    updatedAt = updatedAt,
    version = version,
    ssiVersion = ssiVersion,
    messageId = messageId,
    messageCreatedAt = messageCreatedAt,
)

private fun moneyOf(minorUnits: Long, currency: String): Money = SwiftMessage.moneyOfMinorUnits(minorUnits, currency)

private fun ids(joined: String): List<UUID> =
    if (joined.isBlank()) emptyList() else joined.split(',').map(UUID::fromString)
