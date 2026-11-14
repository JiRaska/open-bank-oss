// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.security.Roles
import com.openbank.party.application.usecase.StatutoryRepresentationService
import com.openbank.party.domain.model.StatutoryRepresentationRule
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/api/v1/parties/{id}/statutory-representation")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API)
class StatutoryRepresentationResource(
    private val service: StatutoryRepresentationService,
    private val identity: SecurityIdentity,
) {
    @PUT
    @Authorize(action = "party.statutory-representation.publish")
    suspend fun publish(
        @PathParam("id") id: UUID,
        request: PublishStatutoryRepresentationRequest?,
    ): StatutoryRepresentationRule {
        requireCaller("service-account-openbank-kyb")
        return service.publish(id, requireNotNull(request) { "representation evidence is required" }.toDomain())
    }

    @GET
    @Authorize(action = "party.statutory-representation.inspect")
    suspend fun latestPublished(@PathParam("id") id: UUID): Response {
        requireCaller("service-account-openbank-delegation")
        val rule = service.latestPublished(id)
        val response = if (rule == null) Response.status(Response.Status.NOT_FOUND) else Response.ok(rule)
        return response.header(HttpHeaders.CACHE_CONTROL, "no-store").build()
    }

    private fun requireCaller(principal: String) {
        if (!ServiceAccountIdentity.isPrincipal(identity, principal)) {
            throw ForbiddenException("this service is not authorized for statutory representation evidence")
        }
    }
}
