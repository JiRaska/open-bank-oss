// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.application.port.`in`.*
import com.openbank.balance.application.usecase.*
import com.openbank.balance.infrastructure.client.AccountServiceClient
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.util.UUID

/**
 * Access control (K7 / ADR-0018): balance-service moves value, so **no endpoint is `@PermitAll`**.
 * Reads are open to service callers + viewers/operators; money-moving writes (hold/credit/debit/
 * initialize) are restricted to the service identity used by the transaction saga and account
 * onboarding plus human operators; the overdraft-limit override is restricted to supervisor/admin
 * (limit overrides are a supervisory action per [Roles.SUPERVISOR]). Enforced by Quarkus OIDC and
 * locked by BalanceResourceSecurityTest.
 *
 * Authz migration (ADR-0034): the money-moving credit/debit legs keep their coarse [RolesAllowed]
 * gate AND additionally carry the resource-scoped [Authorize] OPA check (advisory phase 3 — denies
 * log at WARN, request still proceeds). Both must pass, per the libs authz contract, so layering OPA
 * on top of RBAC strengthens rather than weakens authz on a value-moving service.
 *
 * Customer ownership (A1 / issue #628): if X-Customer-Party-Id is present (set by customer-edge for
 * retail sessions), GET endpoints verify the account belongs to that party before returning data.
 * A mismatched or missing account returns 404 — not 403 — so the endpoint is not an existence oracle.
 */
@Path("/api/v1/balances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class BalanceResource(private val svc: BalanceUseCase, private val accountClient: AccountServiceClient) {

    companion object {
        private const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"
        private val NOT_FOUND: Response = Response.status(404)
            .entity("""{"error":"not found"}""").type(MediaType.APPLICATION_JSON).build()
    }

    @GET
    @Path("/{accountId}")
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    suspend fun getBalances(
        @PathParam("accountId") accountId: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        if (customerPartyId != null) {
            val ownerPartyId = accountClient.getPartyId(accountId)
            if (ownerPartyId == null || ownerPartyId != customerPartyId) return NOT_FOUND
        }
        return Response.ok(mapOf("balances" to svc.getBalances(accountId))).build()
    }

    @GET
    @Path("/{accountId}/{currency}")
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    suspend fun getBalance(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        // Optional point-in-time read: end-of-day booked balance as of this date (ISO-8601). Omit for
        // the current live balance. Statement period-close uses it to anchor opening/closing balances.
        @QueryParam("asOf") asOf: java.time.LocalDate?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        if (customerPartyId != null) {
            val ownerPartyId = accountClient.getPartyId(accountId)
            if (ownerPartyId == null || ownerPartyId != customerPartyId) return NOT_FOUND
        }
        return Response.ok(svc.getBalance(GetBalanceQuery(accountId, currency, asOf))).build()
    }

    @POST
    @Path("/{accountId}/holds")
    @RolesAllowed(Roles.SERVICE, Roles.OPERATOR, Roles.ADMIN)
    suspend fun placeHold(@PathParam("accountId") accountId: UUID, body: PlaceHoldRequest): Response {
        val hold = svc.placeHold(
            PlaceHoldCommand(accountId, body.amount, body.currency, body.reason, body.referenceId, body.ttlSeconds),
        )
        return Response.status(201).entity(hold).build()
    }

    @DELETE
    @Path("/holds/{holdId}")
    @RolesAllowed(Roles.SERVICE, Roles.OPERATOR, Roles.ADMIN)
    suspend fun releaseHold(@PathParam("holdId") holdId: UUID): Response =
        Response.ok(svc.releaseHold(ReleaseHoldCommand(holdId))).build()

    @POST
    @Path("/{accountId}/credit")
    @RolesAllowed(Roles.SERVICE, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "balance.credit", resource = "#accountId")
    suspend fun credit(@PathParam("accountId") accountId: UUID, body: BalanceOperationRequest): Response =
        Response.ok(svc.credit(CreditAccountCommand(accountId, body.amount, body.currency, body.referenceId))).build()

    @POST
    @Path("/{accountId}/debit")
    @RolesAllowed(Roles.SERVICE, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "balance.debit", resource = "#accountId")
    suspend fun debit(@PathParam("accountId") accountId: UUID, body: BalanceOperationRequest): Response =
        Response.ok(svc.debit(DebitAccountCommand(accountId, body.amount, body.currency, body.referenceId))).build()

    @POST
    @Path("/{accountId}/initialize")
    @RolesAllowed(Roles.SERVICE, Roles.OPERATOR, Roles.ADMIN)
    suspend fun initialize(@PathParam("accountId") accountId: UUID, body: InitializeRequest): Response {
        val balance = svc.initializeBalance(
            InitializeBalanceCommand(
                accountId,
                body.currency,
                body.initialAmount ?: BigDecimal.ZERO,
                body.arrangedOverdraftLimit ?: BigDecimal.ZERO,
            ),
        )
        return Response.status(201).entity(balance).build()
    }

    @PUT
    @Path("/{accountId}/{currency}/overdraft-limit")
    @RolesAllowed(Roles.SUPERVISOR, Roles.ADMIN)
    suspend fun setOverdraftLimit(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        body: OverdraftLimitRequest,
    ): Response = Response.ok(
        svc.setOverdraftLimit(SetOverdraftLimitCommand(accountId, currency, body.arrangedOverdraftLimit)),
    ).build()
}

data class PlaceHoldRequest(
    val amount: BigDecimal,
    val currency: String,
    val reason: String,
    val referenceId: String,
    val ttlSeconds: Long? = null,
)
data class BalanceOperationRequest(val amount: BigDecimal, val currency: String, val referenceId: String)
data class InitializeRequest(
    val currency: String,
    val initialAmount: BigDecimal? = null,
    val arrangedOverdraftLimit: BigDecimal? = null,
)
data class OverdraftLimitRequest(val arrangedOverdraftLimit: BigDecimal)
