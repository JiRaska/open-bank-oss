// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.maintenance.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.maintenance.ContractMaintenanceService
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Staff read-only view of a contract's schedule and beneficiary history (#12376). There is no
 * operator WRITE: a schedule or designation is the participant's own SCA-signed decision.
 * The caller is staff via [ContractAccessGuard.readerFor] with no party header.
 */
@Tag(name = "Pension operations", description = "Operator views of pension contracts")
@Path("/api/v2/pension/operator/contracts/{contractId}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
class OperatorContractChangesResource {

    @Inject
    lateinit var maintenance: ContractMaintenanceService

    @Inject
    lateinit var access: ContractAccessGuard

    @GET
    @Path("/contribution-schedule")
    @Operation(summary = "Staff: original, in-force and pending schedule with the change history")
    @Authorize(action = "pension.operator.inspect", resource = "#contractId")
    suspend fun schedule(@PathParam("contractId") contractId: UUID): ScheduleViewResponse =
        ScheduleViewResponse.from(maintenance.schedule(access.readerFor(null), contractId))

    @GET
    @Path("/beneficiaries")
    @Operation(summary = "Staff: current beneficiary designation with the designation history")
    @Authorize(action = "pension.operator.inspect", resource = "#contractId")
    suspend fun beneficiaries(@PathParam("contractId") contractId: UUID): BeneficiaryViewResponse {
        val (contract, history) = maintenance.beneficiaries(access.readerFor(null), contractId)
        return BeneficiaryViewResponse.from(contract, history)
    }
}
