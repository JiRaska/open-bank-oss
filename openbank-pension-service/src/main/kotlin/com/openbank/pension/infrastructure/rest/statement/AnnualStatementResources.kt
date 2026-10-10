// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest.statement

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.out.AnnualStatement
import com.openbank.pension.application.usecase.AnnualStatementService
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Instant
import java.util.UUID

data class AnnualStatementResponse(
    val contractId: UUID,
    val year: Int,
    val documentId: String,
    val sha256: String,
    val issuedAt: Instant,
) {
    companion object {
        fun from(s: AnnualStatement) = AnnualStatementResponse(s.contractId, s.year, s.documentId, s.sha256, s.issuedAt)
    }
}

/**
 * Issuing a closed year's annual statement (#12379). Staff only, beside the tax certificate it
 * mirrors; insert-once per (contract, year), and the route also requires an `Idempotency-Key`.
 */
@Tag(name = "Pension funding operations")
@Path("/api/v1/pension/funding/operations/contracts/{contractId}/annual-statements/{year}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
class AnnualStatementOperationsResource {
    @Inject
    lateinit var statements: AnnualStatementService

    @Inject
    lateinit var access: ContractAccessGuard

    @POST
    @Operation(summary = "Issue a closed year's annual statement through document-service (once per year)")
    @Authorize(action = "pension.funding.operate", resource = "#contractId")
    suspend fun issue(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("contractId") contractId: UUID,
        @PathParam("year") year: Int,
    ): AnnualStatementResponse {
        requireIdempotencyKey(idempotencyKey)
        // Staff only: no party header is accepted on an operator route.
        access.readerFor(null)
        return AnnualStatementResponse.from(statements.issue(contractId, year))
    }
}
