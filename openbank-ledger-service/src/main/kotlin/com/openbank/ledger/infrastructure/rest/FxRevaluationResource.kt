// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.FxRevaluationUseCase
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ops/backfill trigger for the daily FX revaluation (ADR-0046). The run is automated by
 * `FxRevaluationScheduler`; this endpoint re-runs it for a given business day (idempotently — one
 * entry per day, same-day re-run is a no-op).
 */
@Path("/api/v1/ledger/fx-revaluation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Ledger – FX revaluation", description = "Daily mark-to-ČNB revaluation of foreign FX positions")
class FxRevaluationResource(private val useCase: FxRevaluationUseCase) {

    @POST
    @RolesAllowed("ROLE_OPERATOR")
    @Authorize(action = "ledger.trigger", resource = "")
    @Operation(summary = "Run the daily FX revaluation for a business day (default: today, Europe/Prague)")
    suspend fun revalue(@QueryParam("date") date: String?): Response {
        // Blank means "omitted"; a malformed value is a client error — IllegalArgumentException maps
        // to 400 via libs-runtime CommonExceptionMappers, where a raw DateTimeParseException would
        // surface as a 500 (#8832).
        val day = date?.takeIf { it.isNotBlank() }?.let {
            try {
                LocalDate.parse(it)
            } catch (ex: java.time.format.DateTimeParseException) {
                throw IllegalArgumentException(
                    "query parameter 'date' must be an ISO-8601 date (yyyy-MM-dd), got '$it'",
                )
            }
        }
            ?: LocalDate.now(ZoneId.of("Europe/Prague"))
        return Response.ok(useCase.revalue(RevalueFxCommand(day))).build()
    }
}
