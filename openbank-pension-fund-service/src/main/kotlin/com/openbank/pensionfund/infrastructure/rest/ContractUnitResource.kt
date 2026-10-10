// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pensionfund.application.usecase.ContractValuation
import com.openbank.pensionfund.application.usecase.PlaceOrderCommand
import com.openbank.pensionfund.application.usecase.UnitRegisterService
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.pensionfund.domain.model.UnitTransaction
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * The unit register per pension contract. Callers are pension-service (through its
 * FundAdministrationPort) and operators; participants never reach this service directly.
 */
@Tag(name = "Unit register", description = "Forward-priced unit orders and holdings per contract (ADR-0334)")
@Path("/api/v1/contracts/{contractId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ContractUnitResource {

    @Inject
    lateinit var register: UnitRegisterService

    @POST
    @Path("/orders")
    @Operation(summary = "Place a subscription, redemption or switch; it queues until the next published NAV")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.order.place", resource = "#contractId")
    suspend fun place(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: OrderDto?,
    ): Response {
        val key = requireNotNull(idempotencyKey?.takeIf { it.isNotBlank() }) { "header 'Idempotency-Key' is required" }
        val body = requireNotNull(request) { "request body is required" }
        val order = register.place(
            PlaceOrderCommand(
                contractId = contractId,
                fundId = requireNotNull(body.fundId) { "field 'fundId' is required" },
                type = requireNotNull(body.type) { "field 'type' is required" },
                amount = body.amount,
                units = body.units,
                targetFundId = body.targetFundId,
                idempotencyKey = key,
            ),
        )
        return Response.status(Response.Status.ACCEPTED).entity(order).build()
    }

    @GET
    @Path("/orders")
    @Operation(summary = "Orders of a contract, newest first")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.holding.read", resource = "#contractId")
    suspend fun orders(@PathParam("contractId") contractId: UUID): List<UnitOrder> = register.orders(contractId)

    @GET
    @Path("/holdings")
    @Operation(summary = "Unit holdings of a contract valued at each fund's latest published NAV")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.holding.read", resource = "#contractId")
    suspend fun holdings(@PathParam("contractId") contractId: UUID): ContractValuation = register.valuation(contractId)

    @GET
    @Path("/transactions")
    @Operation(summary = "Priced unit transactions of a contract, newest first")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.holding.read", resource = "#contractId")
    suspend fun transactions(@PathParam("contractId") contractId: UUID): List<UnitTransaction> =
        register.transactions(contractId)
}
