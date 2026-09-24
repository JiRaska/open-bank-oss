// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.risk.application.port.`in`.CreateCurveSetCommand
import com.openbank.risk.application.port.`in`.CurveSetUseCase
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.MoneyMarketQuote
import com.openbank.risk.domain.curve.Tenor
import com.openbank.risk.domain.model.Provenance
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Operator-uploaded yield-curve sets (ADR-0313 D4). `@Path` sits immediately above `class` (#3371).
 */
@Tag(name = "Risk", description = "Yield-curve sets bootstrapped from money-market quotes (ADR-0313 D4)")
@Path("/api/v1/risk/curve-sets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
class CurveSetResource {

    @Inject
    lateinit var curveSets: CurveSetUseCase

    @POST
    @Operation(summary = "Upload money-market quotes per index; they are bootstrapped into zero curves and stored")
    @Authorize(action = "risk.curve-set.create")
    suspend fun create(request: CreateCurveSetRequest?): Response {
        val body = requireNotNull(request) { "request body is required" }
        val command = CreateCurveSetCommand(
            asOf = parseDate(requireNotNull(body.asOf) { "field 'asOf' is required" }),
            provenance = parseProvenance(requireNotNull(body.provenance) { "field 'provenance' is required" }),
            source = requireNotNull(body.source?.takeIf { it.isNotBlank() }) { "field 'source' is required" }
                .also { require(it.length <= MAX_SOURCE) { "field 'source' exceeds $MAX_SOURCE characters" } },
            quotes = requireNotNull(body.curves?.takeIf { it.isNotEmpty() }) { "field 'curves' is required" }
                .entries.associate { (name, quotes) -> parseIndex(name) to quotes.map { it.toQuote(name) } },
        )
        return Response.status(Response.Status.CREATED).entity(curveSets.create(command).toResponse()).build()
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "A stored curve set with its pillars and discount factors")
    @Authorize(action = "risk.curve-set.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID): Response = Response.ok(curveSets.get(id).toResponse()).build()

    private fun QuoteDto.toQuote(index: String) = MoneyMarketQuote(
        tenor = Tenor.parse(requireNotNull(tenor) { "curve '$index': every quote needs a 'tenor'" }),
        simpleRate = requireNotNull(rate) { "curve '$index': every quote needs a 'rate'" },
    )

    private fun parseDate(raw: String): LocalDate = try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("field 'asOf' must be an ISO date (YYYY-MM-DD)", e)
    }

    private fun parseProvenance(raw: String): Provenance = Provenance.entries.firstOrNull { it.wire == raw }
        ?: throw IllegalArgumentException("field 'provenance' must be 'synthetic' or 'production'")

    private fun parseIndex(raw: String): CurveIndex = CurveIndex.entries.firstOrNull { it.name == raw }
        ?: throw IllegalArgumentException("unknown curve index '$raw', expected one of ${CurveIndex.entries}")

    private companion object {
        const val MAX_SOURCE = 256
    }
}
