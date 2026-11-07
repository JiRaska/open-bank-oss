// SPDX-License-Identifier: Apache-2.0
package com.openbank.ledger.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.pgclient.PgException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.Response
import org.hibernate.exception.ConstraintViolationException
import org.hibernate.reactive.mutiny.Mutiny
import java.math.BigDecimal
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class HistoryCaptureParameters(
    val accountId: UUID,
    val currency: String,
    val from: LocalDate,
    val through: LocalDate,
)
data class HistoryCaptureResult(val manifest: JournalHistoryManifest, val replayed: Boolean)

/** One PostgreSQL commit contains both the source cache and its bounded replay binding. */
@ApplicationScoped
class JournalHistoryCaptureReceipts(private val snapshots: JournalHistorySnapshots, private val mapper: ObjectMapper) {
    suspend fun capture(keyHash: String, requestHash: String, input: HistoryCaptureParameters): HistoryCaptureResult =
        try {
            Panache.withTransaction {
                Panache.getSession().flatMap { session ->
                    session.createNativeQuery<Any>("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ").executeUpdate()
                        .flatMap { reserve(session, keyHash) }
                        .flatMap { existingOrCapture(session, keyHash, requestHash, input) }
                }
            }.awaitSuspending()
        } catch (failure: PersistenceException) {
            refuseConcurrentRace(failure)
        } catch (failure: PgException) {
            refuseConcurrentRace(failure)
        }

    private fun reserve(session: Mutiny.Session, key: String): Uni<Int> = session.createNativeQuery(
        "SELECT pg_try_advisory_xact_lock(hashtextextended(:key,0))",
        Boolean::class.java,
    ).setParameter("key", key).singleResult.flatMap { acquired ->
        if (!acquired) {
            Uni.createFrom().failure(IdempotencyRequestInProgressException())
        } else {
            session.createNativeQuery<Any>(
                "DELETE FROM journal_history_capture_receipts WHERE scope_key_hash = :key AND expires_at <= statement_timestamp()",
            ).setParameter("key", key).executeUpdate()
        }
    }

    private fun existingOrCapture(
        session: Mutiny.Session,
        key: String,
        fingerprint: String,
        input: HistoryCaptureParameters,
    ): Uni<HistoryCaptureResult> = session.createNativeQuery(
        "SELECT to_jsonb(r)::text FROM journal_history_capture_receipts r WHERE scope_key_hash = :key",
        String::class.java,
    ).setParameter("key", key).resultList.flatMap { rows ->
        val receipt = rows.firstOrNull()?.let(mapper::readTree)
        if (receipt != null) {
            replay(session, receipt, fingerprint, input)
        } else {
            snapshots.captureInSession(session, Ids.newId(), input.accountId, input.currency, input.from, input.through)
                .flatMap { json ->
                    val manifest = mapper.readValue(json, JournalHistoryManifest::class.java)
                    save(session, key, fingerprint, manifest).replaceWith(HistoryCaptureResult(manifest, false))
                }
        }
    }

    private fun replay(
        session: Mutiny.Session,
        receipt: JsonNode,
        fingerprint: String,
        input: HistoryCaptureParameters,
    ): Uni<HistoryCaptureResult> {
        if (receipt.path("request_hash").asText() != fingerprint) {
            return Uni.createFrom().failure(IdempotencyKeyReusedException())
        }
        if (receipt.path("result_kind").asText() == "EMPTY") {
            return Uni.createFrom().item(HistoryCaptureResult(emptyManifest(receipt, input), true))
        }
        val id = receipt.path("snapshot_id").takeUnless { it.isNull || it.isMissingNode }?.asText()
            ?: return gone()
        return session.createNativeQuery(JournalHistorySnapshotSql.READ, String::class.java)
            .setParameter("snapshotId", UUID.fromString(id)).resultList.flatMap { rows ->
                val manifest = rows.firstOrNull()?.let { mapper.readValue(it, JournalHistoryManifest::class.java) }
                if (manifest == null) gone() else Uni.createFrom().item(HistoryCaptureResult(manifest, true))
            }
    }

    private fun save(
        session: Mutiny.Session,
        key: String,
        fingerprint: String,
        manifest: JournalHistoryManifest,
    ): Uni<Int> = session.createNativeQuery<Any>(
        """INSERT INTO journal_history_capture_receipts
                (scope_key_hash,request_hash,result_kind,snapshot_id,empty_result_id,empty_observed_at,empty_database_view)
                VALUES (:key,:hash,:kind,cast(:snapshot AS uuid),cast(:emptyId AS uuid),
                    cast(cast(:observed AS text) AS timestamptz),:view)""",
    ).setParameter("key", key).setParameter("hash", fingerprint)
        .setParameter("kind", if (manifest.persisted) "PERSISTED" else "EMPTY")
        .setParameter("snapshot", manifest.snapshotId.takeIf { manifest.persisted })
        .setParameter("emptyId", manifest.snapshotId.takeUnless { manifest.persisted })
        .setParameter("observed", manifest.observedAt.toString().takeUnless { manifest.persisted })
        .setParameter("view", manifest.databaseView.takeUnless { manifest.persisted }).executeUpdate()

    private fun emptyManifest(receipt: JsonNode, input: HistoryCaptureParameters) = JournalHistoryManifest(
        snapshotId = UUID.fromString(receipt.path("empty_result_id").asText()), persisted = false,
        accountId = input.accountId, currency = input.currency, fromDate = input.from, throughDate = input.through,
        observedAt = OffsetDateTime.parse(receipt.path("empty_observed_at").asText()).toInstant(),
        databaseView = receipt.path("empty_database_view").asText(), coverageScope = "ATTACHED_LEDGER_VIEW_ONLY",
        openingAnchorScope = "SUM_OF_ATTACHED_BOOKED_LINES_BEFORE_FROM_DATE", accountLifetimeComplete = false,
        classifiedHistoryComplete = false, originTrust = "AUTHENTICATED_CALLER_ASSERTION_UNVERIFIED",
        openingBooked = BigDecimal.ZERO, closingBooked = BigDecimal.ZERO, entryCount = 0, missingOriginCount = 0,
        recordsSha256 = EMPTY_DIGEST, canonicalization = "POSTGRES_JSONB_TEXT_LINES_V1",
    )

    suspend fun purgeExpired(): Int = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Any>(
                "DELETE FROM journal_history_capture_receipts WHERE expires_at <= statement_timestamp()",
            ).executeUpdate()
        }
    }.awaitSuspending()

    private fun gone(): Uni<HistoryCaptureResult> = Uni.createFrom().failure(
        ClientErrorException(
            "captured source is no longer available; use a new key for a new observation",
            Response.Status.GONE,
        ),
    )

    private fun refuseConcurrentRace(failure: RuntimeException): Nothing {
        // This runs only AFTER transaction rollback. Never continue inside an aborted snapshot.
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(MAX_CAUSES).toList()
        val driver = causes.filterIsInstance<PgException>().firstOrNull()
        val state = driver?.sqlState ?: causes.filterIsInstance<SQLException>().firstOrNull()?.sqlState
        val constraint =
            driver?.constraint ?: causes.filterIsInstance<ConstraintViolationException>().firstOrNull()?.constraintName
        val receiptConflict = state == "23505" && constraint == "journal_history_capture_receipts_pkey"
        if (state == "40001" || state == "40P01" || receiptConflict) {
            throw IdempotencyRequestInProgressException()
        }
        throw failure
    }

    private companion object {
        const val MAX_CAUSES = 16
        const val EMPTY_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
