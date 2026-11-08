// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.persistence

import com.openbank.tax.application.port.out.CeuRegisterRepository
import com.openbank.tax.domain.ceu.CeuRole
import com.openbank.tax.domain.ceu.RegisterFact
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "ceu_register_fact")
class CeuRegisterFactEntity : PanacheEntityBase {
    @Id
    @Column(name = "event_id")
    var eventId: UUID = UUID(0L, 0L)

    @Column(name = "fact_type", nullable = false)
    var factType: String = ""

    @Column(name = "account_id", nullable = false)
    var accountId: UUID = UUID(0L, 0L)

    @Column(name = "relation_id")
    var relationId: UUID? = null

    @Column(name = "party_id")
    var partyId: UUID? = null

    @Column(name = "role")
    var role: String? = null

    @Column(name = "account_number")
    var accountNumber: String? = null

    @Column(name = "currency")
    var currency: String? = null

    // Null defaults, always set before persist — an EPOCH default would be a lie every test agrees with.
    @Column(name = "effective_on", nullable = false)
    var effectiveOn: LocalDate? = null

    @Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant? = null

    @Column(name = "synthetic", nullable = false)
    var synthetic: Boolean = false
}

@ApplicationScoped
class PanacheCeuRegisterRepository :
    CeuRegisterRepository,
    PanacheRepositoryBase<CeuRegisterFactEntity, UUID> {

    /** Idempotent on the producing event id: a Kafka redelivery is a no-op. */
    override suspend fun append(fact: RegisterFact): Boolean = Panache.withTransaction {
        findById(fact.eventId).onItem().transformToUni { existing ->
            if (existing != null) Uni.createFrom().item(false) else persist(fact.toEntity()).map { true }
        }
    }.awaitSuspending()

    override suspend fun startOf(relationId: UUID): RegisterFact.RelationStarted? = Panache.withSession {
        find("relationId = ?1 and factType = ?2 order by recordedAt", relationId, STARTED).firstResult()
    }.awaitSuspending()?.toDomain() as? RegisterFact.RelationStarted

    override suspend fun factsUpTo(validOn: LocalDate, knownAt: Instant): List<RegisterFact> = Panache.withSession {
        list("effectiveOn <= ?1 and recordedAt <= ?2", validOn, knownAt)
    }.awaitSuspending().map { it.toDomain() }

    private fun RegisterFact.toEntity() = CeuRegisterFactEntity().also { e ->
        e.eventId = eventId
        e.accountId = accountId
        e.effectiveOn = effectiveOn
        e.recordedAt = recordedAt
        e.synthetic = synthetic
        when (this) {
            is RegisterFact.AccountOpened -> {
                e.factType = OPENED
                e.accountNumber = accountNumber
                e.currency = currency
            }
            is RegisterFact.AccountClosed -> e.factType = CLOSED
            is RegisterFact.RelationStarted -> {
                e.factType = STARTED
                e.relationId = relationId
                e.partyId = partyId
                e.role = role.name
            }
            is RegisterFact.RelationEnded -> {
                e.factType = ENDED
                e.relationId = relationId
            }
        }
    }

    private fun CeuRegisterFactEntity.toDomain(): RegisterFact {
        val on = checkNotNull(effectiveOn) { "ceu fact $eventId has no effective_on" }
        val at = checkNotNull(recordedAt) { "ceu fact $eventId has no recorded_at" }
        return when (factType) {
            OPENED -> RegisterFact.AccountOpened(
                eventId,
                accountId,
                checkNotNull(accountNumber),
                checkNotNull(currency),
                on,
                at,
                synthetic,
            )
            CLOSED -> RegisterFact.AccountClosed(eventId, accountId, on, at, synthetic)
            STARTED -> RegisterFact.RelationStarted(
                eventId,
                checkNotNull(relationId),
                accountId,
                checkNotNull(partyId),
                CeuRole.valueOf(checkNotNull(role)),
                on,
                at,
                synthetic,
            )
            ENDED -> RegisterFact.RelationEnded(eventId, checkNotNull(relationId), accountId, on, at, synthetic)
            else -> error("ceu fact $eventId has unknown type $factType")
        }
    }

    private companion object {
        const val OPENED = "ACCOUNT_OPENED"
        const val CLOSED = "ACCOUNT_CLOSED"
        const val STARTED = "RELATION_STARTED"
        const val ENDED = "RELATION_ENDED"
    }
}
