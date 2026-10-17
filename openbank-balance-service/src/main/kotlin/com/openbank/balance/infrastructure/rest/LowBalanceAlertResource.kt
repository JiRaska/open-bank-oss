// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.application.usecase.LowBalanceAccountNotOwnedException
import com.openbank.balance.application.usecase.LowBalanceAlertService
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.Money
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.util.UUID

data class LowBalanceAlertRequest(val enabled: Boolean, val threshold: BigDecimal, val rearmMargin: BigDecimal)

/** Customer-owned alert setting; only customer-edge's M2M identity is authorised by OPA. */
@Path("/api/v1/balances/{accountId}/{currency}/low-balance-alert")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class LowBalanceAlertResource(private val service: LowBalanceAlertService) {
    @GET
    @RolesAllowed(Roles.API)
    @Authorize(action = "balance.alert.read", resource = "#accountId")
    suspend fun get(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @HeaderParam("X-Customer-Party-Id") partyId: UUID?,
    ): Response {
        requireNotNull(partyId) { "customer party header is required" }
        val setting = try {
            service.get(accountId, currency, partyId)
        } catch (_: LowBalanceAccountNotOwnedException) {
            return Response.status(Response.Status.NOT_FOUND).build()
        }
        return if (setting == null) Response.status(Response.Status.NOT_FOUND).build() else Response.ok(setting).build()
    }

    @PUT
    @RolesAllowed(Roles.API)
    @Authorize(action = "balance.alert.update", resource = "#accountId")
    suspend fun put(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @HeaderParam("X-Customer-Party-Id") partyId: UUID?,
        request: LowBalanceAlertRequest?,
    ): Response {
        requireNotNull(partyId) { "customer party header is required" }
        requireNotNull(request) { "request body is required" }
        val threshold = Money.parseInbound(request.threshold, currency, "threshold").amount
        val margin = Money.parseInbound(request.rearmMargin, currency, "rearmMargin").amount
        val setting = try {
            service.configure(accountId, currency, partyId, threshold, margin, request.enabled)
        } catch (_: LowBalanceAccountNotOwnedException) {
            return Response.status(Response.Status.NOT_FOUND).build()
        }
        return Response.ok(setting).build()
    }
}
