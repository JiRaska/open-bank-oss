// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.CampaignSendLogQuery
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * The send log endpoint, split out of [CampaignResource] so the lifecycle API and the operator's
 * read view stay separate concerns (and both stay under the detekt function-count threshold).
 */
@Path("/api/v1/campaigns")
@ApplicationScoped
class CampaignSendLogResource(private val query: CampaignSendLogQuery) {

    /**
     * What happened per party per step, newest first.
     *
     * `outcome` is why this exists. A suppressed send — consent withdrawn, frequency cap, quiet
     * hours (ADR-0200 D6) — is invisible everywhere else: the enrolment reads the same whether the
     * party was contacted or deliberately skipped. Without this, "why did this customer get
     * nothing?" is only answerable with a psql session (#2895).
     *
     * Party identifiers only; the send log holds no contact details, so this exposes none.
     */
    @GET
    @Path("/{id}/sends")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun sends(
        @PathParam("id") id: UUID,
        @QueryParam("outcome") outcome: String?,
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("50") size: Int,
    ): Response {
        // An unrecognised outcome is a 400, never a silently unfiltered page: answering a filter
        // the caller did not ask for with every row looks like "no suppressions here".
        val parsed = outcome?.let {
            runCatching { SendOutcome.valueOf(it.uppercase()) }.getOrElse { return badOutcome(it) }
        }
        return Response.ok(query.listSends(id, parsed, page, size)).build()
    }

    /**
     * Counts per outcome for the whole campaign, so the console's headline numbers do not depend on
     * which page happens to be loaded.
     */
    @GET
    @Path("/{id}/sends/summary")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun sendSummary(@PathParam("id") id: UUID): Response =
        Response.ok(query.summary(id).mapKeys { it.key.name }).build()

    private fun badOutcome(cause: Throwable): Response = Response.status(Response.Status.BAD_REQUEST)
        .entity(
            mapOf(
                "error" to "unknown outcome",
                "allowed" to SendOutcome.entries.map { it.name },
                "detail" to cause.message,
            ),
        )
        .build()
}
