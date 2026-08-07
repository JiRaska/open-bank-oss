// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.rest

import com.openbank.engagement.application.usecase.RecordEngagementEventUseCase
import com.openbank.engagement.application.usecase.ResolveSurfaceUseCase
import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.EngagementEventType
import com.openbank.engagement.domain.model.SurfaceContent
import com.openbank.engagement.domain.model.SurfaceSlot
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
 * The app-facing surface API (ADR-0220 D1/D2). No `@Authorize` policy exists yet for these two
 * actions in the shared `rest.rego` (`openbank-libs/governance/rules.yaml`-derived OPA bundle):
 * editing that file restamps every service's OPA bundle fleet-wide (per this repo's own
 * documented gitops footguns), which is exactly the kind of cross-cutting infra change kept out
 * of this PR alongside the Dockerfile and gitops manifests. Until that rule lands, an OPA sidecar
 * with `AUTHZ_ENFORCE=true` denies both endpoints by the policy's own `default allow := false` —
 * fail-closed, not fail-open, so this gap cannot leak data in the meantime.
 */
@Path("/api/v1/surfaces")
@ApplicationScoped
class SurfaceResource(private val resolve: ResolveSurfaceUseCase, private val record: RecordEngagementEventUseCase) {

    @GET
    @Path("/{slot}")
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
    suspend fun postEvent(request: EngagementEventRequest): Response {
        val slot = parseSlot(request.slot) ?: return badRequest("unknown slot '${request.slot}'")
        val type = EngagementEventType.entries.find { it.name == request.type }
            ?: return badRequest("unknown event type '${request.type}'")
        record.record(
            EngagementEvent(
                partyId = request.partyId,
                contentId = request.contentId,
                slot = slot,
                type = type,
                occurredAt = Instant.now(),
            ),
        )
        return Response.status(Response.Status.ACCEPTED).build()
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
    )
}

data class EngagementEventRequest(val partyId: UUID, val contentId: String, val slot: String, val type: String)
