// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.ListAmlCasesQuery
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.aml.infrastructure.rest.dto.CreateAmlCaseRequest
import com.openbank.aml.infrastructure.rest.dto.UpdateAmlDecisionRequest
import com.openbank.aml.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.util.UUID

@Path("/api/v1/aml/cases")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AML Cases", description = "AML screening case lifecycle")
class AmlCaseResource(
    private val amlCaseUseCase: AmlCaseUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Operation(summary = "Submit an AML screening case")
    suspend fun createCase(
        request: CreateAmlCaseRequest,
        @HeaderParam("Idempotency-Key") idempotencyKey: String,
    ): Response {
        require(idempotencyKey.isNotBlank()) { "Idempotency-Key header is required" }

        idempotencyStore.get(idempotencyKey)?.let { cached ->
            return Response.status(cached.statusCode)
                .entity(cached.responseBody)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Replayed", "true")
                .build()
        }

        val amlCase = amlCaseUseCase.createCase(request.toCommand(idempotencyKey))
        val responseBody = amlCase.toResponse()
        idempotencyStore.save(idempotencyKey, 201, objectMapper.writeValueAsString(responseBody))

        return Response.created(URI.create("/api/v1/aml/cases/${amlCase.id}"))
            .entity(responseBody)
            .build()
    }

    @GET
    @Path("/{caseId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_API")
    @Authorize(action = "amlCase.read", resource = "#caseId")
    @Operation(summary = "Get AML case by ID")
    suspend fun getCase(@PathParam("caseId") caseId: UUID): Response =
        Response.ok(amlCaseUseCase.getCase(caseId).toResponse()).build()

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_API")
    @Authorize(action = "amlCase.list")
    @Operation(summary = "List AML screening cases")
    suspend fun listCases(
        @QueryParam("status") status: String?,
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("screeningType") screeningType: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): Response {
        val amlCases = amlCaseUseCase.listCases(
            ListAmlCasesQuery(
                status = status?.let(AmlCaseStatus::valueOf),
                partyId = partyId,
                screeningType = screeningType?.let(ScreeningType::valueOf),
                limit = limit,
                offset = offset,
            ),
        )
        return Response.ok(amlCases.map { it.toResponse() }).build()
    }

    @PUT
    @Path("/{caseId}/decision")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "amlCase.updateDecision", resource = "#caseId")
    @Operation(summary = "Update AML case decision")
    suspend fun updateDecision(@PathParam("caseId") caseId: UUID, request: UpdateAmlDecisionRequest): Response =
        Response.ok(amlCaseUseCase.updateDecision(request.toCommand(caseId)).toResponse()).build()
}
