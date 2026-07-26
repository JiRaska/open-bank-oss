// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.sanctions.application.usecase.SanctionsListService
import com.openbank.sanctions.domain.model.UpdateSanctionsListRequest
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/api/v1/sanctions/lists")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SanctionsListResource(private val service: SanctionsListService) {

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.list", resource = "")
    suspend fun listAll(): Response = Response.ok(service.listAll()).build()

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.read", resource = "#id")
    suspend fun getById(@PathParam("id") id: UUID): Response =
        service.getById(id)?.let { Response.ok(it).build() } ?: Response.status(404).build()

    @PUT
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "sanctions.update", resource = "#id")
    suspend fun update(@PathParam("id") id: UUID, req: UpdateSanctionsListRequest): Response =
        service.update(id, req)?.let { Response.ok(it).build() } ?: Response.status(404).build()

    @POST
    @Path("/{listType}/refresh")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "sanctions.trigger", resource = "#listType")
    suspend fun refresh(@PathParam("listType") listType: String): Response =
        Response.ok(service.refresh(listType)).build()

    @POST
    @Path("/refresh-all")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "sanctions.trigger", resource = "")
    suspend fun refreshAll(): Response = Response.ok(service.refreshAll()).build()
}
