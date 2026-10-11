// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.RequestFingerprint
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jboss.resteasy.reactive.server.ServerResponseFilter
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Replay protection for every pension-service POST, PUT and PATCH (ADR-0334 S8). Each handler REQUIRES an
 * `Idempotency-Key` ([requireIdempotencyKey]); this filter makes the key mean something: the first
 * successful (2xx) response for a key is stored, and a retry with the same key — same caller, same
 * participant, same method and path — is answered from the store WITHOUT running the handler again.
 * So a client retry after a lost response cannot start a second application, credit a payment
 * twice or place a second order.
 *
 * Scope is `(principal, X-Customer-Party-Id, method, path, key)`: the edge relays every customer
 * under one service-account, so the party header is part of the scope, or two customers choosing
 * the same key would read each other's response. Failed attempts (4xx/5xx) are not stored, so a
 * corrected retry runs.
 *
 * The key is bound to the REQUEST, not only to the route: the stored row carries a fingerprint
 * ([RequestFingerprint] over method, path and the key-sorted JSON body). The same key with the same
 * body replays; the same key with a DIFFERENT body is refused 409 `IDEMPOTENCY_KEY_REUSED` (libs
 * mapper) — replaying would answer a different instruction with the first one's result. Rows written
 * before the fingerprint existed carry none and are replayed as before. Residual, as documented in the threat model: two concurrent FIRST attempts
 * with one key can both run (the store is written after the response); every money-moving step
 * below this layer is idempotent on its own key or unique index as well.
 */
class IdempotencyReplayFilter {

    @Inject
    lateinit var pool: Pool

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var mapper: ObjectMapper

    // readBody: the body is buffered before this runs, so it can be fingerprinted and then handed
    // back to the resource method unchanged.
    @ServerRequestFilter(readBody = true)
    fun replay(request: ContainerRequestContext): Uni<Response?> {
        val scope = scopeOf(request) ?: return Uni.createFrom().nullItem()
        val fingerprint = fingerprintOf(request)
        request.setProperty(SCOPE_PROPERTY, scope)
        request.setProperty(FINGERPRINT_PROPERTY, fingerprint)
        return pool.preparedQuery(SELECT).execute(Tuple.of(scope)).map { rows ->
            val row = rows.firstOrNull() ?: return@map null
            val stored = row.getString("request_hash")
            if (stored != null && stored != fingerprint) throw IdempotencyKeyReusedException()
            request.setProperty(REPLAYED_PROPERTY, true)
            Response.status(row.getInteger("status"))
                .entity(row.getString("response_body"))
                .type(MediaType.APPLICATION_JSON)
                .header(REPLAY_HEADER, "true")
                .build()
        }
    }

    @ServerResponseFilter
    fun store(request: ContainerRequestContext, response: ContainerResponseContext): Uni<Void> {
        val scope = request.getProperty(SCOPE_PROPERTY) as? String
        if (scope == null || request.getProperty(REPLAYED_PROPERTY) == true || response.status !in SUCCESS) {
            return Uni.createFrom().voidItem()
        }
        val body = response.entity?.let { if (it is String) it else mapper.writeValueAsString(it) }
        val fingerprint = request.getProperty(FINGERPRINT_PROPERTY) as? String
        return pool.preparedQuery(INSERT).execute(Tuple.of(scope, response.status, body, fingerprint))
            .replaceWithVoid()
            .onFailure().recoverWithItem { _ -> null }
    }

    /** Method, path and the key-sorted JSON body; a non-JSON body is hashed verbatim. */
    private fun fingerprintOf(request: ContainerRequestContext): String {
        val bytes = request.entityStream?.readAllBytes() ?: ByteArray(0)
        request.entityStream = ByteArrayInputStream(bytes)
        val raw = String(bytes, StandardCharsets.UTF_8)
        val canonical = if (raw.isBlank()) {
            ""
        } else {
            runCatching {
                canonicalWriter.writeValueAsString(mapper.readValue(raw, Any::class.java))
            }.getOrDefault(raw)
        }
        return RequestFingerprint.of(request.method, request.uriInfo.path, canonical)
    }

    private val canonicalWriter by lazy { mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS) }

    private fun scopeOf(request: ContainerRequestContext): String? {
        if (request.method !in MUTATING || !request.uriInfo.path.startsWith(API_PREFIX)) return null
        val key = request.getHeaderString(IDEMPOTENCY_KEY_HEADER)?.takeIf {
            it.isNotBlank() && it.length <= MAX_IDEMPOTENCY_KEY_LENGTH
        } ?: return null
        val principal = identity.principal?.name.orEmpty()
        val party = request.getHeaderString(PARTY_HEADER).orEmpty()
        val raw = listOf(principal, party, request.method, request.uriInfo.path, key).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val API_PREFIX = "/api/v2/pension"
        const val PARTY_HEADER = "X-Customer-Party-Id"
        const val REPLAY_HEADER = "Idempotent-Replayed"
        const val SCOPE_PROPERTY = "pension.idempotency.scope"
        const val REPLAYED_PROPERTY = "pension.idempotency.replayed"
        const val FINGERPRINT_PROPERTY = "pension.idempotency.fingerprint"
        val MUTATING = setOf("POST", "PUT", "PATCH")
        val SUCCESS = 200..299
        const val SELECT =
            "SELECT status, response_body, request_hash FROM pension_idempotency_records WHERE scope_hash = $1"
        const val INSERT =
            "INSERT INTO pension_idempotency_records (scope_hash, status, response_body, request_hash) " +
                "VALUES ($1, $2, $3, $4) " +
                "ON CONFLICT (scope_hash) DO NOTHING"
    }
}
