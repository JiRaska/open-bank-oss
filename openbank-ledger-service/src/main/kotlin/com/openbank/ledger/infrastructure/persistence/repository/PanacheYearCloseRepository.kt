// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.YearCloseRepository
import com.openbank.ledger.application.usecase.YearCloseConflictException
import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.ledger.domain.model.YearCloseStatus
import com.openbank.ledger.infrastructure.persistence.entity.YearCloseEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class PanacheYearCloseRepository(private val clock: Clock, private val outboxRepository: LedgerOutboxRepositoryImpl) :
    YearCloseRepository,
    PanacheRepositoryBase<YearCloseEntity, UUID> {

    override suspend fun findByFiscalYear(fiscalYear: Int): YearCloseRecord? = Panache.withSession {
        find("fiscalYear", fiscalYear).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun isFiscalYearAttested(fiscalYear: Int): Boolean = Panache.withSession {
        count("fiscalYear = ?1 and status = ?2", fiscalYear, YearCloseStatus.ATTESTED.name)
    }.awaitSuspending() > 0

    override suspend fun saveDraft(record: YearCloseRecord): YearCloseRecord = Panache.withTransaction {
        find("fiscalYear", record.fiscalYear).firstResult().flatMap { existing ->
            if (existing == null) {
                persist(record.toEntity())
            } else {
                // Refresh the existing DRAFT in place; the unique fiscal_year row is stable.
                existing.status = record.status.name
                existing.computedAt = record.computedAt
                existing.totalDebits = record.totalDebits
                existing.totalCredits = record.totalCredits
                existing.accountCount = record.accountCount
                existing.contentHash = record.contentHash
                // Refresh updates the maker to the actor who produced THIS snapshot (four-eyes #869).
                existing.draftedBy = record.draftedBy
                existing.updatedAt = Instant.now(clock)
                Uni.createFrom().item(existing)
            }
        }
    }.awaitSuspending().toDomain()

    override suspend fun saveAttested(record: YearCloseRecord, outbox: OutboxMessage): YearCloseRecord =
        // A CONDITIONAL flip, not read-then-write: the row must still be DRAFT with the reviewed
        // hash, the attestor may not be any allocator, and no closing journal may be unattributed
        // (ADR-0096 D3). Allocation holds FOR UPDATE on this same row for its whole transaction, so the two serialise and a check made earlier in the use case cannot go
        // stale in between (TOCTOU). The flip and its outbox row commit together (ADR-0003/0050).
        Panache.withTransaction {
            Panache.getSession().flatMap { s ->
                s.createNativeQuery<Any>(CONDITIONAL_ATTEST_SQL)
                    .setParameter("by", record.attestedBy)
                    .setParameter("at", record.attestedAt?.atOffset(java.time.ZoneOffset.UTC))
                    .setParameter("now", Instant.now(clock).atOffset(java.time.ZoneOffset.UTC))
                    .setParameter("y", record.fiscalYear)
                    .setParameter("hash", record.contentHash)
                    .setParameter("prefix", "year-end-result:${record.fiscalYear}:%")
                    .executeUpdate()
            }.flatMap { updated ->
                if (updated != 1) {
                    Uni.createFrom().failure(
                        YearCloseConflictException(
                            "Year close ${record.fiscalYear} changed while attesting (no longer DRAFT with the " +
                                "reviewed hash, the attestor allocated, or a closing journal is unattributed) — re-read and retry",
                        ),
                    )
                } else {
                    outboxRepository.persistInTransaction(outbox)
                        .flatMap { find("fiscalYear", record.fiscalYear).firstResult() }
                        .map { checkNotNull(it) { "Year close ${record.fiscalYear} vanished during attestation" } }
                }
            }
        }.awaitSuspending().toDomain()

    private fun YearCloseRecord.toEntity() = YearCloseEntity().also {
        it.id = id
        it.fiscalYear = fiscalYear
        it.status = status.name
        it.computedAt = computedAt
        it.totalDebits = totalDebits
        it.totalCredits = totalCredits
        it.accountCount = accountCount
        it.contentHash = contentHash
        it.draftedBy = draftedBy
        it.attestedBy = attestedBy
        it.attestedAt = attestedAt
        it.createdAt = Instant.now(clock)
        it.updatedAt = Instant.now(clock)
    }

    private fun YearCloseEntity.toDomain() = YearCloseRecord(
        id = id,
        fiscalYear = fiscalYear,
        status = YearCloseStatus.valueOf(status),
        computedAt = computedAt,
        totalDebits = totalDebits,
        totalCredits = totalCredits,
        accountCount = accountCount,
        contentHash = contentHash,
        draftedBy = draftedBy,
        attestedBy = attestedBy,
        attestedAt = attestedAt,
    )

    private companion object {
        // Refuses, atomically with the flip: a changed row or hash, an attestor who allocated, and any
        // closing journal with no attribution. Allocation holds FOR UPDATE on this row for its whole
        // transaction, so this UPDATE waits for it and then sees its journal and attribution together.
        const val CONDITIONAL_ATTEST_SQL =
            "UPDATE ledger_year_close SET status = 'ATTESTED', attested_by = :by, attested_at = :at, " +
                "updated_at = :now WHERE fiscal_year = :y AND status = 'DRAFT' AND content_hash = :hash " +
                "AND NOT EXISTS (SELECT 1 FROM ledger_year_close_allocation a " +
                "WHERE a.fiscal_year = :y AND a.allocated_by = :by) " +
                "AND NOT EXISTS (SELECT 1 FROM ledger_idempotency i WHERE " +
                PanacheYearEndAllocationRepository.UNATTRIBUTED_CONDITION + ")"
    }
}
