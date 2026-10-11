// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pensionfund.application.usecase.NavService
import com.openbank.pensionfund.domain.model.TransactionCorrection
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Tag(name = "NAV", description = "Four-eyes NAV publication and correction (ADR-0334)")
@Path("/api/v1/navs")
@Produces(MediaType.APPLICATION_JSON)
class NavResource {

    @Inject
    lateinit var navs: NavService

    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @Path("/{navId}")
    @Operation(summary = "Read one NAV")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.nav.read", resource = "#navId")
    suspend fun get(@PathParam("navId") navId: UUID): NavResponse = NavResponse.from(navs.nav(navId))

    @POST
    @Path("/{navId}/approve")
    @Operation(summary = "Publish a calculated NAV (checker): settles queued orders, or re-prices for a correction")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.nav.approve", resource = "#navId")
    suspend fun approve(@PathParam("navId") navId: UUID): NavPublicationResponse {
        val publication = navs.publish(navId, identity.actor())
        return NavPublicationResponse(
            nav = NavResponse.from(publication.nav),
            settledOrders = publication.settledOrders,
            corrections = publication.corrections.map(CorrectionResponse::from),
        )
    }

    @POST
    @Path("/{navId}/reject")
    @Operation(summary = "Reject a calculated NAV (checker)")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.nav.approve", resource = "#navId")
    suspend fun reject(@PathParam("navId") navId: UUID): NavResponse =
        NavResponse.from(navs.reject(navId, identity.actor()))
}

data class NavPublicationResponse(
    val nav: NavResponse,
    val settledOrders: Int,
    val corrections: List<CorrectionResponse>,
)

data class CorrectionResponse(
    val transactionId: UUID,
    val contractId: UUID,
    val type: String,
    val unitsDelta: java.math.BigDecimal,
    val amountDelta: java.math.BigDecimal,
) {
    companion object {
        fun from(c: TransactionCorrection) = CorrectionResponse(
            transactionId = c.transaction.id,
            contractId = c.transaction.contractId,
            type = c.transaction.type.name,
            unitsDelta = c.unitsDelta,
            amountDelta = c.amountDelta,
        )
    }
}
