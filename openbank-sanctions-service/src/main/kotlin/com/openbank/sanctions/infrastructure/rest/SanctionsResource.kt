// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.sanctions.application.port.`in`.ReviewCommand
import com.openbank.sanctions.application.port.`in`.SanctionsUseCase
import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/api/v1/sanctions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SanctionsResource(private val useCase: SanctionsUseCase) {

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.list", resource = "")
    suspend fun listAll() = useCase.listChecks()

    @POST
    @Path("/screen")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.create", resource = "")
    suspend fun screen(cmd: ScreenEntityCommand) = Response.status(201).entity(useCase.screen(cmd)).build()

    @POST
    @Path("/review")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    // Renamed from sanctions.review (issue #938 follow-up): "clear" is a distinctive four-eyes
    // verb, kept apart from `release` (payment-hold release elsewhere in the fleet) for clean
    // audit separation — a wrongly-cleared true positive here is a real sanctions violation.
    // No ROLE_API on this endpoint, confirmed no M2M caller — safe to four-eyes gate.
    @Authorize(action = "sanctions.clear", resource = "")
    suspend fun review(cmd: ReviewCommand) = useCase.review(cmd)

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID) = useCase.getById(id) ?: throw NotFoundException()

    @GET
    @Path("/hits")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.list", resource = "")
    suspend fun hits() = useCase.listHits()

    @GET
    @Path("/pending")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.list", resource = "")
    suspend fun pending() = useCase.listPending()
}
