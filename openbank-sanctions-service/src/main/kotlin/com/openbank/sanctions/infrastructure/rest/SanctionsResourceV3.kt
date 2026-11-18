// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.FourEyesConsumption
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.sanctions.application.port.`in`.SanctionsUseCase
import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.domain.model.SanctionsCheck
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.HttpHeaders
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

    // Read from the request context, NOT as a method parameter: @Authorize fingerprints every
    // business argument of the intercepted call, so an X-Approval-Id parameter would differ between
    // the first call (null) and the retry (the id), the approval would never match, and every retry
    // would mint a fresh PENDING approval instead of executing (#16455 review).
    @Inject
    lateinit var httpHeaders: Instance<HttpHeaders>

    // Which approval @Authorize consumed for THIS call. The approval's EXECUTED status alone would
    // also accept an id consumed by an earlier call, should the policy ever stop gating the action.
    @Inject
    lateinit var fourEyesConsumption: Instance<FourEyesConsumption>

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
    suspend fun review(cmd: ReviewRequest): SanctionsCheck {
        // Defence in depth: AgentReviewGuard already refused this while reading the body, before
        // @Authorize created any approval. This repeats the decision at the point of effect.
        val agent = ServiceAccountIdentity.verifiedClientId(identity) == AgentReviewGuard.AGENT_CLIENT_ID
        if (agent) {
            AgentReviewGuard.refusal(cmd.newStatus.name, fourEyesEnforce)?.let { throw ForbiddenException(it) }
            // The decision of record belongs to the human who approved it, not the maker identity:
            // record the checker as reviewer and keep the agent's authorship in the note.
            // Bind the approval here too, so a looser interceptor or policy cannot let a decided
            // approval for another check, action or maker authorise this CLEAR.
            val consumed = fourEyesConsumption.takeIf { it.isResolvable }?.get()?.consumedApprovalId
            val approval = approvalIdHeader()?.takeIf { it == consumed }?.let { approvals.find(it) }
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

    private fun approvalIdHeader(): String? =
        if (httpHeaders.isResolvable) httpHeaders.get().getRequestHeader(APPROVAL_ID_HEADER)?.firstOrNull() else null

    internal companion object {
        /** The human checker of [approval] if it authorises an agent CLEAR of [checkId] by [makerId]. */
        fun checkerOf(approval: PendingApproval, checkId: String, makerId: String): String? =
            approval.decidedBy?.takeIf {
                approval.action == "sanctions.clear" &&
                    approval.resourceId == checkId &&
                    approval.makerId == makerId &&
                    // The interceptor marks the approval EXECUTED before this body runs, so an APPROVED
                    // one here was not consumed by four-eyes and must not authorise anything.
                    approval.status == ApprovalStatus.EXECUTED &&
                    it.isNotBlank() &&
                    it != makerId
            }

        const val APPROVAL_ID_HEADER = "X-Approval-Id"
        const val AGENT_NOTE_SUFFIX = " [submitted by service-account-openbank-agent as four-eyes maker]"
    }
}
