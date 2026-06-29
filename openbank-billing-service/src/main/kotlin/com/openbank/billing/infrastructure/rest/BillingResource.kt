// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.rest

import com.openbank.billing.application.usecase.FeeAssessmentService
import io.quarkus.security.Authenticated
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Fee assessment endpoint (ADR-0143 phase 2c, read path). `POST /api/v1/fees/assess` **computes**
 * an account's fee assessment for a cycle from live account/balance/catalog reads and returns it —
 * it **does not post** anything to the ledger (a dry run). The posting leg (outbox + ledger client)
 * and the four-eyes `ledger.post` authorization land in phase 2c-ii.
 */
@ApplicationScoped
@Path("/api/v1/fees")
@Produces(MediaType.APPLICATION_JSON)
class BillingResource(private val service: FeeAssessmentService) {

    @POST
    @Path("/assess")
    @Authenticated
    suspend fun assess(
        @QueryParam("cycleId") cycleId: String?,
        @QueryParam("accountId") accountId: String?,
        @QueryParam("currency") currency: String?,
    ): Response {
        if (cycleId.isNullOrBlank() || accountId.isNullOrBlank() || currency.isNullOrBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "cycleId, accountId and currency are required"))
                .build()
        }
        return Response.ok(service.assess(cycleId, accountId, currency)).build()
    }
}
