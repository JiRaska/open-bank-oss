// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.rest

import com.openbank.fx.application.port.`in`.CnbPolicyRateUseCase
import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.IngestCnbFixingCommand
import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.fx.domain.cnb.CnbPolicyRateFact
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
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
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Ops/backfill surface for the ČNB central-bank fixing ingestion (ADR-0046). The daily ingest is
 * automated by `CnbRateIngestionScheduler`; this endpoint lets operators re-ingest a specific day
 * (idempotently) and read the latest stored ČNB rate. Ingested rates are also exposed on the main
 * FX rates endpoint via `GET /api/v1/fx/rates/{base}/CZK?source=CNB`.
 */
@Path("/api/v1/fx/cnb")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "FX – ČNB", description = "ČNB central-bank exchange-rate fixing ingestion (kurz devizového trhu)")
class CnbResource(
    private val ingestion: CnbRateIngestionUseCase,
    private val policyRates: CnbPolicyRateUseCase,
    private val clock: Clock,
) {

    @POST
    @Path("/ingest")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "fx.trigger", resource = "")
    @Operation(summary = "Ingest the ČNB fixing for a given day (idempotent); omit date for latest")
    suspend fun ingest(@QueryParam("date") date: String?): Response {
        val day = date?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        val result = ingestion.ingest(IngestCnbFixingCommand(day))
        return Response.ok(result).build()
    }

    @GET
    @Path("/rates/{base}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "fx.read", resource = "")
    @Operation(summary = "Get the latest ingested ČNB fixing for {base}/CZK")
    suspend fun getCnbRate(@PathParam("base") base: String): Response = ingestion.getCnbRate(base.uppercase(), "CZK")
        ?.let { Response.ok(it).build() }
        ?: Response.status(404).entity(mapOf("error" to "No ČNB rate for $base/CZK")).build()

    /**
     * The ČNB policy rate or minimum-reserve fact in effect on [asOf] (default: today in Prague):
     * the row with the latest `effectiveFrom <= asOf`, with its provenance. 404 when none is in
     * effect — never a default. Both parameters are nullable on purpose: JAX-RS injects null for an
     * absent one, and a non-null Kotlin type would turn that into a 500 before the body runs.
     */
    @GET
    @Path("/policy-rates/{instrument}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "fx.read", resource = "")
    @Operation(summary = "Get the ČNB policy rate or minimum-reserve fact in effect on asOf, with provenance")
    suspend fun getPolicyRate(
        @PathParam("instrument") instrument: String?,
        @QueryParam("asOf") asOf: String?,
    ): Response {
        val which = CnbPolicyInstrument.parse(requireNotNull(instrument) { "path parameter 'instrument' is required" })
        val day = asOf?.takeIf { it.isNotBlank() }?.let(::parseDate) ?: LocalDate.now(clock.withZone(PRAGUE))
        return policyRates.effectiveAt(which, day)
            ?.let { Response.ok(CnbPolicyRateResponse.of(it, day)).build() }
            ?: Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "No ČNB $which fact in effect on $day"))
                .build()
    }

    private fun parseDate(raw: String): LocalDate = try {
        LocalDate.parse(raw.trim())
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("query parameter 'asOf' must be an ISO date (YYYY-MM-DD), was '$raw'", e)
    }

    private companion object {
        val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")
    }
}

/** A policy-rate fact as served: [rate] is a fraction, [ratePercent] the same value in percent. */
data class CnbPolicyRateResponse(
    val instrument: String,
    val asOf: LocalDate,
    val effectiveFrom: LocalDate,
    val rate: BigDecimal,
    val ratePercent: BigDecimal,
    val sourceUrl: String,
    val fetchedAt: java.time.Instant,
    val contentSha256: String,
    val note: String?,
    val previousRate: BigDecimal?,
    val revisedAt: java.time.Instant?,
) {
    companion object {
        private val HUNDRED = BigDecimal(100)

        /** Drops the column's padding zeros without ever switching to exponent notation (10 % -> 1E+1). */
        private fun BigDecimal.plain(): BigDecimal = stripTrailingZeros().let {
            if (it.scale() <
                0
            ) {
                it.setScale(0)
            } else {
                it
            }
        }

        fun of(f: CnbPolicyRateFact, asOf: LocalDate) = CnbPolicyRateResponse(
            instrument = f.instrument.name,
            asOf = asOf,
            effectiveFrom = f.effectiveFrom,
            rate = f.rate.plain(),
            ratePercent = f.rate.multiply(HUNDRED).plain(),
            sourceUrl = f.sourceUrl,
            fetchedAt = f.fetchedAt,
            contentSha256 = f.contentSha256,
            note = f.note,
            previousRate = f.previousRate?.plain(),
            revisedAt = f.revisedAt,
        )
    }
}
