// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.productcatalog.application.CatalogConflictException
import com.openbank.productcatalog.application.CatalogForbiddenException
import com.openbank.productcatalog.application.CatalogNotFoundException
import com.openbank.productcatalog.application.CatalogPreconditionRequiredException
import com.openbank.productcatalog.application.GenericCatalogService
import com.openbank.productcatalog.domain.catalog.RevisionApproval
import com.openbank.productcatalog.infrastructure.security.CatalogApprovalSecurityConfig
import com.openbank.productcatalog.infrastructure.security.CatalogRoles
import com.openbank.productcatalog.infrastructure.security.CatalogScopeRoleMapper
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Instant
import java.util.UUID

data class RevisionApprovalRequest(val reason: String)

data class RevisionApprovalResponse(val role: String, val digest: String, val approvedAt: Instant) {
    constructor(approval: RevisionApproval) : this(approval.role, approval.digest, approval.approvedAt)
}

/**
 * Identity rules for role-qualified revision approval, shared by the generic endpoint and its
 * deprecated alias. Which roles exist and which authority each needs comes from the revision's pack
 * policy; how a token proves an authority comes from configuration. Nothing here names a pack.
 */
@ApplicationScoped
class RevisionApprovalHandler(
    private val service: GenericCatalogService,
    private val identity: SecurityIdentity,
    private val scopeMapper: CatalogScopeRoleMapper,
    private val approvalSecurity: CatalogApprovalSecurityConfig,
    @ConfigProperty(name = "openbank.catalog.security.scope-claim", defaultValue = "scope")
    private val scopeClaim: String,
) {
    suspend fun list(offeringId: UUID, revisionId: UUID): List<RevisionApprovalResponse> {
        requireEvidenceReader()
        requireOwner(offeringId, revisionId)
        return service.revisionApprovals(revisionId).map(::RevisionApprovalResponse)
    }

    @Suppress("ThrowsCount")
    suspend fun approve(
        offeringId: UUID,
        revisionId: UUID,
        role: String,
        ifMatch: String?,
        request: RevisionApprovalRequest,
    ): Response {
        // Every identity check runs BEFORE the revision is looked up, so a caller who could never
        // approve as this role learns nothing about whether the revision exists.
        val token = requireApprovalToken(service.authoritiesForRole(role))
        val approverName = requireHumanApprover(token)
        val revision = requireOwner(offeringId, revisionId)
        val authority = service.approvalPolicy(revision)?.role(role)?.authority
            ?: throw CatalogConflictException("approval role $role is not declared by the revision's schema")
        if (authority !in heldAuthorities(token)) {
            throw CatalogForbiddenException("approval role and scope are required")
        }
        val issuer = token.issuer?.takeIf(String::isNotBlank)
            ?: throw CatalogForbiddenException("token issuer is required")
        val subject = (token.subject ?: token.getClaim<String>("sub"))?.takeIf(String::isNotBlank)
            ?: throw CatalogForbiddenException("token subject is required")
        val expected = ifMatch?.let { STRONG_ETAG.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?: throw CatalogPreconditionRequiredException("a strong If-Match revision is required")
        val approved = service.approveRevision(
            revisionId,
            expected,
            role,
            issuer,
            subject,
            approverName,
            request.reason,
        )
        return Response.status(Response.Status.CREATED).entity(RevisionApprovalResponse(approved)).build()
    }

    /** OPA is advisory by default; a catalog-read scope alone must not expose approval evidence. */
    private fun requireEvidenceReader() {
        val token = identity.principal as? JsonWebToken
            ?: throw CatalogForbiddenException("a verified JWT is required for approval evidence")
        val subject = token.subject ?: token.getClaim<String>("sub")
        if (subject.isNullOrBlank() || !(isEvidenceReaderService(token) || isHumanApprovalReader(token, subject))) {
            throw CatalogForbiddenException("approval evidence reader identity is required")
        }
    }

    private fun isEvidenceReaderService(token: JsonWebToken): Boolean {
        val client = token.getClaim<String>("azp") ?: return false
        val account = approvalSecurity.evidenceReaders()[client] ?: return false
        return token.getClaim<String>("preferred_username") == account && "ROLE_API" in identity.roles
    }

    private fun isHumanApprovalReader(token: JsonWebToken, subject: String): Boolean {
        val username = token.getClaim<String>("preferred_username").orEmpty()
        val humanToken = token.getClaim<String>("azp") == ADMIN_UI_CLIENT &&
            username.isNotBlank() &&
            !username.startsWith("service-account-") &&
            !username.startsWith("agent:") &&
            !subject.startsWith("agent:") &&
            "ROLE_API" !in identity.roles
        val readers = scopeMapper.approvalAuthorities + CatalogRoles.PUBLISH
        return humanToken && scopeMapper.roles(token.getClaim<Any?>(scopeClaim), identity.roles).any { it in readers }
    }

    /** [candidates]: every authority any registered policy binds to the requested role code. */
    @Suppress("ThrowsCount") // three distinct refusals, each with its own message (same shape as before #13429)
    private fun requireApprovalToken(candidates: Set<String>): JsonWebToken {
        val token = identity.principal as? JsonWebToken
            ?: throw CatalogForbiddenException("a verified JWT principal is required")
        // Raw realm roles can reach this method. Derive the decision again from the verified realm
        // assignment and the independent approval scope on this exact JWT.
        if (heldAuthorities(token).none { it in candidates }) {
            throw CatalogForbiddenException("approval role and scope are required")
        }
        if (token.getClaim<String>("azp") != ADMIN_UI_CLIENT) {
            throw CatalogForbiddenException("the Admin UI client is required for human approval")
        }
        return token
    }

    private fun heldAuthorities(token: JsonWebToken): Set<String> =
        scopeMapper.roles(token.getClaim<Any?>(scopeClaim), identity.roles)

    private fun requireHumanApprover(token: JsonWebToken): String {
        val username = token.getClaim<String>("preferred_username").orEmpty()
        val approverName = token.name ?: username
        val names = listOf(username, approverName)
        if (names.any { it.isBlank() || isMachineIdentity(it) } || token.subject?.startsWith("agent:") == true) {
            throw CatalogForbiddenException("a human approver is required")
        }
        return approverName
    }

    private fun isMachineIdentity(name: String) = name.startsWith("service-account-") || name.startsWith("agent:")

    private suspend fun requireOwner(offeringId: UUID, revisionId: UUID) = service.findRevision(revisionId).also {
        if (it.offeringId != offeringId) {
            throw CatalogNotFoundException("revision $revisionId not found for offering $offeringId")
        }
    }

    private companion object {
        const val ADMIN_UI_CLIENT = "openbank-admin-ui"
        val STRONG_ETAG = Regex("\\\"([0-9]+)\\\"")
    }
}
