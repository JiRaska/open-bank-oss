// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.infrastructure.persistence.repository.HistoryCaptureParameters
import com.openbank.ledger.infrastructure.persistence.repository.JournalHistoryCaptureReceipts
import com.openbank.ledger.infrastructure.persistence.repository.JournalHistorySnapshots
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.libs.idempotency.RequestFingerprints
import com.openbank.libs.security.Roles
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
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** Materializes existing ledger-read facts, never a new authority to post or classify money. */
@Path("/api/v1/journals/history-snapshots")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class JournalHistoryResource(
    private val snapshots: JournalHistorySnapshots,
    private val receipts: JournalHistoryCaptureReceipts,
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
        val scope = IdempotencyScope(IDEMPOTENCY_SERVICE, identity.principal.name)
        val requestHash = RequestFingerprints.of(objectMapper, "POST", SNAPSHOTS_PATH, request)
        val framed = listOf("POST", SNAPSHOTS_PATH, "ledger.list", scope.storeKey(idempotencyKey))
            .joinToString("") { value -> "${value.toByteArray(Charsets.UTF_8).size}:$value" }
        val keyHash = MessageDigest.getInstance("SHA-256").digest(framed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val result = receipts.capture(
            keyHash,
            requestHash,
            HistoryCaptureParameters(request.accountId, currency, from, through),
        )
        val manifest = result.manifest
        val response = if (manifest.persisted) {
            Response.created(URI.create("$SNAPSHOTS_PATH/${manifest.snapshotId}"))
        } else {
            Response.ok()
        }
        if (result.replayed) response.header("X-Idempotency-Replayed", "true")
        return response.entity(manifest).build()
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
