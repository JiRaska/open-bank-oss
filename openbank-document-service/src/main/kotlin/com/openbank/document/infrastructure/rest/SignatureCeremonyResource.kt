// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest

import com.openbank.document.application.port.`in`.OpenCeremonyCommand
import com.openbank.document.application.port.`in`.SignatureCeremonyUseCase
import com.openbank.document.infrastructure.rest.dto.OpenCeremonyRequest
import com.openbank.document.infrastructure.rest.dto.RecordDecisionRequest
import com.openbank.document.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
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

@Path("/api/v1/signature-ceremonies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SignatureCeremonyResource(private val useCase: SignatureCeremonyUseCase) {

    @POST
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun open(req: OpenCeremonyRequest): Response {
        val ceremony = useCase.openCeremony(
            OpenCeremonyCommand(
                documentId = req.documentId,
                signerPartyRefs = req.requireSignerPartyRefs(),
                signatureLevel = req.signatureLevel,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(ceremony.toResponse()).build()
    }

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "signatureCeremony.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID) = useCase.getCeremony(id)?.toResponse() ?: throw NotFoundException()

    @POST
    @Path("/{id}/decisions")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "signatureCeremony.recordDecision", resource = "#id")
    suspend fun recordDecision(@PathParam("id") id: UUID, req: RecordDecisionRequest) =
        useCase.recordDecision(id, req.partyRef, req.decision, req.evidenceRef).toResponse()
}
