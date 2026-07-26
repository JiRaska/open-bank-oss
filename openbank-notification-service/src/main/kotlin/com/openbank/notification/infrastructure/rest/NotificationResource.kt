// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.OperatorMessageTemplate
import com.openbank.notification.domain.model.OperatorMessageTemplateSensitivity
import com.openbank.notification.domain.model.TemplateSensitivity
import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
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
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
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
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "notification.read", resource = "#id")
    @Operation(summary = "Get notification by ID")
    suspend fun getNotification(@PathParam("id") id: UUID): Response {
        val n = repo.findById(id) ?: return Response.status(404).build()
        return Response.ok(notificationView(n)).build()
    }

    /**
     * Party-scoped single read for the customer app's fetch-on-tap (ADR-0135 §3, issue #1182).
     * The push payload now carries no amount/PII (see NotificationConsumer.sendPush); on tap the
     * app calls this to load the full detail. partyId is the edge-injected authoritative identity
     * (customer-edge translates the mobile JWT to ROLE_API and injects it) — the repository
     * SELECT is scoped by it, so a caller can never read another party's notification (IDOR guard,
     * same shape as [markRead]). A missing/other-party id is a 404 with no existence oracle.
     */
    @GET
    @Path("/{id}/self")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    @Authorize(action = "notification.read.self", resource = "#id")
    @Operation(summary = "Get one of the caller's own notifications (party-scoped)")
    suspend fun getOwnNotification(@PathParam("id") id: UUID, @QueryParam("partyId") partyId: UUID?): Response {
        partyId ?: return Response.status(Response.Status.BAD_REQUEST)
            .entity(mapOf("code" to "BAD_REQUEST", "message" to "partyId query parameter is required")).build()
        val n = repo.findByIdAndParty(id, partyId)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("code" to "NOT_FOUND", "message" to "Notification not found")).build()
        return Response.ok(notificationView(n)).build()
    }

    private fun notificationView(n: NotificationEntity) = mapOf(
        "id" to n.notificationId,
        "partyId" to n.partyId,
        "channel" to n.channel,
        "template" to n.template,
        "recipient" to n.recipient,
        "subject" to n.subject,
        "body" to bodyForRead(n.template, n.body),
        "status" to n.status,
        "sentAt" to n.sentAt,
        "readAt" to n.readAt,
        "createdAt" to n.createdAt,
    )

    /**
     * Mark one notification read (customer notification center). partyId is the edge-injected
     * authoritative identity — the repository scopes the UPDATE by it, so a caller can never
     * mark another party's notification (IDOR guard, same pattern as DeviceResource.deactivate).
     * Idempotent: re-marking an already-read notification is a 204 no-op.
     */
    @PATCH
    @Path("/{id}/read")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
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
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    @Authorize(action = "notification.mark-read", resource = "")
    @Operation(summary = "Mark all of a party's notifications as read")
    suspend fun markAllRead(@QueryParam("partyId") partyId: UUID?): Response {
        partyId ?: return Response.status(Response.Status.BAD_REQUEST)
            .entity(mapOf("code" to "BAD_REQUEST", "message" to "partyId query parameter is required")).build()
        val flipped = repo.markAllRead(partyId)
        return Response.ok(mapOf("marked" to flipped)).build()
    }

    /**
     * Second, independent control over the secret-template redaction (the first is
     * NotificationConsumer, which never persists a rendered secret). Rows written before
     * that fix — and any row whose write-path redaction failed — are redacted here on the
     * way out, so two controls must both fail to leak an OTP to an operator. Same
     * defense-in-depth shape as the ADR-0059 D3 scrubber.
     *
     * Checks BOTH template enums that can legitimately own this column (issue #1386): the
     * `template` string here is not exclusively [NotificationTemplate] — since ADR-0176,
     * [com.openbank.notification.application.OperatorMessageService] persists an
     * [OperatorMessageTemplate] name into the same column. The original single-enum lookup
     * failed open (returned [stored] unredacted with no check at all) for every
     * `OperatorMessageTemplate` row, silently reintroducing the exact secret-leak shape issue
     * #1325 closed for `NotificationTemplate`. Both current `OperatorMessageTemplate` constants
     * are non-secret, so this was latent, not exploitable — but a future one that embeds
     * something sensitive now has a real classifier to extend
     * ([OperatorMessageTemplateSensitivity]) instead of silently inheriting the fail-open path.
     *
     * A template string matching NEITHER enum returns [stored] verbatim rather than redacting.
     * That remains safe for the same reason as before: the write paths only ever persist a value
     * that parsed into one of these two enums (any other template is rejected as poison), so an
     * unmatched value here means an enum shrank after the row was written — and any such row was
     * already redacted by its write path or by V9. Fail-open keeps a legacy row readable instead
     * of 500-ing on it.
     */
    private fun bodyForRead(template: String, stored: String): String {
        val known = NotificationTemplate.entries.firstOrNull { it.name == template }
        if (known != null) {
            return if (TemplateSensitivity.isSecret(known)) TemplateSensitivity.REDACTED_BODY else stored
        }
        val operatorKnown = OperatorMessageTemplate.entries.firstOrNull { it.name == template }
        if (operatorKnown != null) {
            return if (OperatorMessageTemplateSensitivity.isSecret(operatorKnown)) {
                TemplateSensitivity.REDACTED_BODY
            } else {
                stored
            }
        }
        return stored
    }
}
