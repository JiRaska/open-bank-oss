// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest

import com.openbank.document.application.port.`in`.CreateTemplateCommand
import com.openbank.document.application.port.`in`.DocumentQueryUseCase
import com.openbank.document.application.port.`in`.DocumentRenderUseCase
import com.openbank.document.application.port.`in`.DocumentTemplateUseCase
import com.openbank.document.application.port.`in`.OnboardingDocumentUseCase
import com.openbank.document.application.port.`in`.RenderDocumentCommand
import com.openbank.document.infrastructure.rest.dto.CreateTemplateRequest
import com.openbank.document.infrastructure.rest.dto.DocumentResponse
import com.openbank.document.infrastructure.rest.dto.EnsureOnboardingAgreementRequest
import com.openbank.document.infrastructure.rest.dto.OnboardingAgreementResponse
import com.openbank.document.infrastructure.rest.dto.PreviewTemplateRequest
import com.openbank.document.infrastructure.rest.dto.PreviewTemplateResponse
import com.openbank.document.infrastructure.rest.dto.RenderDocumentRequest
import com.openbank.document.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
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

// TooManyFunctions: this is the REST surface for the whole document + template + onboarding-agreement
// area — each endpoint is one thin delegation to a use-case; splitting by URL sub-tree would fragment
// one cohesive resource for no real gain.
@Suppress("TooManyFunctions")
@Path("/api/v1/documents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class DocumentResource(
    private val templateUseCase: DocumentTemplateUseCase,
    private val renderUseCase: DocumentRenderUseCase,
    private val queryUseCase: DocumentQueryUseCase,
    private val onboardingUseCase: OnboardingDocumentUseCase,
) {

    /**
     * Get-or-create the caller-party's onboarding framework agreement in the requested language
     * (ADR-0169 D3). Idempotent. `customer-edge` proxies this for `ROLE_CUSTOMER`, forcing
     * `partyRef` to the caller's token so a customer can only ever provision their own agreement.
     */
    @POST
    @Path("/onboarding-agreement")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun ensureOnboardingAgreement(req: EnsureOnboardingAgreementRequest): OnboardingAgreementResponse {
        if (req.partyRef.isBlank()) throw BadRequestException("partyRef is required")
        return onboardingUseCase.ensureOnboardingAgreement(req.partyRef, req.lang).toResponse()
    }

    @POST
    @Path("/templates")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun createTemplate(req: CreateTemplateRequest): Response {
        val template = templateUseCase.createTemplate(
            CreateTemplateCommand(
                code = req.code,
                version = req.version,
                name = req.name,
                engine = req.engine,
                bodyHtml = req.bodyHtml,
                locale = req.locale,
                productRef = req.productRef,
                classification = req.classification,
                createdBy = req.createdBy,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(template.toResponse()).build()
    }

    @POST
    @Path("/templates/preview")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    fun previewTemplate(req: PreviewTemplateRequest): PreviewTemplateResponse {
        if (req.bodyHtml.length > MAX_PREVIEW_BODY_LENGTH) {
            throw BadRequestException("bodyHtml exceeds the $MAX_PREVIEW_BODY_LENGTH character preview limit")
        }
        return PreviewTemplateResponse(templateUseCase.previewRender(req.bodyHtml, req.data))
    }

    @POST
    @Path("/templates/{id}/publish")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "documentTemplate.publish", resource = "#id")
    suspend fun publishTemplate(@PathParam("id") id: UUID) = templateUseCase.publishTemplate(id).toResponse()

    @POST
    @Path("/templates/{id}/retire")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "documentTemplate.retire", resource = "#id")
    suspend fun retireTemplate(@PathParam("id") id: UUID) = templateUseCase.retireTemplate(id).toResponse()

    @GET
    @Path("/templates")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun listTemplates(@QueryParam("limit") @DefaultValue("50") limit: Int) =
        templateUseCase.listTemplates(limit).map { it.toResponse() }

    @GET
    @Path("/templates/{id}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "documentTemplate.read", resource = "#id")
    suspend fun getTemplate(@PathParam("id") id: UUID) =
        templateUseCase.getTemplate(id)?.toResponse() ?: throw NotFoundException()

    @POST
    @Path("/render")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun render(req: RenderDocumentRequest): Response {
        val document = renderUseCase.render(
            RenderDocumentCommand(
                templateCode = req.templateCode,
                templateVersion = req.templateVersion,
                data = req.data,
                contentType = req.contentType,
                partyRef = req.partyRef,
                caseRef = req.caseRef,
                productRef = req.productRef,
                retainUntil = req.retainUntil,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(document.toResponse()).build()
    }

    @GET
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun listByParty(@QueryParam("partyRef") partyRef: String?): List<DocumentResponse> {
        if (partyRef.isNullOrBlank()) throw BadRequestException("partyRef query parameter is required")
        return queryUseCase.listByParty(partyRef).map { it.toResponse() }
    }

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "document.read", resource = "#id")
    suspend fun getDocument(@PathParam("id") id: UUID) =
        queryUseCase.getMetadata(id)?.toResponse() ?: throw NotFoundException()

    @GET
    @Path("/{id}/content")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "document.readContent", resource = "#id")
    suspend fun getContent(@PathParam("id") id: UUID): Response {
        val bytes = queryUseCase.getContent(id) ?: throw NotFoundException()
        return Response.ok(bytes).build()
    }

    private companion object {
        const val MAX_PREVIEW_BODY_LENGTH = 200_000
    }
}
