// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.RequestFingerprints
import com.openbank.libs.idempotency.ReserveResult
import com.openbank.tppregistry.application.port.`in`.BlacklistTppCommand
import com.openbank.tppregistry.application.port.`in`.CheckTppAuthorizationQuery
import com.openbank.tppregistry.application.port.`in`.GetTppQuery
import com.openbank.tppregistry.application.port.`in`.ListTppsQuery
import com.openbank.tppregistry.application.port.`in`.RegisterTppCommand
import com.openbank.tppregistry.application.port.`in`.TppRegistryUseCase
import com.openbank.tppregistry.domain.model.TppRole
import com.openbank.tppregistry.domain.model.TppStatus
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

// Kept top-level (not class members) so they do not count against TppRegistryResource's detekt
// TooManyFunctions threshold — the class itself only exposes the REST operations.
private fun registerKey(tppId: String, idempotencyKey: String) = "tpp:register:$tppId:$idempotencyKey"
private fun blacklistKey(tppId: String, idempotencyKey: String) = "tpp:blacklist:$tppId:$idempotencyKey"
private fun syncKey(idempotencyKey: String) = "tpp:sync:$idempotencyKey"

private fun respond(statusCode: Int, body: String, replayed: Boolean = false): Response {
    val builder = Response.status(statusCode).entity(body).type(MediaType.APPLICATION_JSON)
    if (replayed) builder.header("X-Idempotency-Replayed", "true")
    return builder.build()
}

/**
 * `reserve` -> Reserved: run [block], then the fingerprinted `save`; any failure from [block]
 * releases the reservation and rethrows so a retry with the same body can succeed. Replay: the
 * stored response is returned verbatim. Mismatch/InFlight: throw the matching exception so
 * libs-runtime's mappers answer 409 IDEMPOTENCY_KEY_REUSED / IDEMPOTENCY_REQUEST_IN_PROGRESS.
 * Side effects never run before `reserve` returns [ReserveResult.Reserved].
 */
private suspend fun IdempotencyStore.withReservation(
    key: String,
    requestHash: String,
    ttlSeconds: Long = 86400,
    block: suspend () -> Pair<Int, String>,
): Response = when (val reservation = reserve(key, requestHash)) {
    is ReserveResult.Replay -> respond(reservation.record.statusCode, reservation.record.responseBody, replayed = true)
    ReserveResult.Reserved -> {
        val (statusCode, body) = runCatching { block() }
            .onFailure { release(key, requestHash) }
            .getOrThrow()
        save(key, requestHash, statusCode, body, ttlSeconds)
        respond(statusCode, body)
    }
    ReserveResult.Mismatch -> throw IdempotencyKeyReusedException()
    ReserveResult.InFlight -> throw IdempotencyRequestInProgressException()
}

@Path("/api/v1/tpp-registry")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class TppRegistryResource(
    private val svc: TppRegistryUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {
    @GET
    @Path("/check")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun checkAuthorization(
        // Nullable by necessity: JAX-RS injects null for an absent query parameter and a suspend
        // fun emits no null-check intrinsic, so a non-nullable declaration let the null reach
        // `role.uppercase()` and answered 500. libs-runtime maps IllegalArgumentException to 400
        // — never add a service-local exception mapper (#526, #3624).
        @QueryParam("tppId") tppId: String?,
        @QueryParam("role") role: String?,
    ): Response {
        requireNotNull(tppId) { "query parameter 'tppId' is required" }
        requireNotNull(role) { "query parameter 'role' is required" }
        val result = svc.checkAuthorization(
            CheckTppAuthorizationQuery(tppId, TppRole.valueOf(role.uppercase())),
        )
        return if (result.authorized) {
            Response.ok(result).build()
        } else {
            Response.status(403).entity(result).build()
        }
    }

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun registerTpp(
        cmd: RegisterTppCommand,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val cacheKey = idempotencyKey?.takeIf { it.isNotBlank() }?.let { registerKey(cmd.tppId, it) }
            ?: return respond(201, objectMapper.writeValueAsString(svc.registerTpp(cmd)))
        val hash = RequestFingerprints.of(objectMapper, "POST", "/api/v1/tpp-registry", cmd)
        return idempotencyStore.withReservation(cacheKey, hash) {
            val entry = svc.registerTpp(cmd)
            201 to objectMapper.writeValueAsString(entry)
        }
    }

    @GET
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun listTpps(
        @QueryParam("countryCode") countryCode: String?,
        @QueryParam("role") role: String?,
        @QueryParam("status") status: String?,
        @QueryParam("limit") limit: Int?,
        @QueryParam("afterCursor") afterCursor: String?,
    ): Response {
        val entries = svc.listTpps(
            ListTppsQuery(
                countryCode = countryCode,
                role = role?.let { TppRole.valueOf(it.uppercase()) },
                status = status?.let { TppStatus.valueOf(it.uppercase()) },
                limit = limit ?: 50,
                afterCursor = afterCursor,
            ),
        )
        return Response.ok(mapOf("tpps" to entries, "count" to entries.size)).build()
    }

    @GET
    @Path("/{tppId}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun getTpp(@PathParam("tppId") tppId: String): Response =
        Response.ok(svc.getTpp(GetTppQuery(tppId))).build()

    @POST
    @Path("/{tppId}/blacklist")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "tppRegistry.blacklist", resource = "#tppId")
    suspend fun blacklistTpp(
        @PathParam("tppId") tppId: String,
        body: Map<String, String>,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val reason = body["reason"] ?: "No reason provided"
        val cacheKey = idempotencyKey?.takeIf { it.isNotBlank() }?.let { blacklistKey(tppId, it) }
            ?: return respond(
                200,
                objectMapper.writeValueAsString(svc.blacklistTpp(BlacklistTppCommand(tppId, reason))),
            )
        val hash = RequestFingerprints.of(objectMapper, "POST", "/api/v1/tpp-registry/$tppId/blacklist", body)
        return idempotencyStore.withReservation(cacheKey, hash) {
            val result = svc.blacklistTpp(BlacklistTppCommand(tppId, reason))
            200 to objectMapper.writeValueAsString(result)
        }
    }

    /**
     * The fingerprint here is over a CONSTANT (method + path, no body) — there is no
     * request-specific content to bind to, so [ReserveResult.Mismatch] can never occur for this
     * endpoint. `reserve`/`InFlight` still serializes concurrent syncs under the same key; see
     * `openapi.yaml`, which documents only IDEMPOTENCY_REQUEST_IN_PROGRESS for this operation.
     */
    @POST
    @Path("/sync/eba")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun triggerEbaSync(@HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        val cacheKey = idempotencyKey?.takeIf { it.isNotBlank() }?.let(::syncKey)
            ?: return respond(200, objectMapper.writeValueAsString(svc.triggerEbaSync()))
        val hash = RequestFingerprints.of(objectMapper, "POST", "/api/v1/tpp-registry/sync/eba", null)
        return idempotencyStore.withReservation(cacheKey, hash, ttlSeconds = 300) {
            val result = svc.triggerEbaSync()
            200 to objectMapper.writeValueAsString(result)
        }
    }

    @GET
    @Path("/sync/state")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun getSyncState(): Response = Response.ok(svc.getSyncState()).build()
}
