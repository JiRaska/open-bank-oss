// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.risk.application.port.`in`.PricingCommand
import com.openbank.risk.application.port.`in`.ValuationUseCase
import com.openbank.risk.domain.capital.ReportingCurrencyTotal
import com.openbank.risk.infrastructure.finance.LegBounds
import com.openbank.risk.infrastructure.finance.LegDefaults
import com.openbank.risk.infrastructure.finance.LegSpecJson
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Stateless pre-trade pricing (ADR-0344 D8): legs in, PV / PV01 / key-rate DV01 / FX delta out,
 * nothing persisted. A POST only because the deal does not fit a query string — it changes nothing,
 * so it is authorised as a curve-set READ (the answer reveals the curves and the caller's own legs,
 * nothing else), which every caller of the curve reads already holds. `@Path` sits immediately
 * above `class` (#3371).
 */
@Tag(name = "Risk", description = "Pre-trade pricing of treasury legs on a curve set (ADR-0344 D8)")
@Path("/api/v1/risk/pricing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, "ROLE_RISK", "ROLE_FINANCE")
class PricingResource {

    @Inject
    lateinit var valuation: ValuationUseCase

    @POST
    @Operation(
        summary = "Price HYPOTHETICAL legs on a curve set: PV, PV by leg, PV01, DV01 buckets, FX delta; no persistence",
    )
    @Authorize(action = "risk.curve-set.read", resource = "")
    suspend fun price(request: PricingRequest?): Response {
        val body = requireNotNull(request) { "request body is required" }
        val asOf = try {
            LocalDate.parse(requireNotNull(body.asOf) { "field 'asOf' is required" })
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("field 'asOf' must be an ISO date (YYYY-MM-DD)", e)
        }
        val curveSetId = body.curveSetId?.takeIf { it.isNotBlank() }?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("field 'curveSetId' must be a UUID", e)
            }
        }
        val currency = body.currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: ReportingCurrencyTotal.REPORTING_CURRENCY
        val legs = LegSpecJson.toLegs(
            body.legs,
            LegDefaults(body.calendars, body.businessDayConvention),
            LegBounds.PRICING_REQUEST,
        )
        val result = valuation.price(PricingCommand(asOf, curveSetId, currency, legs))
        return Response.ok(result.toResponse()).build()
    }
}
