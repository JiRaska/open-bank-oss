// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.usecase.DeclineReviewRefusedException
import com.openbank.lending.application.usecase.DeclineReviewUseCase
import com.openbank.lending.domain.model.DeclineReview
import com.openbank.lending.domain.model.DeclineReviewDecision
import com.openbank.lending.domain.model.DeclineReviewDecisionRequest
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
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
 * Staff side of the customer-requested human review of an engine decline. Humans only: the roles are
 * the credit decision's own (credit-risk, admin), the action is NOT in `rules.yaml: role_action_matrix`
 * (that matrix grants to any HUMAN-typed principal, which includes service accounts), and the OPA
 * extension vetoes any machine principal for these actions. The reviewer identity is the JWT subject;
 * the service refuses a reviewer who is the applicant.
 */
@Path("/api/v1/lending/decline-reviews")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Lending Decline Review", description = "Human review of decision-engine declines")
@RolesAllowed("ROLE_CREDIT_RISK", "ROLE_ADMIN")
class DeclineReviewResource(private val reviews: DeclineReviewUseCase, private val identity: SecurityIdentity) {
    @GET
    @Authorize(action = "lending.declineReview.read", resource = "")
    @Operation(summary = "Pending decline reviews, oldest first, with the engine's recorded reasons")
    fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Uni<Response> =
        reviews.listPending(limit).map { list -> Response.ok(list.map { it.toStaffDto() }).build() }

    @POST
    @Path("/{id}/decision")
    @Authorize(action = "lending.declineReview.decide", resource = "#id")
    @Operation(summary = "Confirm the engine decline or overturn it (reviewer != applicant; reason mandatory)")
    fun decide(
        @PathParam("id") id: String,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: DeclineReviewDecisionBody?,
    ): Uni<Response> {
        // #8351 money-path POST: the key is REQUIRED (libs-runtime maps the IllegalArgumentException to
        // 400). The decision itself is replay-safe: a conditional UPDATE claims the REQUESTED row, so a
        // retry answers 409 REVIEW_ALREADY_DECIDED instead of deciding twice.
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val reviewId = runCatching { UUID.fromString(id) }.getOrNull()
            ?: return Uni.createFrom().item(
                Response.status(HTTP_NOT_FOUND).entity(mapOf("error" to "NOT_FOUND")).build(),
            )
        requireNotNull(request) { "request body is required" }
        val outcome = runCatching { DeclineReviewDecision.valueOf(request.outcome.orEmpty()) }.getOrNull()
        requireNotNull(outcome) { "outcome must be CONFIRMED or OVERTURNED" }
        val decision = DeclineReviewDecisionRequest(outcome, request.reason.orEmpty())
        return reviews.decide(reviewId, decision, identity.principal?.name.orEmpty())
            .map { Response.ok(it.toStaffDto()).build() }
            .onFailure(DeclineReviewRefusedException::class.java).recoverWithItem { e ->
                val refused = e as DeclineReviewRefusedException
                val status = if (refused.code ==
                    DeclineReviewRefusedException.NOT_FOUND
                ) {
                    HTTP_NOT_FOUND
                } else {
                    HTTP_CONFLICT
                }
                Response.status(status).entity(mapOf("error" to refused.code, "message" to refused.message)).build()
            }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
    }
}

/** Nullable on purpose: an absent field is a 400 from the guard above, not an NPE-shaped 500. */
data class DeclineReviewDecisionBody(val outcome: String?, val reason: String?)

/** Staff view: the engine snapshot the reviewer must see, and the review's own state. */
data class StaffDeclineReviewDto(
    val id: String,
    val applicationId: String,
    val partyId: String,
    val status: String,
    val requestedAt: String,
    val engineOutcome: String,
    val engineReasons: String?,
    val policyVersions: String?,
    val engineDecidedAt: String,
    val reviewedBy: String?,
    val reviewedAt: String?,
    val reviewReason: String?,
    val successorApplicationId: String?,
)

private fun DeclineReview.toStaffDto() = StaffDeclineReviewDto(
    id.toString(), applicationId.toString(), partyId.toString(), status.name, requestedAt.toInstant().toString(),
    engineOutcome, engineReasons, policyVersions, engineDecidedAt.toInstant().toString(), reviewedBy,
    reviewedAt?.toInstant()?.toString(), reviewReason, successorApplicationId?.toString(),
)
