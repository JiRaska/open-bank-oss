// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.`in`.CallerPartyId
import com.openbank.delegation.application.usecase.DelegationPortfolioService
import com.openbank.delegation.domain.model.DelegationPortfolio
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.OffsetDateTime
import java.util.UUID

data class DelegationPortfolioRequest(val ownerPartyId: UUID?, val name: String?, val accountIds: Set<UUID>?)

data class DelegationPortfolioResponse(
    val id: UUID,
    val ownerPartyId: UUID,
    val name: String,
    val accountIds: Set<UUID>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(value: DelegationPortfolio) = DelegationPortfolioResponse(
            value.id,
            value.ownerPartyId,
            value.name,
            value.accountIds,
            value.createdAt,
            value.updatedAt,
        )
    }
}

/**
 * Business account scopes. The edge sends a business party only after `ActingForResolver` has
 * checked a current party-service mandate; this resource additionally requires it to equal owner.
 */
@Path("/api/v1/delegation-portfolios")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class DelegationPortfolioResource(private val service: DelegationPortfolioService) {
    @POST
    @Authorize(action = "delegation.portfolio.create", resource = "#request.ownerPartyId")
    suspend fun create(
        request: DelegationPortfolioRequest?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: CallerPartyId,
    ): Response {
        requireNotNull(request) { "request body is required" }
        val created = service.create(
            customerPartyId,
            requireNotNull(request.ownerPartyId) { "ownerPartyId is required" },
            requireNotNull(request.name) { "name is required" },
            requireNotNull(request.accountIds) { "accountIds is required" },
        )
        return Response.status(Response.Status.CREATED).entity(DelegationPortfolioResponse.from(created)).build()
    }

    @GET
    @Path("/owner/{ownerPartyId}")
    @Authorize(action = "delegation.portfolio.read", resource = "#ownerPartyId")
    suspend fun list(
        @PathParam("ownerPartyId") ownerPartyId: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: CallerPartyId,
    ): List<DelegationPortfolioResponse> =
        service.list(customerPartyId, ownerPartyId).map(DelegationPortfolioResponse::from)

    @GET
    @Path("/{id}")
    @Authorize(action = "delegation.portfolio.read", resource = "#id")
    suspend fun get(
        @PathParam("id") id: UUID,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerPartyId: CallerPartyId,
    ): DelegationPortfolioResponse = DelegationPortfolioResponse.from(service.get(customerPartyId, id))
}
