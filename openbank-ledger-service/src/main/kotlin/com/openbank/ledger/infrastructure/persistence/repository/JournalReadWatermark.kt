// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.persistence.repository

import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.LocalDate

data class JournalPopulation(val count: Long, val fingerprint: String)

/**
 * A count and order-independent SHA-256 multiset fingerprint of committed journal identities.
 * Equal counts cannot conceal archive removal plus new postings. Four exact numeric sums of the
 * SHA-256 limbs avoid sorting/materialising the whole journal set. This is a consistency check,
 * not a signature or an external-source attestation.
 */
@ApplicationScoped
class JournalReadWatermark {
    suspend fun countThrough(date: LocalDate): Long = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Long>(COUNT_SQL).setParameter("d", date).singleResult
        }
    }.awaitSuspending()

    suspend fun populationThrough(date: LocalDate): JournalPopulation = Panache.withSession {
        Panache.getSession().flatMap { readInTransaction(it, date) }
    }.awaitSuspending()

    companion object {
        const val COUNT_SQL = "SELECT count(*) FROM journal_entries WHERE entry_date <= :d"
        private const val KEY = 7114932801L
        private const val HASH_LIMBS = 4
        private const val HEX_LIMB_LENGTH = 16
        private val fingerprint = "encode(digest(count(*)::text || ':' || " +
            (0 until HASH_LIMBS).joinToString(" || ':' || ") { limb ->
                "COALESCE(sum((('x' || substring(encode(digest(j.id::text, 'sha256'), 'hex') " +
                    "from ${limb * HEX_LIMB_LENGTH + 1} for $HEX_LIMB_LENGTH))::bit(64)::bigint)::numeric), 0)::text"
            } + ", 'sha256'), 'hex')"

        /** Only these two compile-time expressions may be embedded; no request input is SQL. */
        fun fingerprintQuery(asOf: String): String {
            require(asOf in setOf(":d", "e.business_date")) { "Unsupported journal fingerprint date expression" }
            return "SELECT $fingerprint FROM journal_entries j WHERE j.entry_date <= $asOf"
        }

        fun readInTransaction(session: Mutiny.Session, date: LocalDate): Uni<JournalPopulation> =
            session.createNativeQuery<Array<Any>>(
                "SELECT count(*), $fingerprint FROM journal_entries j WHERE j.entry_date <= :d",
            ).setParameter("d", date).singleResult.map {
                JournalPopulation((it[0] as Number).toLong(), it[1].toString())
            }

        fun lockWriter(session: Mutiny.Session): Uni<Any> = session.createNativeQuery<Any>(
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock_shared($KEY)) l",
        ).singleResult

        fun lockCapture(session: Mutiny.Session): Uni<Any> = session.createNativeQuery<Any>(
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock($KEY)) l",
        ).singleResult
    }
}
