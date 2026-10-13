// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.reporting

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.out.ParticipantSnapshot
import com.openbank.pension.application.usecase.ParticipantSnapshotService
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import java.time.LocalDate
import java.util.UUID

data class CaptureParticipantSnapshotRequest(val companyId: UUID, val periodStart: LocalDate, val periodEnd: LocalDate)

data class ParticipantSnapshotReceipt(
    val snapshotId: UUID,
    val companyId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val revision: Int,
    val payloadSchemaVersion: String,
    val sha256: String,
) {
    companion object {
        fun from(s: ParticipantSnapshot) = ParticipantSnapshotReceipt(
            s.snapshotId,
            s.companyId,
            s.periodStart,
            s.periodEnd,
            s.revision,
            s.payloadSchemaVersion,
            s.sha256,
        )
    }
}

data class ParticipantSnapshotResponse(val receipt: ParticipantSnapshotReceipt, val payloadJson: String)

/** Technical aggregate capture; statutory/business approval remains in tax-reporting. */
@Path("/api/v2/pension/reporting/participant-snapshots")
@jakarta.ws.rs.Produces(MediaType.APPLICATION_JSON)
class ParticipantSnapshotResource {
    @Inject lateinit var identity: SecurityIdentity

    @Inject lateinit var snapshots: ParticipantSnapshotService

    @POST
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Capture immutable participant aggregate source for a statutory return")
    @RolesAllowed(Roles.API)
    @Authorize(action = "pension.reporting.snapshot.capture")
    suspend fun capture(
        @HeaderParam("Idempotency-Key") key: String?,
        request: CaptureParticipantSnapshotRequest?,
    ): ParticipantSnapshotReceipt {
        val actor = requireTaxClient()
        val acceptedKey = requireIdempotencyKey(key)
        val command = requireNotNull(request) { "capture request body is required" }
        return ParticipantSnapshotReceipt.from(
            snapshots.capture(actor, acceptedKey, command.companyId, command.periodStart, command.periodEnd),
        )
    }

    @GET
    @Path("/{snapshotId}")
    @Operation(summary = "Read exact immutable participant aggregate bytes by snapshot ID and company")
    @RolesAllowed(Roles.API)
    @Authorize(action = "pension.reporting.snapshot.read")
    suspend fun byId(
        @PathParam("snapshotId") snapshotId: UUID,
        @QueryParam("companyId") companyId: UUID?,
    ): ParticipantSnapshotResponse {
        requireTaxClient()
        val scopedCompany = requireNotNull(companyId) { "companyId is required" }
        val saved = snapshots.byId(snapshotId, scopedCompany) ?: throw NotFoundException()
        return ParticipantSnapshotResponse(ParticipantSnapshotReceipt.from(saved), saved.payloadJson)
    }

    /** A staff-shaped username or advisory OPA decision never authorizes this endpoint. */
    private fun requireTaxClient(): String {
        if (identity.isAnonymous) denyTaxClient()
        val jwt = identity.principal as? JsonWebToken ?: denyTaxClient()
        val client = jwt.getClaim<Any>("azp") as? String
        if (!identity.hasRole(Roles.API) || client != TAX_CLIENT) denyTaxClient()
        if (jwt.subject.isNullOrBlank()) denyTaxClient()
        if (jwt.getClaim<Any>("preferred_username") != "service-account-$TAX_CLIENT") denyTaxClient()
        val issuer = jwt.issuer?.takeIf { it.isNotBlank() }
            ?: denyTaxClient()
        return "$issuer\n$client\n${jwt.subject}"
    }

    private fun denyTaxClient(): Nothing = throw ForbiddenException("trusted tax reporting client required")

    companion object {
        private const val TAX_CLIENT = "openbank-tax-reporting"
    }
}
