// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.rest

import com.openbank.engagement.application.usecase.EngagementService
import com.openbank.engagement.domain.model.BadgeType
import com.openbank.engagement.domain.model.EngagementProfile
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

data class EngagementResponse(
    val partyId: UUID,
    val enrolled: Boolean,
    val streakDays: Int,
    val totalPoints: Int,
    val badges: Set<BadgeType>,
    val updatedAt: Instant,
) {
    companion object {
        fun from(p: EngagementProfile) =
            EngagementResponse(p.partyId, p.enrolled, p.streakDays, p.totalPoints, p.badges, p.updatedAt)
    }
}

/**
 * ADR-0220 D3 rewards hub — read the engagement profile and manage opt-in/out.
 * The profile is created lazily on first read (default: not enrolled, zero state).
 */
@Path("/api/v1/engagement")
@ApplicationScoped
class EngagementResource(private val service: EngagementService) {

    @GET
    @Path("/{partyId}")
    @Authorize(action = "engagement.read", resource = "#partyId")
    suspend fun get(@PathParam("partyId") partyId: UUID): EngagementResponse =
        EngagementResponse.from(service.getOrCreate(partyId))

    @POST
    @Path("/{partyId}/opt-in")
    @Authorize(action = "engagement.enroll", resource = "#partyId")
    suspend fun optIn(@PathParam("partyId") partyId: UUID): Response =
        Response.ok(EngagementResponse.from(service.optIn(partyId))).build()

    @POST
    @Path("/{partyId}/opt-out")
    @Authorize(action = "engagement.enroll", resource = "#partyId")
    suspend fun optOut(@PathParam("partyId") partyId: UUID): Response =
        Response.ok(EngagementResponse.from(service.optOut(partyId))).build()
}
