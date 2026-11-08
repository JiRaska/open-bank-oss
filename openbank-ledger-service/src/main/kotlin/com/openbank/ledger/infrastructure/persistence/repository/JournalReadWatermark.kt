// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.persistence.repository

import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.LocalDate

/**
 * Journals are append-only, including reversals. Their committed count through a date is a
 * monotonic read watermark. Concurrent writers share a lock; only the short close capture takes
 * it exclusively. A timestamp assigned before commit cannot provide this guarantee.
 */
@ApplicationScoped
class JournalReadWatermark {
    suspend fun countThrough(date: LocalDate): Long = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Long>(COUNT_SQL).setParameter("d", date).singleResult
        }
    }.awaitSuspending()

    companion object {
        const val COUNT_SQL = "SELECT count(*) FROM journal_entries WHERE entry_date <= :d"
        private const val KEY = 7114932801L

        fun lockWriter(session: Mutiny.Session): Uni<Any> = session.createNativeQuery<Any>(
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock_shared($KEY)) l",
        ).singleResult

        fun lockCapture(session: Mutiny.Session): Uni<Any> = session.createNativeQuery<Any>(
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock($KEY)) l",
        ).singleResult
    }
}
