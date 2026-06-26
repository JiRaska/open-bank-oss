// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Path("/api/v1/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Notifications")
class NotificationResource {

    @Inject lateinit var repo: NotificationRepository

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_SERVICE")
    @Authorize(action = "notification.list", resource = "")
    @Operation(summary = "List notifications (paginated)")
    suspend fun listNotifications(
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
        @QueryParam("partyId") partyId: UUID?,
    ): Response {
        val (items, total) = if (partyId != null) {
            repo.pageByParty(partyId, page, size.coerceIn(1, 100))
        } else {
            repo.pageAll(page, size.coerceIn(1, 100))
        }
        return Response.ok(
            mapOf(
                "items" to items.map { n ->
                    mapOf(
                        "id" to n.notificationId,
                        "partyId" to n.partyId,
                        "channel" to n.channel,
                        "template" to n.template,
                        "recipient" to n.recipient,
                        "subject" to n.subject,
                        "status" to n.status,
                        "sentAt" to n.sentAt,
                        "createdAt" to n.createdAt,
                    )
                },
                "total" to total,
                "page" to page,
                "size" to size,
            ),
        ).build()
    }

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_SERVICE")
    @Authorize(action = "notification.read", resource = "#id")
    @Operation(summary = "Get notification by ID")
    suspend fun getNotification(@PathParam("id") id: UUID): Response {
        val n = repo.findById(id) ?: return Response.status(404).build()
        return Response.ok(
            mapOf(
                "id" to n.notificationId,
                "partyId" to n.partyId,
                "channel" to n.channel,
                "template" to n.template,
                "recipient" to n.recipient,
                "subject" to n.subject,
                "body" to n.body,
                "status" to n.status,
                "sentAt" to n.sentAt,
                "createdAt" to n.createdAt,
            ),
        ).build()
    }
}
