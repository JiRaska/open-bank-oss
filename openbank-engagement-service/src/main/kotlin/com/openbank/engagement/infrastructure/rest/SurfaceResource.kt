// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.rest

import com.openbank.engagement.application.port.out.CampaignBannerPlacementRepository
import com.openbank.engagement.application.usecase.RecordEngagementEventUseCase
import com.openbank.engagement.application.usecase.ResolveSurfaceUseCase
import com.openbank.engagement.domain.model.CampaignAttribution
import com.openbank.engagement.domain.model.CampaignBannerPlacement
import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.EngagementEventType
import com.openbank.engagement.domain.model.SurfaceCatalog
import com.openbank.engagement.domain.model.SurfaceContent
import com.openbank.engagement.domain.model.SurfaceSlot
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

/**
 * The app-facing surface API (ADR-0220 D1/D2), reached through the customer edge
 * (`edge-service-engagement` in `openbank-libs/governance/policies/rest.rego`) — the edge
 * injects the caller's authoritative partyId, so a client-supplied partyId never reaches this
 * service on its own authority.
 */
@Path("/api/v1/surfaces")
@ApplicationScoped
class SurfaceResource(
    private val resolve: ResolveSurfaceUseCase,
    private val record: RecordEngagementEventUseCase,
    private val banners: CampaignBannerPlacementRepository,
) {

    @GET
    @Path("/{slot}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    @Authorize(action = "engagement.surface.read", resource = "#partyId")
    suspend fun get(@PathParam("slot") slotName: String, @QueryParam("partyId") partyId: UUID?): Response {
        partyId ?: return badRequest("partyId query parameter is required")
        val slot = parseSlot(slotName) ?: return badRequest("unknown slot '$slotName'")

        return when (val result = resolve.resolve(partyId, slot)) {
            is ResolveSurfaceUseCase.Result.Rendered ->
                Response.ok(mapOf("state" to "ok", "content" to result.content.map { it.toDto() })).build()
            is ResolveSurfaceUseCase.Result.NotEligible ->
                Response.ok(
                    mapOf(
                        "state" to "not_eligible",
                        "reason" to (result.reason.denyReason?.name ?: "GATE_DENIED"),
                    ),
                )
                    .build()
            ResolveSurfaceUseCase.Result.Suppressed ->
                Response.ok(mapOf("state" to "suppressed")).build()
        }
    }

    @POST
    @Path("/events")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    @Authorize(action = "engagement.surface.recordEvent", resource = "#request.partyId")
    suspend fun postEvent(request: EngagementEventRequest): Response {
        val slot = parseSlot(request.slot) ?: return badRequest("unknown slot '${request.slot}'")
        val type = EngagementEventType.entries.find { it.name == request.type }
            ?: return badRequest("unknown event type '${request.type}'")
        val content = validateContent(request, slot) ?: return badRequest("unknown or incompatible content '${request.contentId}'")
        val campaignFields = listOf(request.campaignId, request.stepOrder, request.channel)
        val campaignAttribution = if (campaignFields.any { it != null }) {
            if (request.interactionRef == null || campaignFields.any { it == null }) {
                return badRequest("campaign attribution requires an interaction reference and all server fields")
            }
            runCatching {
                CampaignAttribution(
                    campaignId = requireNotNull(request.campaignId),
                    stepOrder = requireNotNull(request.stepOrder),
                    channel = requireNotNull(request.channel),
                )
            }.getOrElse { return badRequest(it.message ?: "invalid campaign attribution") }
        } else {
            null
        }
        if (campaignAttribution?.channel == "BANNER" && !content.isCampaignPlacement) {
            return badRequest("campaign banner attribution must name its assigned banner")
        }
        record.record(
            EngagementEvent(
                partyId = request.partyId,
                contentId = request.contentId,
                slot = slot,
                type = type,
                occurredAt = Instant.now(),
                interactionRef = request.interactionRef,
                campaignAttribution = campaignAttribution,
            ),
        )
        return Response.status(Response.Status.ACCEPTED).build()
    }

    /** Keeps ownership and slot validation together: an interaction reference cannot be replayed elsewhere. */
    private suspend fun validateContent(request: EngagementEventRequest, slot: SurfaceSlot): ValidatedContent? {
        val catalogue = SurfaceCatalog.ALL[request.contentId]
        if (catalogue != null) return catalogue.takeIf { it.slot == slot }?.let { ValidatedContent(false) }

        val isCampaignPlacement = request.contentId == CampaignBannerPlacement.CAMPAIGN_BANNER_CONTENT_ID &&
            request.interactionRef != null &&
            banners.belongsToParty(request.interactionRef, request.partyId)
        return isCampaignPlacement
            .takeIf { slot == SurfaceSlot.HOME_BANNER && request.type in INTERACTION_EVENT_TYPES }
            ?.let { ValidatedContent(true) }
    }

    private fun parseSlot(name: String): SurfaceSlot? = SurfaceSlot.entries.find { it.name == name }

    private fun badRequest(message: String) = Response.status(
        Response.Status.BAD_REQUEST,
    ).entity(mapOf("code" to "BAD_REQUEST", "message" to message)).build()

    private fun SurfaceContent.toDto() = mapOf(
        "id" to id,
        "slot" to slot.name,
        "type" to type.name,
        "variables" to variables,
        "values" to values,
        "deepLink" to deepLink,
        "interactionRef" to interactionRef,
    )

    private data class ValidatedContent(val isCampaignPlacement: Boolean)

    private companion object {
        val INTERACTION_EVENT_TYPES = setOf("IMPRESSION", "CLICK", "DISMISS")
    }
}

data class EngagementEventRequest(
    val partyId: UUID,
    val contentId: String,
    val slot: String,
    val type: String,
    /** Present only after customer-edge verified an opaque app interaction reference for this party. */
    val interactionRef: UUID? = null,
    /** Server-owned fields: customer-edge strips client values and resolves these from campaign-service. */
    val campaignId: UUID? = null,
    val stepOrder: Int? = null,
    val channel: String? = null,
)
