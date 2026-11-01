// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRecordCorruptException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.libs.idempotency.RequestFingerprints
import com.openbank.libs.util.Hex
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.hibernate.reactive.mutiny.Mutiny
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A write and its durable response snapshot commit in one PostgreSQL transaction. Nested Panache
 * store calls join the context-bound session, as in PostgresClusterLock; no receipt expires and no
 * independent reservation survives rollback. Native HTTP/DB tests must prove that transaction edge.
 * The stored response can contain audit personal data; hashing the key does not anonymize it.
 */
@ApplicationScoped
class PostgresWriteReplay(private val mapper: ObjectMapper, private val identity: SecurityIdentity) {
    private val snapshotReader = mapper.readerFor(JsonNode::class.java)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    suspend fun execute(
        key: String?,
        path: String,
        request: Any?,
        method: String = "POST",
        action: suspend () -> Response,
    ): Response {
        val suppliedKey = requireNotNull(key) { "Idempotency-Key header is required" }
        val scoped = IdempotencyScope(SERVICE, identity.principal.name).storeKey(suppliedKey)
        val digest = MessageDigest.getInstance("SHA-256").digest(scoped.toByteArray(Charsets.UTF_8))
        val receiptKey = Hex.lower(digest)
        val lockKey = ByteBuffer.wrap(digest).long
        val fingerprint = RequestFingerprints.of(mapper, method, path, request)
        return Panache.withTransaction {
            Panache.getSession().chain { session ->
                runLocked(session, lockKey, receiptKey, fingerprint, action)
            }
        }.awaitSuspending()
    }

    private fun runLocked(
        session: Mutiny.Session,
        lockKey: Long,
        receiptKey: String,
        fingerprint: String,
        action: suspend () -> Response,
    ): Uni<Response> = session.createNativeQuery(TRY_LOCK_SQL, java.lang.Boolean::class.java)
        .setParameter("key", lockKey).singleResult
        .chain { acquired ->
            uni<Response>(CoroutineScope(Dispatchers.Unconfined)) {
                if (acquired != true) throw IdempotencyRequestInProgressException()
                val rows = session.createNativeQuery<Array<Any?>>(SELECT_SQL)
                    .setParameter("key", receiptKey).resultList.awaitSuspending()
                val stored = rows.firstOrNull()
                if (stored != null) {
                    replay(stored, fingerprint)
                } else {
                    val response = action()
                    val body = mapper.writeValueAsString(response.entity)
                    session.createNativeQuery<Int>(INSERT_SQL)
                        .setParameter("key", receiptKey)
                        .setParameter("hash", fingerprint)
                        .setParameter("status", response.status)
                        .setParameter("body", body).executeUpdate().awaitSuspending()
                    Response.fromResponse(response).entity(body).type(MediaType.APPLICATION_JSON).build()
                }
            }
        }

    private fun replay(row: Array<Any?>, fingerprint: String): Response {
        val hash = row.getOrNull(0) as? String ?: corruptReceipt()
        val status = (row.getOrNull(1) as? Number)?.toInt() ?: corruptReceipt()
        val body = row.getOrNull(2) as? String ?: corruptReceipt()
        if (hash != fingerprint) throw IdempotencyKeyReusedException()
        if (status !in MIN_HTTP_STATUS..MAX_HTTP_STATUS) corruptReceipt()
        validateSnapshot(body)
        return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON)
            .header("X-Idempotency-Replayed", "true").build()
    }

    private fun validateSnapshot(body: String) {
        try {
            if (snapshotReader.readValue<JsonNode>(body) == null) corruptReceipt()
        } catch (_: JsonProcessingException) {
            corruptReceipt()
        }
    }

    private fun corruptReceipt(): Nothing = throw IdempotencyRecordCorruptException("Stored write receipt is invalid")

    private companion object {
        const val SERVICE = "pension-fund-service"
        const val MIN_HTTP_STATUS = 100
        const val MAX_HTTP_STATUS = 599
        const val TRY_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(:key)"
        const val SELECT_SQL =
            "SELECT request_hash, status_code, response_json FROM pension_fund_write_replay WHERE key_hash = :key"
        const val INSERT_SQL =
            "INSERT INTO pension_fund_write_replay (key_hash, request_hash, status_code, response_json) " +
                "VALUES (:key, :hash, :status, :body)"
    }
}
