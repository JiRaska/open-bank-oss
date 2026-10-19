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

    override suspend fun recordRejectionOnce(
        rejection: InstitutionTransferRejection,
        outbox: SwiftOutboxMessage,
    ): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.find(InstitutionTransferRejectionEntity::class.java, rejection.paymentId).flatMap { existing ->
                if (existing != null) {
                    Uni.createFrom().item(false)
                } else {
                    session.merge(rejection.toEntity())
                        .flatMap { outboxRepoImpl.persistWithinCurrentTransaction(outbox) }
                        .replaceWith(true)
                }
            }
        }
    }.awaitSuspending()

    private fun persistAll(outbox: List<SwiftOutboxMessage>): Uni<Unit> =
        outbox.fold(Uni.createFrom().item(Unit)) { chain, message ->
            chain.flatMap { outboxRepoImpl.persistWithinCurrentTransaction(message).replaceWith(Unit) }
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
    )

    private fun moneyOf(minorUnits: Long, currency: String): Money =
        SwiftMessage.moneyOfMinorUnits(minorUnits, currency)

    private fun ids(joined: String): List<UUID> =
        if (joined.isBlank()) emptyList() else joined.split(',').map(UUID::fromString)
}
