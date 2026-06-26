// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.YearCloseRepository
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
        // Status flip + outbox row in ONE transaction (transactional outbox, ADR-0003/0050) —
        // either the attestation and its YearCloseAttested event both commit, or neither does.
        Panache.withTransaction {
            find("fiscalYear", record.fiscalYear).firstResult().flatMap { existing ->
                checkNotNull(existing) { "Year close ${record.fiscalYear} vanished during attestation" }
                existing.status = record.status.name
                existing.attestedBy = record.attestedBy
                existing.attestedAt = record.attestedAt
                existing.updatedAt = Instant.now(clock)
                outboxRepository.persistInTransaction(outbox).replaceWith(existing)
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
}
