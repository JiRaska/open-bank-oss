// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fx.infrastructure.rest

import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.IngestCnbFixingCommand
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
import java.time.LocalDate

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
class CnbResource(private val ingestion: CnbRateIngestionUseCase) {

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
}
