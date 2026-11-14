// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.rest

import com.openbank.kyb.application.usecase.StatutoryRepresentationPublicationService
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/api/v1/kyb/cases/{id}/statutory-representation")
@Produces(MediaType.APPLICATION_JSON)
class StatutoryRepresentationResource(
    private val service: StatutoryRepresentationPublicationService,
    private val identity: SecurityIdentity,
) {
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Roles.ADMIN, Roles.KYC)
    @Authorize(action = "kyb.statutory-representation.publish")
    suspend fun publish(@PathParam("id") caseId: UUID, request: PublishStatutoryRepresentationRequest?): Response {
        if (!ServiceAccountIdentity.isHumanStaff(identity)) {
            throw ForbiddenException("publication requires an authenticated KYB staff member")
        }
        val expected = requireNotNull(request?.attestationId) { "attestationId is required" }
        // isHumanStaff guarantees principal.name == preferred_username, the same value the
        // attestation recorded as attestedBy — so the four-eyes check compares like with like.
        val publishedBy = identity.principal.name
        val attestationId = service.publish(caseId, expected, publishedBy) ?: throw NotFoundException()
        return Response.ok(mapOf("attestationId" to attestationId)).build()
    }

    @GET
    @Path("/{attestationId}/current")
    @RolesAllowed(Roles.API)
    @Authorize(action = "kyb.statutory-representation.inspect")
    suspend fun isCurrent(
        @PathParam("id") caseId: UUID,
        @PathParam("attestationId") attestationId: UUID,
        @QueryParam("entityPartyId") entityPartyId: UUID?,
    ): Response {
        if (!ServiceAccountIdentity.isPrincipal(identity, "service-account-openbank-delegation")) {
            throw ForbiddenException("only delegation-service may verify a statutory representation revision")
        }
        requireNotNull(entityPartyId) { "entityPartyId is required" }
        val current = service.isCurrent(caseId, entityPartyId, attestationId)
        val response = if (current) Response.noContent() else Response.status(Response.Status.NOT_FOUND)
        return response.header(HttpHeaders.CACHE_CONTROL, "no-store").build()
    }
}

data class PublishStatutoryRepresentationRequest(val attestationId: UUID?)
