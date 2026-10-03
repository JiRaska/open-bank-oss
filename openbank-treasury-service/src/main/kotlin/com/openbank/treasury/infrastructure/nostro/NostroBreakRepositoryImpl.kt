// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.nostro

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.treasury.application.port.out.NostroBreakRepository
import com.openbank.treasury.application.port.out.TreasuryOutboxRepository
import com.openbank.treasury.domain.model.BreakChanges
import com.openbank.treasury.domain.model.BreakSide
import com.openbank.treasury.domain.model.NostroBreak
import com.openbank.treasury.domain.model.Side
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `nostro_breaks` (V14). A resolution change updates the LOADED row inside the transaction, never
 * `persist` of a new entity: the break key is unique, and an INSERT of a stored key fails at flush.
 */
@ApplicationScoped
class NostroBreakRepositoryImpl(private val outbox: TreasuryOutboxRepository) :
    NostroBreakRepository,
    PanacheRepository<NostroBreakEntity> {

    override suspend fun inScope(
        glCode: String,
        statementUuid: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<NostroBreak> = Panache.withSession {
        list(
            "glCode = ?1 and (statementUuid = ?2 or (side = ?3 and bookingDate >= ?4 and bookingDate <= ?5))",
            glCode,
            statementUuid,
            BreakSide.LEDGER.name,
            from,
            to,
        )
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun apply(changes: BreakChanges) {
        val updates = (changes.resolved + changes.reopened).associateBy { it.id }
        Panache.withTransaction {
            val inserted: Uni<Void> =
                if (changes.opened.isEmpty()) {
                    Uni.createFrom().voidItem()
                } else {
                    persist(changes.opened.map { b -> NostroBreakEntity().also { it.fill(b) } })
                }
            inserted.flatMap {
                if (updates.isEmpty()) {
                    Uni.createFrom().voidItem()
                } else {
                    list("breakUuid in ?1", updates.keys.toList()).invoke { rows ->
                        rows.forEach { row -> row.resolvedOn = updates.getValue(row.breakUuid).resolvedOn }
                    }.replaceWithVoid()
                }
            }
        }.awaitSuspending()
    }

    override suspend fun byIban(iban: String, includeResolved: Boolean): List<NostroBreak> = Panache.withSession {
        if (includeResolved) list("iban", iban) else list("iban = ?1 and resolvedOn is null", iban)
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun open(): List<NostroBreak> =
        Panache.withSession { list("resolvedOn is null") }.awaitSuspending().map { it.toDomain() }

    /**
     * The conditional UPDATE is the claim: of two pods sweeping at once (an Argo canary runs both),
     * exactly one sees one row updated and writes the event; the other writes nothing.
     */
    override suspend fun markAlerted(breakId: UUID, alertedAt: Instant, eventType: String, payload: String): Boolean =
        Panache.withTransaction {
            update("alertedAt = ?1 where breakUuid = ?2 and alertedAt is null", alertedAt, breakId).flatMap { n ->
                if (n != 1) {
                    Uni.createFrom().item(false)
                } else {
                    outbox.persistInTransaction(
                        OutboxMessage(
                            aggregateId = breakId,
                            eventType = eventType,
                            payload = payload,
                            createdAt = alertedAt,
                        ),
                    ).replaceWith(true)
                }
            }
        }.awaitSuspending()

    private fun NostroBreakEntity.fill(b: NostroBreak) {
        breakUuid = b.id
        breakKey = b.breakKey
        iban = b.iban
        glCode = b.glCode
        currency = b.currency
        side = b.side.name
        ourSide = b.ourSide.name
        amount = b.amount
        bookingDate = b.bookingDate
        reference = b.reference
        statementUuid = b.statementUuid
        statementSequence = b.statementSequence
        ledgerLineId = b.ledgerLineId
        firstSeenOn = b.firstSeenOn
        resolvedOn = b.resolvedOn
        alertedAt = b.alertedAt
    }

    private fun NostroBreakEntity.toDomain() = NostroBreak(
        id = breakUuid,
        breakKey = breakKey,
        iban = iban,
        glCode = glCode,
        currency = currency,
        side = BreakSide.valueOf(side),
        ourSide = Side.valueOf(ourSide),
        amount = amount,
        bookingDate = bookingDate,
        reference = reference,
        statementUuid = statementUuid,
        statementSequence = statementSequence,
        ledgerLineId = ledgerLineId,
        firstSeenOn = firstSeenOn,
        resolvedOn = resolvedOn,
        alertedAt = alertedAt,
    )
}
