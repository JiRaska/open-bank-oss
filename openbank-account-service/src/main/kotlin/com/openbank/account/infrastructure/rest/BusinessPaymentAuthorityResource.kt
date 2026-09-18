// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.BusinessPaymentAuthorityService
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

/** Internal money-path decision; the edge derives actorPartyId from the human JWT, not a body. */
@Path("/api/v1/accounts/{accountId}/business-payment-authorization")
@Produces(MediaType.APPLICATION_JSON)
class BusinessPaymentAuthorityResource(private val authority: BusinessPaymentAuthorityService) {
    @GET
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Check whether one human may directly debit a business account")
    suspend fun check(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("actorPartyId") actorPartyId: UUID?,
    ): BusinessPaymentAuthorityResponse {
        requireNotNull(actorPartyId) { "query parameter 'actorPartyId' is required" }
        val decision = authority.decide(accountId, actorPartyId)
        return BusinessPaymentAuthorityResponse(
            authorized = decision.authorized,
            outcome = decision.outcome.name,
            ownerPartyId = decision.ownerPartyId,
        )
    }
}

data class BusinessPaymentAuthorityResponse(val authorized: Boolean, val outcome: String, val ownerPartyId: UUID?)
