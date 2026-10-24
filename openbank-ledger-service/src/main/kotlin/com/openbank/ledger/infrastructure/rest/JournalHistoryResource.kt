// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.infrastructure.persistence.repository.JournalHistorySnapshots
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/** Materializes existing ledger-read facts, never a new authority to post or classify money. */
@Path("/api/v1/journals/history-snapshots")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class JournalHistoryResource(private val snapshots: JournalHistorySnapshots) {
    @POST
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.list", resource = "")
    suspend fun capture(request: JournalHistoryRequest?): Response {
        requireNotNull(request) { "a request body is required" }
        val from = LocalDate.parse(request.fromDate)
        val through = LocalDate.parse(request.throughDate)
        require(!from.isAfter(through)) { "fromDate must not be after throughDate" }
        val currency = CurrencyCode.of(request.currency).code
        val manifest = snapshots.capture(request.accountId, currency, from, through)
        if (!manifest.persisted) return Response.ok(manifest).build()
        return Response.created(URI.create("/api/v1/journals/history-snapshots/${manifest.snapshotId}"))
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
    }
}

data class JournalHistoryRequest(
    val accountId: UUID,
    val currency: String,
    val fromDate: String,
    val throughDate: String,
)
