// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.wealth.application.port.`in`.DeclareHoldingCommand
import com.openbank.wealth.application.port.`in`.DeclaredHoldingUseCase
import com.openbank.wealth.application.port.`in`.RevalueHoldingCommand
import com.openbank.wealth.application.port.out.HoldingNotFoundException
import com.openbank.wealth.domain.model.Valuation
import com.openbank.wealth.infrastructure.rest.dto.DeclareHoldingRequest
import com.openbank.wealth.infrastructure.rest.dto.HoldingResponse
import com.openbank.wealth.infrastructure.rest.dto.RevalueHoldingRequest
import com.openbank.wealth.infrastructure.rest.dto.ValuationDto
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
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
 * Declared off-platform holdings (ADR-0301 D1).
 *
 * NOTE the annotation order: `@Path` must sit immediately above `class`, with no top-level
 * declaration between them. A Kotlin annotation binds to the NEXT declaration, so a helper
 * function slipped in there silently steals the `@Path` — the resource is then never registered
 * and every call answers 404 on a running pod while the class still compiles, still is a CDI bean
 * and still passes any unit test that calls it directly (#3371).
 */
@Tag(name = "Wealth", description = "Customer-declared off-platform holdings (ADR-0301)")
@Path("/api/v1/holdings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
class WealthResource {

    // Field injection, not constructor: `LongParameterList` fires AT detekt's threshold of 9, and
    // a resource that later gains a metrics port would trip it on the constructor.
    @Inject
    lateinit var holdings: DeclaredHoldingUseCase

    /**
     * The header is declared NULLABLE and checked in the body, and that is the only shape that
     * works. JAX-RS injects `null` for an absent parameter; on a plain `fun` Kotlin emits
     * `checkNotNullParameter` at offset 0, so a `require` in the body is dead code and the absent
     * header is a 500 — the exact case the guard was written for. `requireNotNull` then maps to
     * 400 through libs-runtime (#3104).
     */
    @POST
    @Operation(summary = "Declare a holding the bank does not hold")
    @Authorize(action = "wealth.holding.declare")
    suspend fun declare(
        @HeaderParam("X-Customer-Party-Id") ownerPartyId: String?,
        request: DeclareHoldingRequest,
    ): Response {
        val owner = requireNotNull(ownerPartyId) { "header 'X-Customer-Party-Id' is required" }
        val holding = holdings.declare(
            DeclareHoldingCommand(
                ownerPartyId = UUID.fromString(owner),
                holdingType = request.holdingType,
                label = request.label,
                valuation = request.valuation.toDomain(),
                ownershipShare = request.ownershipShare,
                externalReference = request.externalReference,
                documentIds = request.documentIds,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(HoldingResponse.from(holding)).build()
    }

    @GET
    @Operation(summary = "List a party's active and pledged holdings")
    @Authorize(action = "wealth.holding.read")
    suspend fun list(@HeaderParam("X-Customer-Party-Id") ownerPartyId: String?): List<HoldingResponse> {
        val owner = requireNotNull(ownerPartyId) { "header 'X-Customer-Party-Id' is required" }
        return holdings.listForParty(UUID.fromString(owner)).map(HoldingResponse::from)
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Read one holding")
    @Authorize(action = "wealth.holding.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID): HoldingResponse =
        holdings.findById(id)?.let(HoldingResponse::from) ?: throw HoldingNotFoundException(id)

    @PUT
    @Path("/{id}/valuation")
    @Operation(summary = "Restate a holding's value; does not move any loan's ECL")
    @Authorize(action = "wealth.holding.revalue", resource = "#id")
    suspend fun revalue(@PathParam("id") id: UUID, request: RevalueHoldingRequest): HoldingResponse =
        HoldingResponse.from(holdings.revalue(RevalueHoldingCommand(id, request.valuation.toDomain())))

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Withdraw a holding; refused while pledged as lending collateral")
    @Authorize(action = "wealth.holding.withdraw", resource = "#id")
    suspend fun withdraw(@PathParam("id") id: UUID): HoldingResponse =
        HoldingResponse.from(holdings.withdraw(id))
}

private fun ValuationDto.toDomain() = Valuation(
    amount = amount,
    currency = currency,
    valuedAt = valuedAt,
    source = source,
    appraiserReference = appraiserReference,
)
