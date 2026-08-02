// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.rest

import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.ConvertCommand
import com.openbank.fx.application.port.`in`.FxUseCase
import com.openbank.fx.application.port.`in`.GetRateHistoryQuery
import com.openbank.fx.application.port.`in`.GetRateQuery
import com.openbank.fx.domain.model.RateSource
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.time.Instant
import java.util.UUID

data class ConvertRequest(
    val partyId: UUID,
    val accountId: UUID?,
    val partyName: String,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmountMinorUnits: Long,
)

@Path("/api/v1/fx")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "FX", description = "Foreign exchange rates and conversions — ECB aligned")
class FxResource(private val fxUseCase: FxUseCase, private val cnbIngestion: CnbRateIngestionUseCase) {

    @GET
    @Path("/rates")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "fx.list", resource = "")
    @Operation(summary = "Get all current FX rates")
    suspend fun getRates(): Response = Response.ok(fxUseCase.getAllRates()).build()

    @GET
    @Path("/rates/{base}/{quote}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "fx.read", resource = "")
    @Operation(summary = "Get specific FX rate; ?source=CNB returns the central-bank fixing")
    suspend fun getRate(
        @PathParam("base") base: String,
        @PathParam("quote") quote: String,
        @QueryParam("source") source: String?,
    ): Response {
        val rate = if (source?.uppercase() == RateSource.CNB.name) {
            cnbIngestion.getCnbRate(base.uppercase(), quote.uppercase())
        } else {
            fxUseCase.getRate(GetRateQuery(base.uppercase(), quote.uppercase()))
        }
        return rate?.let { Response.ok(it).build() }
            ?: Response.status(404).entity(mapOf("error" to "Rate not found for $base/$quote")).build()
    }

    @POST
    @Path("/convert")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "fx.convert", resource = "")
    @Operation(summary = "Execute FX conversion")
    suspend fun convert(req: ConvertRequest?, @HeaderParam("Idempotency-Key") key: String): Response {
        // A JSON `null` body deserialises to null despite the non-nullable Kotlin type, so the
        // first field access threw NPE and this answered 500 (#3038). libs-runtime maps
        // IllegalArgumentException to 400.
        requireNotNull(req) { "request body is required" }
        require(key.isNotBlank()) { "Idempotency-Key required" }
        val conv = fxUseCase.convert(
            ConvertCommand(
                idempotencyKey = key,
                partyId = req.partyId,
                accountId = req.accountId,
                fromCurrency = req.fromCurrency,
                toCurrency = req.toCurrency,
                fromAmountMinorUnits = req.fromAmountMinorUnits,
                partyName = req.partyName,
            ),
        )
        return Response.created(URI.create("/api/v1/fx/conversions/${conv.id}")).entity(conv).build()
    }

    @GET
    @Path("/rates/{base}/{quote}/history")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "fx.read", resource = "")
    @Operation(summary = "Historical rates for a currency pair (all sources, newest first)")
    @Suppress("LongParameterList", "MagicNumber")
    suspend fun getRateHistory(
        @PathParam("base") base: String,
        @PathParam("quote") quote: String,
        @QueryParam("source") sourceStr: String?,
        @QueryParam("from") fromStr: String?,
        @QueryParam("to") toStr: String?,
        @QueryParam("limit") limit: Int?,
        @QueryParam("offset") offset: Int?,
    ): Response {
        val source = sourceStr?.uppercase()?.let { s ->
            runCatching { RateSource.valueOf(s) }.getOrNull()
                ?: return Response.status(Response.Status.BAD_REQUEST)
                    .entity(mapOf("error" to "Unknown source '$s'; valid: ${RateSource.entries.joinToString()}"))
                    .build()
        }
        val from = fromStr?.let {
            runCatching { Instant.parse(it) }.getOrNull()
                ?: return Response.status(Response.Status.BAD_REQUEST)
                    .entity(mapOf("error" to "Invalid 'from' instant: $it")).build()
        }
        val to = toStr?.let {
            runCatching { Instant.parse(it) }.getOrNull()
                ?: return Response.status(Response.Status.BAD_REQUEST)
                    .entity(mapOf("error" to "Invalid 'to' instant: $it")).build()
        }
        if (from != null && to != null && from > to) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "'from' must be before 'to'")).build()
        }

        val history = fxUseCase.getRateHistory(
            GetRateHistoryQuery(
                baseCurrency = base.uppercase(),
                quoteCurrency = quote.uppercase(),
                source = source,
                from = from,
                to = to,
                limit = (limit ?: 100).coerceIn(1, 365),
                offset = (offset ?: 0).coerceAtLeast(0),
            ),
        )
        return Response.ok(history).build()
    }

    @GET
    @Path("/conversions/{id}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "fx.read", resource = "#id")
    @Operation(summary = "Get conversion by ID")
    suspend fun getConversion(@PathParam("id") id: UUID): Response =
        fxUseCase.getConversion(id)?.let { Response.ok(it).build() }
            ?: Response.status(404).entity(mapOf("error" to "Conversion not found")).build()
}
