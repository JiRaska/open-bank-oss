// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.swift.application.port.`in`.SendSwiftCommand
import com.openbank.swift.application.port.`in`.SwiftUseCase
import com.openbank.swift.domain.model.SwiftStatus
import com.openbank.swift.infrastructure.rest.dto.toResponse
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

@Path("/api/v1/swift")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SwiftResource(private val useCase: SwiftUseCase) {
    @POST
    @RolesAllowed(Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "swift.send", resource = "")
    suspend fun send(cmd: SendSwiftCommand?): Response {
        // A JSON `null` body deserialises to null despite the non-nullable Kotlin type, so the
        // first field access threw NPE and this answered 500 (#3038). libs-runtime maps
        // IllegalArgumentException to 400.
        requireNotNull(cmd) { "request body is required" }
        return Response.status(201).entity(useCase.send(cmd)).build()
    }

    @GET
    @Path("/messages")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "swift.list", resource = "")
    suspend fun listAll() = useCase.listAll().map { it.toResponse() }

    @GET
    @Path("/{id}")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "swift.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID) = useCase.getById(id) ?: throw NotFoundException()

    @GET
    @Path("/status/{status}")
    @RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "swift.list", resource = "")
    suspend fun listByStatus(@PathParam("status") status: SwiftStatus) = useCase.listByStatus(status)

    @POST
    @Path("/{id}/ack")
    @RolesAllowed(Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "swift.acknowledge", resource = "#id")
    suspend fun ack(@PathParam("id") id: UUID, body: Map<String, String>?) =
        useCase.acknowledge(id, body?.get("ackRef") ?: "")

    @POST
    @Path("/{id}/reject")
    @RolesAllowed(Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "swift.reject", resource = "#id")
    suspend fun reject(@PathParam("id") id: UUID, body: Map<String, String>?) =
        useCase.reject(id, body?.get("reason") ?: "")
}
