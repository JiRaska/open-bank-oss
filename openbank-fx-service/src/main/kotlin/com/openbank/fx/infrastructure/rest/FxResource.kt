// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.rest

import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.ConvertCommand
import com.openbank.fx.application.port.`in`.FxUseCase
import com.openbank.fx.application.port.`in`.GetRateHistoryQuery
import com.openbank.fx.application.port.`in`.GetRateQuery
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
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
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ConvertRequest(
    val partyId: UUID,
    val accountId: UUID?,
    val partyName: String,
    val fromCurrency: String,
    val toCurrency: String,
    val fromAmountMinorUnits: Long,
)

/**
 * Response for `GET /rates/{base}/{quote}`, mirroring the serialized [FxRate] shape.
 *
 * #3374: a quote derived by inverting the stored pair ([FxRate.inverted]) carried the source row's
 * [FxRate.id], so both directions answered under one identifier — a client caching by `id` would
 * serve one direction for the other, and an audit record referencing the quote id could not be
 * replayed to a direction. A derived quote therefore answers with [id] null and the source row id
 * in [derivedFrom]; a stored quote answers with its own [id] and [derivedFrom] null. The domain
 * object keeps the source id internally — `FxConversion.rateId` must keep pointing at the row the
 * price came from.
 */
data class FxRateResponse(
    val id: UUID?,
    val baseCurrency: String,
    val quoteCurrency: String,
    val bidRate: BigDecimal,
    val askRate: BigDecimal,
    val rateType: RateType,
    val source: RateSource,
    val validFrom: Instant,
    val validTo: Instant,
    val createdAt: Instant,
    val pair: String,
    val midRate: BigDecimal,
    val spread: BigDecimal,
    val derivedFrom: UUID?,
) {
    companion object {
        fun of(rate: FxRate, derivedFrom: UUID?) = FxRateResponse(
            id = if (derivedFrom != null) null else rate.id,
            baseCurrency = rate.baseCurrency,
            quoteCurrency = rate.quoteCurrency,
            bidRate = rate.bidRate,
            askRate = rate.askRate,
            rateType = rate.rateType,
            source = rate.source,
            validFrom = rate.validFrom,
            validTo = rate.validTo,
            createdAt = rate.createdAt,
            pair = rate.pair,
            midRate = rate.midRate,
            spread = rate.spread,
            derivedFrom = derivedFrom,
        )
    }
}

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
    @Operation(summary = "Get specific FX rate; ?source=CNB returns the central-bank fixing, ?asOf pins its day")
    suspend fun getRate(
        @PathParam("base") base: String,
        @PathParam("quote") quote: String,
        @QueryParam("source") source: String?,
        // Nullable ON PURPOSE, and it is the declaration that decides this, not the body: JAX-RS
        // injects null for an absent query parameter, and a non-nullable Kotlin `String` emits
        // `Intrinsics.checkNotNullParameter` at offset 0 — on a `suspend fun` it emits nothing at
        // all and the null flows in unchecked. Either way the guard would be a 500 or worse
        // (`check-nonnull-jaxrs-params.py`, #3104).
        @QueryParam("asOf") asOfStr: String?,
    ): Response {
        val requestedBase = base.uppercase()
        val requestedQuote = quote.uppercase()
        val asOf = asOfStr?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
                ?: return Response.status(Response.Status.BAD_REQUEST)
                    .entity(mapOf("error" to "Invalid 'asOf' date: $it (expected ISO yyyy-MM-dd)")).build()
        }
        // `asOf` is meaningful only for the central-bank fixing, which is the only source with a
        // per-business-day identity; a commercial quote is a live price with no "as of yesterday".
        // Rejecting the combination rather than ignoring it — a silently-dropped date parameter is
        // how a backfill reads as correct while marking at today's rate, the defect this closes.
        if (asOf != null && source?.uppercase() != RateSource.CNB.name) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "'asOf' is only supported with source=CNB")).build()
        }
        val body = if (source?.uppercase() == RateSource.CNB.name) {
            // The ČNB path only ever looks up the exact stored direction — never derived.
            cnbIngestion.getCnbRate(requestedBase, requestedQuote, asOf)
                ?.let { FxRateResponse.of(it, derivedFrom = null) }
        } else {
            fxUseCase.getRate(GetRateQuery(requestedBase, requestedQuote))
                ?.let { FxRateResponse.of(it.rate, it.derivedFrom) }
        }
        return body?.let { Response.ok(it).build() }
            ?: Response.status(404).entity(mapOf("error" to "Rate not found for $base/$quote")).build()
    }

    @POST
    @Path("/convert")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "fx.convert", resource = "")
    @Operation(summary = "Execute FX conversion")
    suspend fun convert(req: ConvertRequest?, @HeaderParam("Idempotency-Key") key: String?): Response {
        // A JSON `null` body deserialises to null despite the non-nullable Kotlin type, so the
        // first field access threw NPE and this answered 500 (#3038). libs-runtime maps
        // IllegalArgumentException to 400.
        requireNotNull(req) { "request body is required" }
        // #3104 — the sibling of the line above, one argument position over. An ABSENT header
        // injected null, so `key.isNotBlank()` threw NPE and this guard answered 500 rather than
        // the 400 it was written to give.
        require(!key.isNullOrBlank()) { "Idempotency-Key required" }
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
