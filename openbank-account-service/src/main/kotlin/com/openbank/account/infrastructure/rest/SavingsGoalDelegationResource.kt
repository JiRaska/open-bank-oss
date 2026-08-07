// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.SavingsGoalDelegationGuard
import com.openbank.account.domain.model.SavingsDelegationIntent
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

/**
 * Machine-readable savings-goal delegation check (ADR-0232 D3) — same transitional
 * shape as the account and card `/check` endpoints: the customer-edge slice asks
 * here until it holds its own projection. Reuses the `account.read` OPA action.
 */
@Path("/api/v1/accounts/{accountId}/savings-goal/delegation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SavingsGoalDelegationResource(private val guard: SavingsGoalDelegationGuard) {

    @GET
    @Path("/check")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Whether a party may DEPOSIT / WITHDRAW / PROPOSE_WITHDRAW on the account's savings goal")
    suspend fun check(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("intent") intent: SavingsDelegationIntent?,
    ): Response {
        // #3104 — both are required; absent, they used to reach the guard as null and answer 500.
        requireNotNull(partyId) { "query parameter 'partyId' is required" }
        requireNotNull(intent) { "query parameter 'intent' is required" }
        val authorized = guard.isAuthorized(accountId, partyId, intent)
        return Response.ok(mapOf("authorized" to authorized)).build()
    }
}
