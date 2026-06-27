// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.swift.application.port.`in`.SendSwiftCommand
import com.openbank.swift.application.port.`in`.SwiftUseCase
import com.openbank.swift.domain.model.SwiftStatus
import com.openbank.swift.infrastructure.rest.dto.toResponse
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
    @Authorize(action = "swift.send", resource = "")
    suspend fun send(cmd: SendSwiftCommand) = Response.status(201).entity(useCase.send(cmd)).build()

    @GET
    @Path("/messages")
    @Authorize(action = "swift.list", resource = "")
    suspend fun listAll() = useCase.listAll().map { it.toResponse() }

    @GET
    @Path("/{id}")
    @Authorize(action = "swift.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID) = useCase.getById(id) ?: throw NotFoundException()

    @GET
    @Path("/status/{status}")
    @Authorize(action = "swift.list", resource = "")
    suspend fun listByStatus(@PathParam("status") status: SwiftStatus) = useCase.listByStatus(status)

    @POST
    @Path("/{id}/ack")
    @Authorize(action = "swift.acknowledge", resource = "#id")
    suspend fun ack(@PathParam("id") id: UUID, body: Map<String, String>) =
        useCase.acknowledge(id, body["ackRef"] ?: "")

    @POST
    @Path("/{id}/reject")
    @Authorize(action = "swift.reject", resource = "#id")
    suspend fun reject(@PathParam("id") id: UUID, body: Map<String, String>) = useCase.reject(id, body["reason"] ?: "")
}
