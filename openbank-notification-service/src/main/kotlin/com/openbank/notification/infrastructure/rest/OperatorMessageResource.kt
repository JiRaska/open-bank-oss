// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.notification.application.NotificationConsumer
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.OperatorMessagePurpose
import com.openbank.notification.domain.model.PushContentPolicy
import com.openbank.notification.infrastructure.persistence.entity.OperatorMessageEntity
import com.openbank.notification.infrastructure.persistence.repository.OperatorMessageRepository
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Operator-initiated customer messaging (ADR-0176). Two steps, not one, because
 * [com.openbank.libs.approval.ApprovalStore] cannot hold the actual message content and
 * [com.openbank.libs.authz.AuthorizeInterceptor] never runs this class's method bodies at all
 * on an un-approved first call — it short-circuits with its own 202 response before `ctx.proceed()`.
 * So there is nothing for a single annotated method to persist content INTO at the moment a
 * pending approval is created; the content has to already exist, keyed by an id the interceptor
 * can carry as `resource`:
 *
 * 1. [draft] — plain `@RolesAllowed`, its own `opsmessage.draft` action (NOT four-eyes gated).
 *    Validates and persists the message, returns its id.
 * 2. [submit] — `opsmessage.compose`, the sole `four_eyes.actions` entry. First call (no
 *    `X-Approval-Id`) pauses for a second approver; the maker's retry (once approved) actually
 *    sends, by calling [NotificationConsumer.dispatch] directly — reusing render/persist/deliver
 *    /oversight unchanged rather than round-tripping through Kafka for a caller already inside
 *    this service.
 */
@Path("/api/v1/opsmessages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Operator Messages", description = "ADR-0176 operator-initiated customer messaging")
class OperatorMessageResource {

    @Inject lateinit var repo: OperatorMessageRepository

    @Inject lateinit var consumer: NotificationConsumer

    @Inject lateinit var identity: SecurityIdentity

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "opsmessage.draft", resource = "")
    @Operation(summary = "Draft an operator-initiated customer message")
    suspend fun draft(request: DraftOperatorMessageRequest): Response {
        if (request.template != NotificationTemplate.OPERATOR_ACCOUNT_NOTICE) {
            return errorResponse(422, "UNSUPPORTED_TEMPLATE", "Only OPERATOR_ACCOUNT_NOTICE is composable today.")
        }
        // ADR-0176 D2: the whole point of the catalogue design is that this is the ONLY place
        // an operator-supplied value reaches a customer message, and it is validated BEFORE the
        // row is ever written — never free text, never reaching four-eyes or renderTemplate
        // unchecked.
        if (!REFERENCE_ID_PATTERN.matches(request.referenceId)) {
            return errorResponse(
                422,
                "INVALID_REFERENCE_ID",
                "referenceId must match ${REFERENCE_ID_PATTERN.pattern}.",
            )
        }
        // ADR-0176 D6: refused at the API, not merely hidden in the UI, until a real consent
        // gate exists (marketing scope in consent-service, marketing_consent mapped in
        // party-service, a check here — none of which exist yet).
        if (request.purpose == OperatorMessagePurpose.MARKETING) {
            return errorResponse(
                422,
                "MARKETING_NOT_SUPPORTED",
                "Marketing messages are refused until a consent gate exists.",
            )
        }
        val id = Ids.randomId()
        val entity = repo.create(
            id = id,
            partyId = request.partyId,
            template = request.template,
            referenceId = request.referenceId,
            purpose = request.purpose,
            makerId = makerId(),
        )
        return Response.status(201).entity(entity.toResponse()).build()
    }

    @POST
    @Path("/{id}/submit")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "opsmessage.compose", resource = "#id")
    @Operation(summary = "Submit a drafted message for sending (four-eyes gated)")
    suspend fun submit(@PathParam("id") id: UUID): Response {
        // This body only ever runs once a second operator has approved (see class KDoc) — a
        // fresh draft, an already-sent, or an already-rejected id are all just "not currently
        // sendable", surfaced the same way rather than distinguished, since none of them is
        // reachable from the maker's own retry loop without an approval already existing.
        val entity = repo.findByMessageId(id) ?: throw NotFoundException("no operator message with id=$id")
        if (entity.status != "PENDING_APPROVAL") {
            return errorResponse(409, "ALREADY_RESOLVED", "This message is already ${entity.status}.")
        }
        val notificationRequest = NotificationRequest(
            partyId = entity.partyId,
            channel = NotificationChannel.PUSH,
            template = NotificationTemplate.valueOf(entity.template),
            recipient = entity.partyId.toString(),
            variables = mapOf("referenceId" to entity.referenceId),
            pushContentPolicy = PushContentPolicy.WAKE_SIGNAL_ONLY,
        )
        consumer.dispatch(notificationRequest).awaitSuspending()
        repo.markSent(id)
        return Response.ok(entity.toResponse().copy(status = "SENT")).build()
    }

    // Reuses opsmessage.approve rather than inventing a fourth action name: ADR-0176 D4 names
    // exactly three actions (compose/approve/reject), and browsing "what needs my decision" is
    // conceptually part of the checker's role, not a distinct capability worth its own rego rule.
    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "opsmessage.approve", resource = "")
    @Operation(summary = "List operator messages awaiting a second approver")
    suspend fun listPending(
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
    ): Response {
        val (items, total) = repo.pageByStatus("PENDING_APPROVAL", page, size.coerceIn(1, 100))
        return Response.ok(mapOf("items" to items.map { it.toResponse() }, "total" to total)).build()
    }

    // identity.principal.name (preferred_username), NOT .subject (UUID) — MUST match how
    // AuthorizeInterceptor.buildQuery resolves the maker's Principal.id for ApprovalStore, or
    // the self-approval guard could silently fail to catch a maker approving their own request
    // (same trap ledger-service's ApprovalResource comments on).
    private fun makerId(): String = identity.principal?.name ?: "anonymous"

    private fun errorResponse(status: Int, code: String, message: String): Response = Response.status(status)
        .entity(ApiError(traceId = Ids.randomId().toString(), status = status, code = code, message = message))
        .build()

    companion object {
        private val REFERENCE_ID_PATTERN = Regex("^[A-Za-z0-9-]{1,40}$")
    }
}

data class DraftOperatorMessageRequest(
    val partyId: UUID,
    val template: NotificationTemplate,
    val referenceId: String,
    val purpose: OperatorMessagePurpose,
)

data class OperatorMessageResponse(
    val id: UUID,
    val partyId: UUID,
    val template: String,
    val referenceId: String,
    val purpose: String,
    val status: String,
)

fun OperatorMessageEntity.toResponse() = OperatorMessageResponse(
    id = messageId,
    partyId = partyId,
    template = template,
    referenceId = referenceId,
    purpose = purpose,
    status = status,
)
