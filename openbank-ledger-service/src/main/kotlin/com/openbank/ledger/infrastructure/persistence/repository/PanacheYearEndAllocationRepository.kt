// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.YearEndAllocationRepository
import com.openbank.ledger.domain.model.YearEndAllocationRecord
import com.openbank.ledger.domain.model.YearEndAllocationStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Native-SQL store for `ledger_year_close_allocation` (V42); no entity, so nothing is overwritten. */
@ApplicationScoped
class PanacheYearEndAllocationRepository : YearEndAllocationRepository {

    override suspend fun findPending(fiscalYear: Int): YearEndAllocationRecord? {
        val rows: List<*> = Panache.withSession {
            Panache.getSession().flatMap { s ->
                s.createNativeQuery<Any>(
                    "SELECT idempotency_key, fiscal_year, status, allocated_by, journal_id " +
                        "FROM ledger_year_close_allocation WHERE fiscal_year = :y AND status = 'PENDING'",
                ).setParameter("y", fiscalYear).resultList
            }
        }.awaitSuspending()
        return rows.firstOrNull()?.let { row ->
            val c = row as Array<*>
            YearEndAllocationRecord(
                idempotencyKey = c[0] as String,
                fiscalYear = (c[1] as Number).toInt(),
                status = YearEndAllocationStatus.valueOf(c[2] as String),
                allocatedBy = c[3] as String,
                journalId = c[4]?.let { UUID.fromString(it.toString()) },
            )
        }
    }

    override suspend fun exists(idempotencyKey: String): Boolean = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createNativeQuery<Any>("SELECT count(*) FROM ledger_year_close_allocation WHERE idempotency_key = :k")
                .setParameter("k", idempotencyKey).singleResult
        }
    }.awaitSuspending().let { (it as Number).toLong() > 0 }

    override suspend fun insertPending(
        fiscalYear: Int,
        idempotencyKey: String,
        allocatedBy: String,
        at: Instant,
    ): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { s ->
            // Lock the year-close row: attestation updates the same row, so the two serialise here.
            s.createNativeQuery<Any>(
                "SELECT status FROM ledger_year_close WHERE fiscal_year = :y FOR UPDATE",
            ).setParameter("y", fiscalYear).resultList.flatMap { rows ->
                if (rows.singleOrNull() != "DRAFT") {
                    Uni.createFrom().item(0)
                } else {
                    s.createNativeQuery<Any>(
                        "INSERT INTO ledger_year_close_allocation " +
                            "(idempotency_key, fiscal_year, status, allocated_by, requested_at) " +
                            "VALUES (:k, :y, 'PENDING', :by, :at) ON CONFLICT DO NOTHING",
                    ).setParameter("k", idempotencyKey).setParameter("y", fiscalYear)
                        .setParameter("by", allocatedBy).setParameter("at", at.atOffset(ZoneOffset.UTC))
                        .executeUpdate()
                }
            }
        }
    }.awaitSuspending() == 1

    override suspend fun markPosted(idempotencyKey: String, journalId: UUID, at: Instant) {
        complete(
            "UPDATE ledger_year_close_allocation SET status = 'POSTED', journal_id = :j, completed_at = :at " +
                "WHERE idempotency_key = :k AND status = 'PENDING'",
            idempotencyKey,
            at.atOffset(ZoneOffset.UTC),
            journalId,
        )
    }

    override suspend fun markAbandoned(idempotencyKey: String, at: Instant) {
        complete(
            "UPDATE ledger_year_close_allocation SET status = 'ABANDONED', completed_at = :at " +
                "WHERE idempotency_key = :k AND status = 'PENDING'",
            idempotencyKey,
            at.atOffset(ZoneOffset.UTC),
            null,
        )
    }

    private suspend fun complete(sql: String, key: String, at: OffsetDateTime, journalId: UUID?) {
        Panache.withTransaction {
            Panache.getSession().flatMap { s ->
                val q = s.createNativeQuery<Any>(sql).setParameter("k", key).setParameter("at", at)
                (if (journalId != null) q.setParameter("j", journalId) else q).executeUpdate()
            }
        }.awaitSuspending()
    }

    override suspend fun allocators(fiscalYear: Int): Set<String> = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createNativeQuery<Any>(
                "SELECT DISTINCT allocated_by FROM ledger_year_close_allocation " +
                    "WHERE fiscal_year = :y AND status <> 'ABANDONED'",
            ).setParameter("y", fiscalYear).resultList
        }
    }.awaitSuspending().map { it.toString() }.toSet()

    override suspend fun unattributedJournalKeys(fiscalYear: Int): List<String> = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createNativeQuery<Any>(UNATTRIBUTED_SQL).setParameter("prefix", "year-end-result:$fiscalYear:%")
                .resultList
        }
    }.awaitSuspending().map { it.toString() }

    private companion object {
        const val UNATTRIBUTED_SQL =
            "SELECT i.idempotency_key FROM ledger_idempotency i " +
                "LEFT JOIN ledger_year_close_allocation a ON a.idempotency_key = i.idempotency_key " +
                "AND a.status = 'POSTED' AND a.journal_id = i.journal_id " +
                "WHERE i.idempotency_key LIKE :prefix AND a.idempotency_key IS NULL ORDER BY 1"
    }
}
