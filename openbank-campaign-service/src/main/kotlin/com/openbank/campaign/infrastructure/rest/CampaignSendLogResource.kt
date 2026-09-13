// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.CampaignEngagementQuery
import com.openbank.campaign.application.usecase.CampaignIncentiveOutcomeProjector
import com.openbank.campaign.application.usecase.CampaignSendLogQuery
import com.openbank.campaign.application.usecase.CampaignSummaryQuery
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/**
 * The send log endpoint, split out of [CampaignResource] so the lifecycle API and the operator's
 * read view stay separate concerns (and both stay under the detekt function-count threshold).
 */
@Path("/api/v1/campaigns")
@ApplicationScoped
class CampaignSendLogResource(
    private val query: CampaignSendLogQuery,
    private val summaries: CampaignSummaryQuery,
    private val engagement: CampaignEngagementQuery,
    private val incentives: CampaignIncentiveOutcomeProjector,
    @ConfigProperty(name = "openbank.campaign.incentive-outcome-projection.ready", defaultValue = "false")
    private val incentiveOutcomeProjectionReady: Boolean,
) {

    /**
     * Reach and delivery for every campaign in one call (issue #3296).
     *
     * The campaign list returns records only, so a console had no way to show whether anything
     * reached anyone without one request per campaign — an N+1 against a scale-to-zero service.
     * Three grouped queries answer it instead.
     *
     * Suppressions are carried through rather than folded away: "2 sent" and "2 sent, 40 suppressed
     * for consent" are different campaigns, and only the second says why reach was low.
     */
    @GET
    @Path("/summary")
    @Authorize(action = "campaign.read", resource = "")
    suspend fun summary(): Response = Response.ok(summaries.summaries()).build()

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
        val result = query.listSends(id, parsed, page, size)
        // The body stays an array. Wrapping it in a page object would change the response type from
        // `array` to `object` — a breaking contract change, and under ADR-0048 a major bump means
        // serving every path under a new URL major, which is out of all proportion to adding
        // pagination. The counts ride in headers, where adding them is additive.
        return Response.ok(result.items)
            .header("X-Total-Count", result.total)
            .header("X-Page", result.page)
            .header("X-Page-Size", result.size)
            .build()
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

    /** Per-step funnel for the journey view — every number a SQL aggregate, never a page fold. */
    @GET
    @Path("/{id}/journey")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun journey(@PathParam("id") id: UUID): Response = Response.ok(query.funnel(id)).build()

    /**
     * App attention after a validated campaign handoff.  These are event counts (not people and
     * not product conversions); an empty list means no attributable event has arrived, not proof
     * that an audience saw zero content.
     */
    @GET
    @Path("/{id}/engagement")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun engagement(@PathParam("id") id: UUID): Response = Response.ok(engagement.metrics(id)).build()

    /** Reward lifecycle funnel. Only `committed` is an authoritative redemption. */
    @GET
    @Path("/{id}/incentives")
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun incentives(@PathParam("id") id: UUID): Response {
        if (!incentiveOutcomeProjectionReady) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(mapOf("error" to "incentive outcome projection is not initialized"))
                .build()
        }
        return Response.ok(incentives.funnel(id)).build()
    }

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
