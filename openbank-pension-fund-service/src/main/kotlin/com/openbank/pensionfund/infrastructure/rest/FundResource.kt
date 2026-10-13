// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pensionfund.application.usecase.FundAdministrationService
import com.openbank.pensionfund.application.usecase.NavService
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.NavRecord
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Segregated pension funds and their NAV calculation (ADR-0334 §1).
 *
 * Roles are declared per METHOD, never on the class: a class-level @RolesAllowed was measured
 * to win over a method-level one on these suspend endpoints (ROLE_API got 403 on reads).
 *
 * `@Path` sits immediately above `class`: a Kotlin annotation binds to the NEXT declaration, and a
 * top-level helper slipped in between would silently steal it (#3371).
 */
@Tag(name = "Funds", description = "Segregated pension funds and NAV (ADR-0334)")
@Path("/api/v1/funds")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class FundResource {

    @Inject
    lateinit var funds: FundAdministrationService

    @Inject
    lateinit var navs: NavService

    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Operation(summary = "Create a segregated fund")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.fund.manage")
    suspend fun create(request: FundRequest?): Response {
        val fund = funds.createFund(requireNotNull(request) { "request body is required" }.toDefinition())
        return Response.status(Response.Status.CREATED).entity(fund).build()
    }

    @GET
    @Operation(summary = "List funds")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.fund.read")
    suspend fun list(): List<Fund> = funds.funds()

    @GET
    @Path("/{fundId}")
    @Operation(summary = "Read one fund")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.fund.read", resource = "#fundId")
    suspend fun get(@PathParam("fundId") fundId: UUID): Fund = funds.fund(fundId)

    @PUT
    @Path("/{fundId}")
    @Operation(summary = "Amend a fund's descriptive and fee terms; ISIN and currency are immutable")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.fund.manage", resource = "#fundId")
    suspend fun amend(@PathParam("fundId") fundId: UUID, request: FundRequest?): Fund =
        funds.amendFund(fundId, requireNotNull(request) { "request body is required" }.toDefinition())

    @DELETE
    @Path("/{fundId}")
    @Operation(summary = "Close a fund; refused while units are outstanding or a strategy uses it")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.fund.manage", resource = "#fundId")
    suspend fun close(@PathParam("fundId") fundId: UUID): Fund = funds.closeFund(fundId)

    @POST
    @Path("/{fundId}/navs")
    @Operation(summary = "Calculate a NAV (maker); a second person publishes it")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.nav.calculate", resource = "#fundId")
    suspend fun calculateNav(@PathParam("fundId") fundId: UUID, request: NavCalculationDto?): Response {
        val nav = navs.calculate(
            fundId,
            requireNotNull(request) {
                "request body is required"
            }.toRequest(),
            identity.actor(),
        )
        return Response.status(Response.Status.CREATED).entity(NavResponse.from(nav)).build()
    }

    @GET
    @Path("/{fundId}/navs")
    @Operation(summary = "NAV history of a fund, newest first")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "pension-fund.nav.read", resource = "#fundId")
    suspend fun navHistory(@PathParam("fundId") fundId: UUID): List<NavResponse> =
        navs.navs(fundId).map(NavResponse::from)
}

data class NavResponse(
    val id: UUID,
    val fundId: UUID,
    val valuationDate: java.time.LocalDate,
    val grossAssets: java.math.BigDecimal,
    val accruedManagementFee: java.math.BigDecimal,
    val otherLiabilities: java.math.BigDecimal,
    val netAssets: java.math.BigDecimal,
    val unitsOutstanding: java.math.BigDecimal,
    val navPerUnit: java.math.BigDecimal,
    val status: String,
    val calculatedBy: String,
    val approvedBy: String?,
    val publishedAt: java.time.Instant?,
    val correctsNavId: UUID?,
) {
    companion object {
        fun from(n: NavRecord) = NavResponse(
            id = n.id,
            fundId = n.fundId,
            valuationDate = n.valuationDate,
            grossAssets = n.figures.grossAssets,
            accruedManagementFee = n.figures.accruedManagementFee,
            otherLiabilities = n.figures.otherLiabilities,
            netAssets = n.figures.netAssets,
            unitsOutstanding = n.figures.unitsOutstanding,
            navPerUnit = n.figures.navPerUnit,
            status = n.status.name,
            calculatedBy = n.calculatedBy,
            approvedBy = n.approvedBy,
            publishedAt = n.publishedAt,
            correctsNavId = n.correctsNavId,
        )
    }
}
