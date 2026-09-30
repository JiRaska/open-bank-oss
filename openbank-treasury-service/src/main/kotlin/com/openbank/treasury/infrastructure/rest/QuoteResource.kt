// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.port.`in`.TreasuryQuoteUseCase
import com.openbank.treasury.domain.model.ProductType
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * The simulated counterparties' two-way quotes (ADR-0315 D9) — SYNTHETIC, sandbox only: the risk
 * engine's latest curve set plus each synthetic counterparty's configured spread. Read-only; the
 * same treasury staff who read the blotter, under its own OPA action `treasury.quote.read`.
 *
 * Query parameters are declared nullable and `requireNotNull`ed: a non-null Kotlin parameter is a
 * 500 for the absent case (root CLAUDE.md). NOTE the annotation order: `@Path` sits immediately
 * above `class` (#3371).
 */
@Tag(name = "Treasury", description = "Money-market deals with four-eyes booking (ADR-0315)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER)
@Path("/api/v1/treasury/quotes")
class QuoteResource {

    @Inject
    lateinit var quotes: TreasuryQuoteUseCase

    @GET
    @Operation(
        summary = "SYNTHETIC bid/ask of the simulated counterparties for a product, currency and tenor, " +
            "off the risk engine's latest curve set plus each counterparty's spread (ADR-0315 D9)",
    )
    @Authorize(action = "treasury.quote.read")
    suspend fun quotes(
        // Strings, not the enum / Int: JAX-RS answers a failed conversion of a query parameter with
        // 404, which would read as "no such route". A bad value is a 400 like every other one.
        @QueryParam("product") product: String?,
        @QueryParam("currency") currency: String?,
        @QueryParam("tenorDays") tenorDays: String?,
    ): QuoteBoardResponse = QuoteBoardResponse.from(
        quotes.quotes(
            requireNotNull(product) { "query parameter 'product' is required" }.let { p ->
                requireNotNull(ProductType.entries.firstOrNull { it.name == p }) { "unknown product '$p'" }
            },
            requireNotNull(currency) { "query parameter 'currency' is required" },
            requireNotNull(tenorDays) { "query parameter 'tenorDays' is required" }.let { t ->
                requireNotNull(t.toIntOrNull()) { "tenorDays must be a whole number of days" }
            },
        ),
    )
}
