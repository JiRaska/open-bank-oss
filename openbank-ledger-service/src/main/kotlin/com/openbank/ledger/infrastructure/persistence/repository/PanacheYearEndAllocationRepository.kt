// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.YearEndAllocationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Native-SQL store for `ledger_year_close_allocation` (V42); insert-only. */
@ApplicationScoped
class PanacheYearEndAllocationRepository : YearEndAllocationRepository {

    override suspend fun <T> inYearCloseLock(fiscalYear: Int, block: suspend (isDraft: Boolean) -> T): T =
        Panache.withTransaction {
            Panache.getSession().flatMap { s ->
                s.createNativeQuery<Any>("SELECT status FROM ledger_year_close WHERE fiscal_year = :y FOR UPDATE")
                    .setParameter("y", fiscalYear).resultList
            }.flatMap { rows ->
                // Unconfined: the block starts on THIS thread, inside the Vert.x context that holds the
                // open session, so every nested Panache.withTransaction/withSession it reaches (the
                // journal save, the attribution insert) joins this transaction instead of opening one.
                CoroutineScope(Dispatchers.Unconfined).async { block(rows.singleOrNull() == "DRAFT") }.asUni()
            }
        }.awaitSuspending()

    override suspend fun record(
        fiscalYear: Int,
        idempotencyKey: String,
        allocatedBy: String,
        journalId: UUID,
        at: Instant,
    ) {
        Panache.withTransaction {
            Panache.getSession().flatMap { s ->
                s.createNativeQuery<Any>(
                    "INSERT INTO ledger_year_close_allocation " +
                        "(idempotency_key, fiscal_year, allocated_by, journal_id, allocated_at) VALUES (:k, :y, :by, :j, :at)",
                ).setParameter("k", idempotencyKey).setParameter("y", fiscalYear).setParameter("by", allocatedBy)
                    .setParameter("j", journalId).setParameter("at", at.atOffset(ZoneOffset.UTC)).executeUpdate()
            }
        }.awaitSuspending()
    }

    override suspend fun allocators(fiscalYear: Int): Set<String> = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createNativeQuery<Any>(
                "SELECT DISTINCT allocated_by FROM ledger_year_close_allocation WHERE fiscal_year = :y",
            )
                .setParameter("y", fiscalYear).resultList
        }
    }.awaitSuspending().map { it.toString() }.toSet()

    override suspend fun unattributedJournalKeys(fiscalYear: Int): List<String> = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createNativeQuery<Any>(
                UNATTRIBUTED_SQL,
            ).setParameter("prefix", "year-end-result:$fiscalYear:%").resultList
        }
    }.awaitSuspending().map { it.toString() }

    internal companion object {
        /** Shared with the conditional attest flip, so both refuse the same population. */
        const val UNATTRIBUTED_CONDITION =
            "i.idempotency_key LIKE :prefix AND NOT EXISTS (SELECT 1 FROM ledger_year_close_allocation a " +
                "WHERE a.idempotency_key = i.idempotency_key AND a.journal_id = i.journal_id)"
        const val UNATTRIBUTED_SQL =
            "SELECT i.idempotency_key FROM ledger_idempotency i WHERE $UNATTRIBUTED_CONDITION ORDER BY 1"
    }
}
