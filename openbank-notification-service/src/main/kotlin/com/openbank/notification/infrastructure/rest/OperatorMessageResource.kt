// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.notification.application.OperatorMessageRejected
import com.openbank.notification.application.OperatorMessageRequest
import com.openbank.notification.application.OperatorMessageService
import com.openbank.notification.domain.model.OperatorMessageTemplate
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Operator-initiated customer messaging (ADR-0176). `opsmessage.compose` is a distinct action
 * namespace, not `notification.*` (ADR-0176 D4): `rest.rego`'s `edge-service-notification` rule
 * grants every `notification.*` action to the customer-edge M2M identity via a `startswith`
 * match, and this write path must never be reachable that way.
 *
 * `four_eyes.actions` in `rules.yaml` flags this action; when `AUTHZ_FOUR_EYES_ENFORCE=true`
 * (`false` by default here, matching every other four-eyes-wired service — flipping it is a
 * deliberate, separate operational decision, not shipped with this endpoint), a maker's call is
 * paused by `AuthorizeInterceptor` with HTTP 202 and a `PendingApproval` id; the operator retries
 * with `X-Approval-Id` once a different operator decides it via `ApprovalResource`.
 */
@Path("/api/v1/notifications/messages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Operator Messages", description = "Operator-initiated customer messaging (ADR-0176)")
class OperatorMessageResource {

    @Inject
    lateinit var service: OperatorMessageService

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "opsmessage.compose")
    @Operation(summary = "Send a customer a message from a reviewed, closed catalogue of templates")
    suspend fun compose(request: ComposeMessageRequest): Response {
        val notificationId = try {
            service.compose(
                OperatorMessageRequest(
                    partyId = request.partyId,
                    template = request.template,
                    recipient = request.recipient,
                    variables = request.variables,
                ),
            )
        } catch (e: OperatorMessageRejected) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("code" to "BAD_REQUEST", "message" to e.message))
                .build()
        }
        return Response.status(Response.Status.CREATED)
            .entity(mapOf("id" to notificationId))
            .build()
    }
}

data class ComposeMessageRequest(
    val partyId: UUID,
    val template: OperatorMessageTemplate,
    val recipient: String,
    val variables: Map<String, String> = emptyMap(),
)
