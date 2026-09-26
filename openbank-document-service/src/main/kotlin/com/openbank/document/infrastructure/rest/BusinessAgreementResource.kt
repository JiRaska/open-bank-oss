// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest

import com.openbank.document.application.port.`in`.BusinessAgreementConflictException
import com.openbank.document.application.port.`in`.BusinessAgreementUseCase
import com.openbank.document.infrastructure.rest.dto.BusinessAgreementResponse
import com.openbank.document.infrastructure.rest.dto.EnsureBusinessAgreementRequest
import com.openbank.document.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * A company's onboarding agreement (business framework agreement + annexed disclosures + one
 * multi-signer ceremony). Internal: kyb-service calls it with its service identity; customers
 * reach the rendered PDFs through customer-edge's document routes and sign through its ceremony
 * decision route. Authorised by `document_rest_ext.rego` (`document.business-agreement.*`).
 */
@Path("/api/v1/business-agreements")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class BusinessAgreementResource(private val useCase: BusinessAgreementUseCase) {

    @POST
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "document.business-agreement.ensure", resource = "#req.caseId")
    suspend fun ensure(req: EnsureBusinessAgreementRequest?): BusinessAgreementResponse {
        val body = requireNotNull(req) { "request body is required" }
        return try {
            useCase.ensure(body.toCommand()).toResponse()
        } catch (e: BusinessAgreementConflictException) {
            throw ClientErrorException(e.message, Response.Status.CONFLICT, e)
        }
    }

    @GET
    @Path("/{caseId}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "document.business-agreement.read", resource = "#caseId")
    suspend fun get(@PathParam("caseId") caseId: UUID, @QueryParam("lang") lang: String?): BusinessAgreementResponse =
        useCase.find(caseId, lang)?.toResponse() ?: throw NotFoundException("No business agreement for case $caseId")
}
