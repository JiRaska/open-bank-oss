// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.persistence.repository

import com.openbank.aml.application.port.out.AttributionBacklog
import com.openbank.aml.application.port.out.MovementAttribution
import com.openbank.aml.application.port.out.MovementCountQuery
import com.openbank.aml.application.port.out.MovementCounterReader
import com.openbank.aml.application.port.out.MovementIntakeRepository
import com.openbank.aml.domain.monitoring.BookedMovement
import com.openbank.aml.domain.monitoring.MovementDirection
import com.openbank.aml.domain.monitoring.UncountedMovement
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Native SQL on purpose: every write here is an `INSERT ... ON CONFLICT`, which is what makes a
 * redelivered event and a concurrent tick harmless, and which a JPA `persist` cannot express. There
 * are no entities for these tables, so there is no implicit column naming to get wrong.
 */
@ApplicationScoped
class MovementIntakeRepositoryImpl :
    MovementIntakeRepository,
    MovementCounterReader {

    override suspend fun recordMovement(movement: BookedMovement, recordedAt: Instant): Boolean =
        Panache.withTransaction {
            mutate(
                """
            INSERT INTO aml_booked_movement
              (journal_entry_id, account_id, currency, direction, amount, transaction_id, entry_date,
               booked_at, source_event_id, recorded_at)
            VALUES (:journalEntryId, :accountId, :currency, :direction, :amount, :transactionId, :entryDate,
               :bookedAt, cast(nullif(:sourceEventId, '') as uuid), :recordedAt)
            ON CONFLICT (journal_entry_id, account_id, currency) DO NOTHING
            """,
                mapOf(
                    "journalEntryId" to movement.journalEntryId,
                    "accountId" to movement.accountId,
                    "currency" to movement.currency,
                    "direction" to movement.direction.name,
                    "amount" to movement.amount,
                    "transactionId" to movement.transactionId,
                    "entryDate" to movement.entryDate,
                    "bookedAt" to utc(movement.bookedAt),
                    "sourceEventId" to (movement.sourceEventId?.toString() ?: ""),
                    "recordedAt" to utc(recordedAt),
                ),
            )
        }.awaitSuspending() == 1

    override suspend fun recordOrigin(transactionId: UUID, rail: String, recordedAt: Instant): Boolean =
        Panache.withTransaction {
            mutate(
                """
                INSERT INTO aml_movement_origin (transaction_id, rail, recorded_at)
                VALUES (:transactionId, :rail, :recordedAt)
                ON CONFLICT (transaction_id) DO NOTHING
                """,
                mapOf("transactionId" to transactionId, "rail" to rail, "recordedAt" to utc(recordedAt)),
            )
        }.awaitSuspending() == 1

    override suspend fun findUncounted(limit: Int): List<UncountedMovement> = Panache.withSession {
        Panache.getSession().chain { session ->
            session.createNativeQuery(
                """
                SELECT m.journal_entry_id, m.account_id, m.currency, m.direction, m.amount, m.transaction_id,
                       m.booked_at, m.recorded_at, p.party_id, o.rail
                  FROM aml_booked_movement m
                  LEFT JOIN aml_account_party p ON p.account_id = m.account_id
                  LEFT JOIN aml_movement_origin o ON o.transaction_id = m.transaction_id
                 WHERE m.counted_at IS NULL
                 ORDER BY m.last_attempt_at NULLS FIRST, m.recorded_at
                 LIMIT :limit
                """.trimIndent(),
                Array<Any>::class.java,
            ).setParameter("limit", limit).resultList
        }
    }.awaitSuspending().map { row ->
        UncountedMovement(
            journalEntryId = row[UNCOUNTED_JOURNAL] as UUID,
            accountId = row[UNCOUNTED_ACCOUNT] as UUID,
            currency = row[UNCOUNTED_CURRENCY] as String,
            direction = MovementDirection.valueOf(row[UNCOUNTED_DIRECTION] as String),
            amount = row[UNCOUNTED_AMOUNT] as BigDecimal,
            transactionId = row[UNCOUNTED_TRANSACTION] as UUID,
            bookedAt = instant(row[UNCOUNTED_BOOKED_AT]),
            recordedAt = instant(row[UNCOUNTED_RECORDED_AT]),
            knownPartyId = nullable(row, UNCOUNTED_PARTY) as UUID?,
            originRail = nullable(row, UNCOUNTED_RAIL) as String?,
        )
    }

    override suspend fun rememberParty(accountId: UUID, partyId: UUID, resolvedAt: Instant) {
        Panache.withTransaction {
            mutate(
                """
                INSERT INTO aml_account_party (account_id, party_id, resolved_at)
                VALUES (:accountId, :partyId, :resolvedAt)
                ON CONFLICT (account_id) DO NOTHING
                """,
                mapOf("accountId" to accountId, "partyId" to partyId, "resolvedAt" to utc(resolvedAt)),
            )
        }.awaitSuspending()
    }

    override suspend fun markAttempted(movement: UncountedMovement, attemptedAt: Instant) {
        Panache.withTransaction {
            mutate(
                """
                UPDATE aml_booked_movement SET last_attempt_at = :attemptedAt
                 WHERE journal_entry_id = :journalEntryId AND account_id = :accountId AND currency = :currency
                   AND counted_at IS NULL
                """,
                movementKey(movement) + ("attemptedAt" to utc(attemptedAt)),
            )
        }.awaitSuspending()
    }

    // The claim and the increments share one transaction: `counted_at IS NULL` is the compare-and-set
    // that makes a movement count once, and a failed increment rolls the claim back with it.
    override suspend fun count(attribution: MovementAttribution): Boolean = Panache.withTransaction {
        mutate(
            """
            UPDATE aml_booked_movement
               SET party_id = :partyId, rail = :rail, counted_at = :countedAt, last_attempt_at = :countedAt
             WHERE journal_entry_id = :journalEntryId AND account_id = :accountId AND currency = :currency
               AND counted_at IS NULL
            """,
            movementKey(attribution.movement) + mapOf(
                "partyId" to attribution.partyId,
                "rail" to attribution.rail,
                "countedAt" to utc(attribution.countedAt),
            ),
        ).chain { claimed ->
            if (claimed == 0) {
                Uni.createFrom().item(false)
            } else {
                attribution.bandCodes.fold(Uni.createFrom().item(0)) { chain, band ->
                    chain.chain { _ -> increment(attribution, band) }
                }.replaceWith(true)
            }
        }
    }.awaitSuspending()

    override suspend fun backlog(): AttributionBacklog = Panache.withSession {
        Panache.getSession().chain { session ->
            session.createNativeQuery(
                "SELECT count(*), min(recorded_at) FROM aml_booked_movement WHERE counted_at IS NULL",
                Array<Any>::class.java,
            ).singleResult
        }
    }.awaitSuspending().let { row ->
        AttributionBacklog((row[0] as Number).toLong(), nullable(row, 1)?.let { instant(it) })
    }

    // The optional filters are spliced as fixed SQL fragments, never as values: every value is bound.
    override suspend fun countMovements(query: MovementCountQuery): Long = Panache.withSession {
        Panache.getSession().chain { session ->
            val selection = session.createNativeQuery(
                """
                SELECT coalesce(sum(movement_count), 0) FROM aml_movement_counter
                 WHERE party_id = :partyId AND currency = :currency AND band_code = :bandCode
                   AND scheme_revision = :schemeRevision
                   AND bucket_start >= :fromInclusive AND bucket_start < :toExclusive
                """.trimIndent() +
                    (if (query.direction != null) " AND direction = :direction" else "") +
                    (if (query.rail != null) " AND rail = :rail" else ""),
                Number::class.java,
            )
                .setParameter("partyId", query.partyId)
                .setParameter("currency", query.currency)
                .setParameter("bandCode", query.bandCode)
                .setParameter("schemeRevision", query.schemeRevision)
                .setParameter("fromInclusive", utc(query.fromInclusive))
                .setParameter("toExclusive", utc(query.toExclusive))
            query.direction?.let { selection.setParameter("direction", it.name) }
            query.rail?.let { selection.setParameter("rail", it) }
            selection.singleResult
        }
    }.awaitSuspending().toLong()

    private fun increment(attribution: MovementAttribution, band: String): Uni<Int> = mutate(
        """
        INSERT INTO aml_movement_counter
          (party_id, currency, direction, rail, scheme_revision, bucket_start, band_code,
           bucket_seconds, movement_count, updated_at)
        VALUES (:partyId, :currency, :direction, :rail, :schemeRevision, :bucketStart, :bandCode,
           :bucketSeconds, 1, :updatedAt)
        ON CONFLICT (party_id, currency, direction, rail, scheme_revision, bucket_start, band_code)
        DO UPDATE SET movement_count = aml_movement_counter.movement_count + 1, updated_at = :updatedAt
        """,
        mapOf(
            "partyId" to attribution.partyId,
            "currency" to attribution.movement.currency,
            "direction" to attribution.movement.direction.name,
            "rail" to attribution.rail,
            "schemeRevision" to attribution.schemeRevision,
            "bucketStart" to utc(attribution.bucketStart),
            "bandCode" to band,
            "bucketSeconds" to attribution.bucketSeconds.toInt(),
            "updatedAt" to utc(attribution.countedAt),
        ),
    )

    private fun movementKey(movement: UncountedMovement): Map<String, Any> = mapOf(
        "journalEntryId" to movement.journalEntryId,
        "accountId" to movement.accountId,
        "currency" to movement.currency,
    )

    private fun mutate(sql: String, values: Map<String, Any>): Uni<Int> = Panache.getSession().chain { session ->
        val query = session.createNativeMutationQuery(sql.trimIndent())
        values.forEach { (name, value) -> query.setParameter(name, value) }
        query.executeUpdate()
    }

    private companion object {
        const val UNCOUNTED_JOURNAL = 0
        const val UNCOUNTED_ACCOUNT = 1
        const val UNCOUNTED_CURRENCY = 2
        const val UNCOUNTED_DIRECTION = 3
        const val UNCOUNTED_AMOUNT = 4
        const val UNCOUNTED_TRANSACTION = 5
        const val UNCOUNTED_BOOKED_AT = 6
        const val UNCOUNTED_RECORDED_AT = 7
        const val UNCOUNTED_PARTY = 8
        const val UNCOUNTED_RAIL = 9

        /** A native row is a Java `Object[]`: its elements can be SQL NULL whatever Kotlin's type says. */
        fun nullable(row: Array<Any>, index: Int): Any? = (row as Array<*>)[index]

        fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)

        fun instant(value: Any?): Instant = when (value) {
            is Instant -> value
            is OffsetDateTime -> value.toInstant()
            is java.sql.Timestamp -> value.toInstant()
            else -> error("unexpected timestamp type ${value?.javaClass}")
        }
    }
}
