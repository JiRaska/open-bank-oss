// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.infrastructure.persistence.repository.JournalHistorySnapshots
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.RequestFingerprints
import com.openbank.libs.idempotency.ReserveResult
import com.openbank.libs.security.Roles
import io.quarkus.logging.Log
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/** Materializes existing ledger-read facts, never a new authority to post or classify money. */
@Path("/api/v1/journals/history-snapshots")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class JournalHistoryResource(
    private val snapshots: JournalHistorySnapshots,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {
    // Request-scoped identity, carried across the coroutine dispatch (see AccountResource).
    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.list", resource = "")
    suspend fun capture(
        request: JournalHistoryRequest?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        // Nullable on purpose (#3104): an absent header is a 400 from requireNotNull, never a 500.
        requireNotNull(idempotencyKey) { "Idempotency-Key header is required" }
        requireNotNull(request) { "a request body is required" }
        val from = LocalDate.parse(request.fromDate)
        val through = LocalDate.parse(request.throughDate)
        require(!from.isAfter(through)) { "fromDate must not be after throughDate" }
        val currency = CurrencyCode.of(request.currency).code
        // #10916: a retry with the same key and body replays the first snapshot instead of capturing
        // a second one; a different body under the key is 409, a concurrent duplicate is in-progress.
        val scope = IdempotencyScope(IDEMPOTENCY_SERVICE, identity.principal.name)
        val requestHash = RequestFingerprints.of(objectMapper, "POST", SNAPSHOTS_PATH, request)
        when (
            val reservation = idempotencyStore.reserve(
                scope,
                idempotencyKey,
                requestHash,
                IdempotencyStore.DEFAULT_IN_FLIGHT_TTL_SECONDS,
            )
        ) {
            is ReserveResult.Replay -> return Response.status(reservation.record.statusCode)
                .entity(reservation.record.responseBody)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Replayed", "true")
                .build()
            ReserveResult.Mismatch -> throw IdempotencyKeyReusedException()
            ReserveResult.InFlight -> throw IdempotencyRequestInProgressException()
            ReserveResult.Reserved -> Unit
        }
        var captured = false
        val manifest = try {
            snapshots.capture(request.accountId, currency, from, through).also { captured = true }
        } finally {
            if (!captured) {
                withContext(NonCancellable) {
                    runCatching { idempotencyStore.release(scope, idempotencyKey, requestHash) }
                        .onFailure { Log.warn("Failed to release idempotency key after snapshot failure", it) }
                }
            }
        }
        val status = if (manifest.persisted) Response.Status.CREATED else Response.Status.OK
        idempotencyStore.save(
            scope,
            idempotencyKey,
            requestHash,
            status.statusCode,
            objectMapper.writeValueAsString(manifest),
            IdempotencyStore.DEFAULT_RECORD_TTL_SECONDS,
        )
        if (!manifest.persisted) return Response.ok(manifest).build()
        return Response.created(URI.create("$SNAPSHOTS_PATH/${manifest.snapshotId}"))
            .entity(manifest).build()
    }

    @GET
    @Path("/{snapshotId}")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.list", resource = "")
    suspend fun manifest(@PathParam("snapshotId") id: UUID): Response =
        Response.ok(snapshots.manifest(id) ?: throw NotFoundException()).build()

    @GET
    @Path("/{snapshotId}/entries")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.list", resource = "")
    suspend fun page(
        @PathParam("snapshotId") id: UUID,
        @QueryParam("afterOrdinal") @DefaultValue("0") afterOrdinal: Long,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
    ): Response {
        require(afterOrdinal >= 0) { "afterOrdinal must not be negative" }
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and 1000" }
        val manifest = snapshots.manifest(id) ?: throw NotFoundException()
        require(afterOrdinal <= manifest.entryCount) { "afterOrdinal exceeds snapshot entry count" }
        return Response.ok(snapshots.page(manifest, afterOrdinal, limit) ?: throw NotFoundException()).build()
    }
    private companion object {
        const val MAX_PAGE_SIZE = 1000
        const val SNAPSHOTS_PATH = "/api/v1/journals/history-snapshots"
        const val IDEMPOTENCY_SERVICE = "ledger-service"
    }
}

data class JournalHistoryRequest(
    val accountId: UUID,
    val currency: String,
    val fromDate: String,
    val throughDate: String,
)
