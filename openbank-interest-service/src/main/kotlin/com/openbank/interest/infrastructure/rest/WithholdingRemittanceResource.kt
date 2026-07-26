// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.rest

import com.openbank.interest.application.port.`in`.RemitWithholdingUseCase
import com.openbank.libs.authz.Authorize
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * Withholding-tax remittance endpoints (ADR-0038): assemble the monthly *Vyúčtování daně vybírané
 * srážkou*, advancing the paired withholding records `RECORDED → REMITTED`.
 */
@Path("/api/v1/interest/withholding/remittances")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Withholding remittance", description = "Monthly withholding-tax remittance (§38d ZDP)")
@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_API")
class WithholdingRemittanceResource(private val remitUseCase: RemitWithholdingUseCase) {
    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "interest.create", resource = "")
    @Operation(summary = "Assemble (or return) the monthly withholding remittance for a tax period")
    fun assemble(@QueryParam("year") year: Int, @QueryParam("month") month: Int): Uni<Response> =
        remitUseCase.assembleRemittance(year, month)
            .map { Response.status(201).entity(it).build() }
            .onFailure().recoverWithItem { e -> Response.serverError().entity(mapOf("error" to e.message)).build() }

    @GET
    @Authorize(action = "interest.list", resource = "")
    @Operation(summary = "List assembled withholding remittance batches")
    fun list() = remitUseCase.listRemittances()

    @GET
    @Path("/{year}/{month}")
    @Authorize(action = "interest.read", resource = "")
    @Operation(summary = "Get the withholding remittance batch for a tax period")
    fun get(@PathParam("year") year: Int, @PathParam("month") month: Int): Uni<Response> =
        remitUseCase.getRemittance(year, month)
            .map { it?.let { r -> Response.ok(r).build() } ?: Response.status(404).build() }
}
