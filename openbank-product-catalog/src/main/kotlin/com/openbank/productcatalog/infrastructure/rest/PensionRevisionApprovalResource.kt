// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.productcatalog.application.CatalogForbiddenException
import com.openbank.productcatalog.application.CatalogNotFoundException
import com.openbank.productcatalog.application.CatalogPreconditionRequiredException
import com.openbank.productcatalog.application.GenericCatalogService
import com.openbank.productcatalog.application.port.out.PensionApprovalRole
import com.openbank.productcatalog.application.port.out.PensionRevisionApproval
import com.openbank.productcatalog.infrastructure.security.CatalogRoles
import com.openbank.productcatalog.infrastructure.security.CatalogScopeRoleMapper
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Instant
import java.util.UUID

data class PensionApprovalRequest(val reason: String)

data class PensionApprovalResponse(val role: PensionApprovalRole, val digest: String, val approvedAt: Instant)

@ApplicationScoped
@Path("/api/v2/offerings/{offeringId}/revisions/{revisionId}/pension-approvals")
@Produces(MediaType.APPLICATION_JSON)
class PensionRevisionApprovalResource(
    private val service: GenericCatalogService,
    private val identity: SecurityIdentity,
    private val scopeMapper: CatalogScopeRoleMapper,
    @ConfigProperty(name = "openbank.catalog.security.scope-claim", defaultValue = "scope")
    private val scopeClaim: String,
) {
    @GET
    @Authorize(
        action = "catalog.pensionApproval.read",
        resource = "#offeringId",
        attributes = ["azp", "subject", "preferred_username"],
    )
    @RolesAllowed(CatalogRoles.READ)
    suspend fun list(
        @PathParam("offeringId") offeringId: UUID,
        @PathParam("revisionId") revisionId: UUID,
    ): List<PensionApprovalResponse> {
        requireOwner(offeringId, revisionId)
        return service.pensionApprovals(revisionId).map(::response)
    }

    @POST
    @Authorize(action = "catalog.pensionApproval.decide", resource = "#offeringId")
    @Path("/{role}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(CatalogRoles.PENSION_LEGAL_APPROVER, CatalogRoles.PENSION_PRODUCT_OWNER)
    @Suppress("ThrowsCount")
    suspend fun approve(
        @PathParam("offeringId") offeringId: UUID,
        @PathParam("revisionId") revisionId: UUID,
        @PathParam("role") role: PensionApprovalRole,
        @HeaderParam("If-Match") ifMatch: String?,
        request: PensionApprovalRequest,
    ): Response {
        val requiredRole = when (role) {
            PensionApprovalRole.LEGAL_COUNSEL -> CatalogRoles.PENSION_LEGAL_APPROVER
            PensionApprovalRole.PRODUCT_OWNER -> CatalogRoles.PENSION_PRODUCT_OWNER
        }
        val token = identity.principal as? JsonWebToken
            ?: throw CatalogForbiddenException("a verified JWT principal is required")
        // Raw realm roles can reach @RolesAllowed. Derive the decision again from the verified
        // realm assignment and the independent approval scope on this exact JWT.
        if (requiredRole !in scopeMapper.roles(token.getClaim<Any?>(scopeClaim), identity.roles)) {
            throw CatalogForbiddenException("approval role and scope are required")
        }
        val approverName = token.name ?: token.getClaim<String>("preferred_username").orEmpty()
        if (
            approverName.isBlank() ||
            approverName.startsWith("service-account-") ||
            approverName.startsWith("agent:") ||
            token.subject?.startsWith("agent:") == true
        ) {
            throw CatalogForbiddenException("a human pension approver is required")
        }
        requireOwner(offeringId, revisionId)
        val issuer = token.issuer?.takeIf(String::isNotBlank)
            ?: throw CatalogForbiddenException("token issuer is required")
        val subject = (token.subject ?: token.getClaim<String>("sub"))?.takeIf(String::isNotBlank)
            ?: throw CatalogForbiddenException("token subject is required")
        val revision = ifMatch?.let { STRONG_ETAG.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?: throw CatalogPreconditionRequiredException("a strong If-Match revision is required")
        val approved = service.approvePensionRevision(
            revisionId,
            revision,
            role,
            issuer,
            subject,
            approverName,
            request.reason,
        )
        return Response.status(Response.Status.CREATED).entity(response(approved)).build()
    }

    private suspend fun requireOwner(offeringId: UUID, revisionId: UUID) {
        val revision = service.findRevision(revisionId)
        if (revision.offeringId != offeringId) {
            throw CatalogNotFoundException("revision $revisionId not found for offering $offeringId")
        }
    }

    private fun response(approval: PensionRevisionApproval) =
        PensionApprovalResponse(approval.role, approval.digest, approval.approvedAt)

    private companion object {
        val STRONG_ETAG = Regex("\\\"([0-9]+)\\\"")
    }
}
