// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Replay protection for every pension-service POST (ADR-0334 S8). Each handler REQUIRES an
 * `Idempotency-Key` ([requireIdempotencyKey]); this filter makes the key mean something: the first
 * successful (2xx) response for a key is stored, and a retry with the same key — same caller, same
 * participant, same method and path — is answered from the store WITHOUT running the handler again.
 * So a client retry after a lost response cannot start a second application, credit a payment
 * twice or place a second order.
 *
 * Scope is `(principal, X-Customer-Party-Id, method, path, key)`: the edge relays every customer
 * under one service-account, so the party header is part of the scope, or two customers choosing
 * the same key would read each other's response. Failed attempts (4xx/5xx) are not stored, so a
 * corrected retry runs. Residual, as documented in the threat model: two concurrent FIRST attempts
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

    @ServerRequestFilter
    fun replay(request: ContainerRequestContext): Uni<Response?> {
        val scope = scopeOf(request) ?: return Uni.createFrom().nullItem()
        request.setProperty(SCOPE_PROPERTY, scope)
        return pool.preparedQuery(SELECT).execute(Tuple.of(scope)).map { rows ->
            val row = rows.firstOrNull() ?: return@map null
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
        return pool.preparedQuery(INSERT).execute(Tuple.of(scope, response.status, body))
            .replaceWithVoid()
            .onFailure().recoverWithItem { _ -> null }
    }

    private fun scopeOf(request: ContainerRequestContext): String? {
        if (request.method != "POST" || !request.uriInfo.path.startsWith(API_PREFIX)) return null
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
        const val API_PREFIX = "/api/v1/pension"
        const val PARTY_HEADER = "X-Customer-Party-Id"
        const val REPLAY_HEADER = "Idempotent-Replayed"
        const val SCOPE_PROPERTY = "pension.idempotency.scope"
        const val REPLAYED_PROPERTY = "pension.idempotency.replayed"
        val SUCCESS = 200..299
        const val SELECT = "SELECT status, response_body FROM pension_idempotency_records WHERE scope_hash = $1"
        const val INSERT =
            "INSERT INTO pension_idempotency_records (scope_hash, status, response_body) VALUES ($1, $2, $3) " +
                "ON CONFLICT (scope_hash) DO NOTHING"
    }
}
