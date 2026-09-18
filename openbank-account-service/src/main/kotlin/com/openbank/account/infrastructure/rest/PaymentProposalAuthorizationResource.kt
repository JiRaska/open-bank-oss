// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.infrastructure.rest

import com.openbank.account.application.port.`in`.AuthorizationUseCase
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.Money
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.math.BigDecimal
import java.util.UUID

/** Maker-only authorization question. A true answer is never evidence to execute a payment. */
@Path("/api/v1/accounts/{accountId}/delegation/payment-proposal-authorization")
@Produces(MediaType.APPLICATION_JSON)
class PaymentProposalAuthorizationResource(private val authorization: AuthorizationUseCase) {
    @GET
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Whether a delegate may prepare, but not execute, a payment")
    suspend fun check(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("amount") amount: String?,
        @QueryParam("currency") currency: String?,
    ): Response {
        requireNotNull(partyId) { "query parameter 'partyId' is required" }
        require(!amount.isNullOrBlank()) { "query parameter 'amount' is required" }
        require(!currency.isNullOrBlank()) { "query parameter 'currency' is required" }
        val money = runCatching { Money.of(BigDecimal(amount), currency) }.getOrNull()
            ?: return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "amount/currency is not a valid monetary value"))
                .build()
        if (!money.isPositive()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "amount must be positive"))
                .build()
        }
        val decision = authorization.authorizePaymentProposal(accountId, partyId, money)
        return Response.ok(
            buildMap<String, Any> {
                put("authorized", decision.authorized)
                put("outcome", decision.outcome.name)
                if (decision.authorized) {
                    put("delegationId", checkNotNull(decision.delegationId).toString())
                    put("grantorPartyId", checkNotNull(decision.grantorPartyId).toString())
                }
            },
        ).build()
    }
}
