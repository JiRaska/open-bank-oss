// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.sanctions.application.port.`in`.SanctionsUseCase
import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.domain.model.SanctionsCheck
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

@Path("/api/v3/sanctions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SanctionsResourceV3(private val useCase: SanctionsUseCase) {

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var approvals: ApprovalStore

    @field:ConfigProperty(name = "authz.four-eyes.enforce", defaultValue = "false")
    var fourEyesEnforce: Boolean = false

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.list", resource = "")
    suspend fun listAll() = useCase.listChecks()

    @POST
    @Path("/screen")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.create", resource = "")
    suspend fun screen(cmd: ScreenEntityCommand) =
        Response.status(Response.Status.CREATED).entity(useCase.screen(cmd)).build()

    @POST
    @Path("/review")
    // ROLE_API admits the agent identity (service-account-openbank-agent) to the OPA decision; OPA
    // grants it sanctions.clear as four-eyes maker only, and four-eyes pauses it for a human checker.
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.clear", resource = "#cmd.checkId")
    suspend fun review(
        cmd: ReviewRequest,
        @HeaderParam("X-Approval-Id") approvalId: String?,
    ): SanctionsCheck {
        // Defence in depth: AgentReviewGuard already refused this while reading the body, before
        // @Authorize created any approval. This repeats the decision at the point of effect.
        val agent = ServiceAccountIdentity.verifiedClientId(identity) == AgentReviewGuard.AGENT_CLIENT_ID
        if (agent) {
            AgentReviewGuard.refusal(cmd.newStatus.name, fourEyesEnforce)?.let { throw ForbiddenException(it) }
            // The decision of record belongs to the human who approved it, not the maker identity:
            // record the checker as reviewer and keep the agent's authorship in the note.
            // Bind the approval here too, so a looser interceptor or policy cannot let a decided
            // approval for another check, action or maker authorise this CLEAR.
            val approval = approvalId?.let { approvals.find(it) }
            val checker =
                approval?.let { checkerOf(it, cmd.checkId.toString(), identity.principal.name) }
                    ?: throw ForbiddenException(
                        "an agent review executes only with a human checker's approval of this exact check",
                    )
            return useCase.review(cmd.toCommand(reviewedBy = checker, noteSuffix = AGENT_NOTE_SUFFIX))
        }
        return useCase.review(cmd.toCommand(identity))
    }

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID) = useCase.getById(id) ?: throw NotFoundException()

    @GET
    @Path("/hits")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.list", resource = "")
    suspend fun hits() = useCase.listHits()

    @GET
    @Path("/pending")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "sanctions.list", resource = "")
    suspend fun pending() = useCase.listPending()

    internal companion object {
        /** The human checker of [approval] if it authorises an agent CLEAR of [checkId] by [makerId]. */
        fun checkerOf(approval: PendingApproval, checkId: String, makerId: String): String? =
            approval.decidedBy?.takeIf {
                approval.action == "sanctions.clear" &&
                    approval.resourceId == checkId &&
                    approval.makerId == makerId &&
                    approval.status in setOf(ApprovalStatus.APPROVED, ApprovalStatus.EXECUTED) &&
                    it.isNotBlank() &&
                    it != makerId
            }

        const val AGENT_NOTE_SUFFIX = " [submitted by service-account-openbank-agent as four-eyes maker]"
    }
}
