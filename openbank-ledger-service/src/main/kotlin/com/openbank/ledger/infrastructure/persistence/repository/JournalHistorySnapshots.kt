// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class JournalHistoryManifest(
    val snapshotId: UUID,
    val persisted: Boolean,
    val accountId: UUID,
    val currency: String,
    val fromDate: LocalDate,
    val throughDate: LocalDate,
    val observedAt: Instant,
    val databaseView: String,
    val coverageScope: String,
    val openingAnchorScope: String,
    val accountLifetimeComplete: Boolean,
    val classifiedHistoryComplete: Boolean,
    val originTrust: String,
    val openingBooked: BigDecimal,
    val closingBooked: BigDecimal,
    val entryCount: Long,
    val missingOriginCount: Long,
    val recordsSha256: String,
    val canonicalization: String,
)

data class JournalHistoryRecord(val ordinal: Long, val canonicalJson: String)
data class JournalHistoryPage(
    val snapshotId: UUID,
    val records: List<JournalHistoryRecord>,
    val nextAfterOrdinal: Long?,
)

@ApplicationScoped
class JournalHistorySnapshots(private val mapper: ObjectMapper) {
    suspend fun capture(
        accountId: UUID,
        currency: String,
        fromDate: LocalDate,
        throughDate: LocalDate,
    ): JournalHistoryManifest {
        val id = Ids.newId()
        val json = Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ").executeUpdate()
                    .flatMap {
                        captureInSession(session, id, accountId, currency, fromDate, throughDate)
                    }
            }
        }.awaitSuspending()
        return mapper.readValue(json, JournalHistoryManifest::class.java)
    }

    internal fun captureInSession(
        session: Mutiny.Session,
        id: UUID,
        accountId: UUID,
        currency: String,
        fromDate: LocalDate,
        throughDate: LocalDate,
    ): Uni<String> = session.createNativeQuery(JournalHistorySnapshotSql.CREATE, String::class.java)
        .setParameter("snapshotId", id).setParameter("accountId", accountId)
        .setParameter("currency", currency).setParameter("fromDate", fromDate)
        .setParameter("throughDate", throughDate).singleResult

    suspend fun manifest(id: UUID): JournalHistoryManifest? {
        val rows = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery(JournalHistorySnapshotSql.READ, String::class.java)
                    .setParameter("snapshotId", id).resultList
            }
        }.awaitSuspending()
        return rows.firstOrNull()?.let { mapper.readValue(it, JournalHistoryManifest::class.java) }
    }

    suspend fun page(manifest: JournalHistoryManifest, afterOrdinal: Long, limit: Int): JournalHistoryPage? {
        val rows = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(JournalHistorySnapshotSql.PAGE)
                    .setParameter("snapshotId", manifest.snapshotId).setParameter("afterOrdinal", afterOrdinal)
                    .setParameter("pageSize", limit).resultList
            }
        }.awaitSuspending()
        val records = rows.map { row ->
            val cols = row as Array<*>
            JournalHistoryRecord((cols[0] as Number).toLong(), cols[1] as String)
        }
        // Source lifecycle can invalidate the immutable snapshot between manifest and page reads.
        // Never turn that race into a successful empty final page.
        if (records.isEmpty() && afterOrdinal < manifest.entryCount) return null
        val last = records.lastOrNull()?.ordinal ?: afterOrdinal
        return JournalHistoryPage(manifest.snapshotId, records, last.takeIf { it < manifest.entryCount })
    }

    suspend fun purgeUnavailableSources(): Int = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Any>(JournalHistorySnapshotSql.PURGE_UNAVAILABLE).executeUpdate()
        }
    }.awaitSuspending()
}
