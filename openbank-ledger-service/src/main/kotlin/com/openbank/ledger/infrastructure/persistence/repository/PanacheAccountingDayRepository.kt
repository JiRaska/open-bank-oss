// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.infrastructure.persistence.entity.AccountingDayEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class PanacheAccountingDayRepository(
    private val clock: Clock,
    private val outboxRepository: LedgerOutboxRepositoryImpl,
) : AccountingDayRepository,
    PanacheRepositoryBase<AccountingDayEntity, UUID> {

    override suspend fun findByDate(businessDate: LocalDate): AccountingDayRecord? = Panache.withSession {
        find("businessDate", businessDate).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findLatestOpen(): AccountingDayRecord? = Panache.withSession {
        find("status = ?1 order by businessDate desc", AccountingDayStatus.OPEN.name).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findRange(from: LocalDate, to: LocalDate): List<AccountingDayRecord> = Panache.withSession {
        find("businessDate >= ?1 and businessDate <= ?2 order by businessDate asc", from, to).list()
    }.awaitSuspending().map { it.toDomain() }

    /**
     * Insert + outbox row in ONE transaction (transactional outbox, ADR-0003/0050). A plain
     * `persist` is correct here and only here: the row is genuinely new, so Hibernate's
     * INSERT-only treatment of an application-assigned `@Id` is what we want. Every later state
     * change goes through [saveTransition]'s conditional UPDATE rather than `persist` — the
     * assigned-id persist-vs-merge trap (ADR-0126 D3) is avoided by never re-persisting.
     */
    override suspend fun saveOpened(record: AccountingDayRecord, outbox: OutboxMessage): AccountingDayRecord =
        Panache.withTransaction {
            persist(record.toEntity()).flatMap { saved ->
                outboxRepository.persistInTransaction(outbox).replaceWith(saved)
            }
        }.awaitSuspending().toDomain()

    /**
     * Conditional transition + outbox row in ONE transaction. The UPDATE is guarded on
     * `version = expectedVersion`, so of two operators racing the same transition exactly one
     * writes; the loser sees 0 rows affected and gets a conflict rather than a lost update.
     */
    override suspend fun saveTransition(
        record: AccountingDayRecord,
        expectedVersion: Long,
        outbox: OutboxMessage,
    ): AccountingDayRecord = Panache.withTransaction {
        update(
            "status = ?1, cutoffAt = ?2, tiedOutAt = ?3, lockedAt = ?4, lastTransitionBy = ?5, " +
                "version = ?6, updatedAt = ?7 where id = ?8 and version = ?9",
            record.status.name,
            record.cutoffAt,
            record.tiedOutAt,
            record.lockedAt,
            record.lastTransitionBy,
            record.version,
            Instant.now(clock),
            record.id,
            expectedVersion,
        ).flatMap { updated ->
            check(updated == 1) {
                "Accounting day ${record.businessDate} changed concurrently (expected version " +
                    "$expectedVersion) — retry against the current state"
            }
            outboxRepository.persistInTransaction(outbox).replaceWith(record)
        }
    }.awaitSuspending()

    private fun AccountingDayRecord.toEntity() = AccountingDayEntity().also {
        it.id = id
        it.businessDate = businessDate
        it.status = status.name
        it.openedAt = openedAt
        it.openedBy = openedBy
        it.cutoffAt = cutoffAt
        it.tiedOutAt = tiedOutAt
        it.lockedAt = lockedAt
        it.lastTransitionBy = lastTransitionBy
        it.version = version
        it.createdAt = Instant.now(clock)
        it.updatedAt = Instant.now(clock)
    }

    private fun AccountingDayEntity.toDomain() = AccountingDayRecord(
        id = id,
        businessDate = businessDate,
        status = AccountingDayStatus.valueOf(status),
        openedAt = openedAt,
        openedBy = openedBy,
        cutoffAt = cutoffAt,
        tiedOutAt = tiedOutAt,
        lockedAt = lockedAt,
        lastTransitionBy = lastTransitionBy,
        version = version,
    )
}
