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
 *
 * `resource = "#request"` matters more here than on a typical gated action. Every other
 * four-eyes-gated action in the fleet (e.g. `lending.disburse`) binds `resource` to an
 * ALREADY-EXISTING entity id, so a `PendingApproval` can only ever satisfy a retry against that
 * same entity. `opsmessage.compose` is create-with-rich-body — there is no pre-existing entity to
 * bind to — so without a resource binding, `AuthorizeInterceptor.satisfies()` would compare only
 * (action, resourceId=null, maker), making every pending approval for one maker interchangeable:
 * a checker approving message A would silently also authorize a later retry carrying a
 * completely different message B, never reviewed by anyone (code-review finding, PR #1368).
 * `#request` resolves via reflection (`extractResource`) to `request.toString()` — `data class
 * ComposeMessageRequest`'s generated `toString()` is a deterministic, content-derived
 * fingerprint of partyId+template+recipient+variables. Two calls with the same content produce
 * the same resourceId (the retry the maker is meant to make); any change to the content produces
 * a different one (a NEW pending approval, requiring a fresh checker decision) — server-computed,
 * so the maker cannot forge a match by claiming an id, only by resending byte-identical content.
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
    @Authorize(action = "opsmessage.compose", resource = "#request")
    @Operation(summary = "Send a customer a message from a reviewed, closed catalogue of templates")
    suspend fun compose(request: ComposeMessageRequest): Response {
        val notificationId = try {
            service.compose(
                OperatorMessageRequest(
                    partyId = request.partyId,
                    template = request.template,
                    recipient = request.recipient,
                    variables = request.requireVariables(),
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
    /**
     * Declared with a NULLABLE value type on purpose, because that is the truth on the wire.
     * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the VALUES of
     * a map, so `{"variables": {"note": null}}` deserialises happily into a `Map<String, String>`
     * holding a null. Writing the type honestly is what makes [requireVariables] reachable instead
     * of dead code.
     */
    val variables: Map<String, String?> = emptyMap(),
) {
    /**
     * `IllegalArgumentException` is mapped to 400 by libs-runtime's `CommonExceptionMappers`;
     * no service-local mapper is added (#526).
     */
    fun requireVariables(): Map<String, String> = variables.mapValues { (key, value) ->
        requireNotNull(value) { "variables[$key] must not be null" }
    }
}
