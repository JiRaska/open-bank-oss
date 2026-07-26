// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.port.`in`.AuthorizationUseCase
import com.openbank.account.application.port.`in`.GrantAuthorizationCommand
import com.openbank.account.application.port.`in`.ListAuthorizationsQuery
import com.openbank.account.application.port.`in`.RevokeAuthorizationCommand
import com.openbank.account.infrastructure.rest.dto.AuthorizationResponse
import com.openbank.account.infrastructure.rest.dto.GrantAuthorizationRequest
import com.openbank.account.infrastructure.rest.dto.RevokeAuthorizationRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/api/v1/accounts/{accountId}/authorizations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AuthorizationResource(private val authorizationUseCase: AuthorizationUseCase) {

    @GET
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    suspend fun list(@PathParam("accountId") accountId: UUID): List<AuthorizationResponse> =
        authorizationUseCase.listAuthorizations(ListAuthorizationsQuery(accountId))
            .map { AuthorizationResponse.from(it) }

    @POST
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.authorize", resource = "#accountId")
    suspend fun grant(@PathParam("accountId") accountId: UUID, request: GrantAuthorizationRequest): Response {
        val auth = authorizationUseCase.grantAuthorization(
            GrantAuthorizationCommand(
                accountId = accountId,
                partyId = request.partyId,
                role = request.role,
                dailyLimit = request.dailyLimit?.toDomain(),
                transactionLimit = request.transactionLimit?.toDomain(),
                validFrom = request.validFrom,
                validTo = request.validTo,
                grantedBy = request.grantedBy,
            ),
        )
        return Response.status(201).entity(AuthorizationResponse.from(auth)).build()
    }

    @DELETE
    @Path("/{authorizationId}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.authorize", resource = "#accountId")
    suspend fun revoke(
        @PathParam("accountId") accountId: UUID,
        @PathParam("authorizationId") authorizationId: UUID,
        request: RevokeAuthorizationRequest,
    ): AuthorizationResponse {
        val auth = authorizationUseCase.revokeAuthorization(
            RevokeAuthorizationCommand(
                accountId = accountId,
                authorizationId = authorizationId,
                revokedBy = request.revokedBy,
                reason = request.reason,
            ),
        )
        return AuthorizationResponse.from(auth)
    }

    @GET
    @Path("/check")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    suspend fun check(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("partyId") partyId: UUID,
        @QueryParam("role") role: com.openbank.account.domain.model.AuthorizationRole,
    ): Response {
        val authorized = authorizationUseCase.isAuthorized(accountId, partyId, role)
        return Response.ok(mapOf("authorized" to authorized)).build()
    }
}
