// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.notification.infrastructure.persistence.repository.NotificationPreferenceRepository
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/** A party's push preferences; a party with no stored row is treated as all-on. */
data class NotificationPreferenceDto(
    val paymentsPush: Boolean = true,
    val productPush: Boolean = true,
    val marketingPush: Boolean = true,
)

@Path("/api/v1/preferences")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Preferences", description = "Per-party push-notification preferences (#2)")
class NotificationPreferenceResource {

    @Inject
    lateinit var repo: NotificationPreferenceRepository

    @GET
    @Path("/party/{partyId}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_CUSTOMER")
    @Authorize(action = "notification.preferences.read", resource = "#partyId")
    @Operation(summary = "Get a party's push preferences (all-on when never set)")
    suspend fun get(@PathParam("partyId") partyId: UUID): NotificationPreferenceDto {
        val row = repo.getByParty(partyId) ?: return NotificationPreferenceDto()
        return NotificationPreferenceDto(row.paymentsPush, row.productPush, row.marketingPush)
    }

    @PUT
    @Path("/party/{partyId}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_CUSTOMER")
    @Authorize(action = "notification.preferences.update", resource = "#partyId")
    @Operation(summary = "Set a party's push preferences")
    suspend fun set(@PathParam("partyId") partyId: UUID, req: NotificationPreferenceDto): NotificationPreferenceDto {
        val row = repo.upsert(partyId, req.paymentsPush, req.productPush, req.marketingPush)
        return NotificationPreferenceDto(row.paymentsPush, row.productPush, row.marketingPush)
    }
}
