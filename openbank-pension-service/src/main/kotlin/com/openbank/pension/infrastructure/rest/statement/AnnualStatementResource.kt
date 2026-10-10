// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest.statement

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.usecase.AnnualStatementService
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

// Its own file on purpose: openapi-route-conformance (and any reader) takes ONE class-level @Path
// per file, so sharing a file with AnnualStatementOperationsResource made this GET read as served
// under the operations path and the published route as unserved.
/** The participant's (or staff's) read of an issued annual statement: its document id and hash. */
@Tag(name = "Pension")
@Path("/api/v1/pension/contracts/{contractId}/annual-statements/{year}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
class AnnualStatementResource {
    @Inject
    lateinit var contracts: PensionContractUseCase

    @Inject
    lateinit var statements: AnnualStatementService

    @Inject
    lateinit var access: ContractAccessGuard

    @GET
    @Operation(summary = "Read the issued annual statement for a year (404 until issued)")
    @Authorize(action = "pension.contract.read", resource = "#contractId")
    suspend fun get(
        @PathParam("contractId") contractId: UUID,
        @PathParam("year") year: Int,
        @HeaderParam("X-Customer-Party-Id") party: String?,
    ): AnnualStatementResponse {
        // Ownership first: someone else's contract is NOT FOUND, exactly like the contract read.
        contracts.get(access.readerFor(party), contractId)
        val statement = statements.find(contractId, year) ?: throw NotFoundException("no annual statement for $year")
        return AnnualStatementResponse.from(statement)
    }
}
