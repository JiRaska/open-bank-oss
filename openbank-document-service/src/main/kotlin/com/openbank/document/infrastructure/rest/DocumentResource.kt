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

    /**
     * One page of a party's documents, newest first (#8082).
     *
     * Two things were wrong here, and they are one defect seen from two sides: the endpoint
     * trusted the caller.
     *
     * **Bounded.** The read was unbounded. A party's document count grows with every rendered
     * statement and agreement, and the whole set was serialised into a single response. The page
     * size is clamped, not merely defaulted — a caller-supplied page size is a caller-supplied
     * amount of work.
     *
     * **Policy-aligned.** It carried only @RolesAllowed, while its siblings [getDocument] and
     * [getContent] are @Authorize-gated. A role check is not a policy decision — it cannot see
     * which party is being browsed — so the one endpoint returning a party's whole document file
     * was the one endpoint the PDP never saw. The action document.list is granted to
     * ROLE_OPERATOR/ROLE_ADMIN by base rest.rego's operator-read-any rule (which matches the
     * .list verb suffix) and to customer-edge's identity by edge-service-notification (which
     * matches the document. family), so this adds a decision point without needing a new grant.
     *
     * The body stays an array; the page metadata rides in headers. Wrapping it in a page object
     * would change the response type from array to object — a breaking contract change, and under
     * ADR-0048 a major bump means serving every path under a new URL major, out of all proportion
     * to adding pagination. Two live consumers parse this array today: admin-ui's Customer-360
     * DocumentsPanel and customer-edge's CustomerDocumentResource. Same shape and same reasoning
     * as campaign-service's send log.
     */
    @GET
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "document.list", resource = "#partyRef")
    suspend fun listByParty(
        @QueryParam("partyRef") partyRef: String?,
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("50") size: Int,
    ): Response {
        if (partyRef.isNullOrBlank()) throw BadRequestException("partyRef query parameter is required")
        if (page < 0) throw BadRequestException("page must not be negative")
        val effectiveSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val items: List<DocumentResponse> =
            queryUseCase.listByPartyPaged(partyRef, page, effectiveSize).map { it.toResponse() }
        return Response.ok(items)
            .header("X-Total-Count", queryUseCase.countByParty(partyRef))
            .header("X-Page", page)
            .header("X-Page-Size", effectiveSize)
            .build()
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

        /** A caller-supplied page size is a caller-supplied amount of work. */
        const val MAX_PAGE_SIZE = 200
    }
}
