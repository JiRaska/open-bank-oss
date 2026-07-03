// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
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
                        "readAt" to n.readAt,
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
                "readAt" to n.readAt,
                "createdAt" to n.createdAt,
            ),
        ).build()
    }

    /**
     * Mark one notification read (customer notification center). partyId is the edge-injected
     * authoritative identity — the repository scopes the UPDATE by it, so a caller can never
     * mark another party's notification (IDOR guard, same pattern as DeviceResource.deactivate).
     * Idempotent: re-marking an already-read notification is a 204 no-op.
     */
    @PATCH
    @Path("/{id}/read")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_SERVICE", "ROLE_ADMIN")
    @Authorize(action = "notification.mark-read", resource = "#id")
    @Operation(summary = "Mark a notification as read")
    suspend fun markRead(@PathParam("id") id: UUID, @QueryParam("partyId") partyId: UUID?): Response {
        partyId ?: return Response.status(Response.Status.BAD_REQUEST)
            .entity(mapOf("code" to "BAD_REQUEST", "message" to "partyId query parameter is required")).build()
        return if (repo.markRead(id, partyId)) {
            Response.noContent().build()
        } else {
            Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("code" to "NOT_FOUND", "message" to "Notification not found")).build()
        }
    }

    /** Mark ALL of a party's notifications read. Returns the number flipped. */
    @PATCH
    @Path("/read-all")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_SERVICE", "ROLE_ADMIN")
    @Authorize(action = "notification.mark-read", resource = "")
    @Operation(summary = "Mark all of a party's notifications as read")
    suspend fun markAllRead(@QueryParam("partyId") partyId: UUID?): Response {
        partyId ?: return Response.status(Response.Status.BAD_REQUEST)
            .entity(mapOf("code" to "BAD_REQUEST", "message" to "partyId query parameter is required")).build()
        val flipped = repo.markAllRead(partyId)
        return Response.ok(mapOf("marked" to flipped)).build()
    }
}
