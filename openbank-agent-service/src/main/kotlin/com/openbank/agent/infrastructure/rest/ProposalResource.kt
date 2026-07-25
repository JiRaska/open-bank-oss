// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.port.`in`.DecideProposalUseCase
import com.openbank.agent.application.port.`in`.ProposalQueries
import com.openbank.agent.domain.proposal.AgentProposal
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

/**
 * The HITL approval-queue API (ADR-0031 D4). The admin-ui BFF lists pending proposals and records
 * a human decision. ADR-0031 D3: requires an authenticated operator (Keycloak bearer) — the
 * segregation-of-duties / double-decision invariants are still enforced in ProposalService.
 * @Blocking because the store is imperative JDBC (worker thread), unlike the reactive read tools.
 */
@Path("/api/v1/proposals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
class ProposalResource {

    @Inject lateinit var queries: ProposalQueries

    @Inject lateinit var decisions: DecideProposalUseCase

    data class ProposalDto(
        val id: String,
        val title: String,
        val rationale: String,
        val suggestedAction: String,
        val proposedBy: String,
        val proposedAt: Instant,
        val state: String,
        val decidedBy: String?,
        val decidedAt: Instant?,
        val decisionReason: String?,
        val modelId: String?,
    )

    data class DecisionRequest(val approve: Boolean, val decidedBy: String, val reason: String? = null)

    private fun AgentProposal.toDto() = ProposalDto(
        id = id.toString(), title = title, rationale = rationale, suggestedAction = suggestedAction,
        proposedBy = proposedBy, proposedAt = proposedAt, state = state.name, decidedBy = decidedBy,
        decidedAt = decidedAt, decisionReason = decisionReason, modelId = modelId,
    )

    @GET
    @Blocking
    fun list(
        @QueryParam("state") @DefaultValue("pending") state: String,
        @QueryParam("agentId") agentId: String?,
    ): List<ProposalDto> {
        val filter = agentId?.trim()?.takeIf { it.isNotEmpty() }
        val rows = if (state.equals("all", ignoreCase = true)) {
            queries.listAll(MAX_LIST, filter)
        } else {
            queries.listPending(filter)
        }
        return rows.map { it.toDto() }
    }

    @POST
    @Path("/{id}/decision")
    @Blocking
    fun decide(@PathParam("id") id: String, body: DecisionRequest): Response {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: return Response.status(Response.Status.BAD_REQUEST).entity(mapOf("error" to "invalid id")).build()
        return try {
            val updated = decisions.decide(uuid, body.approve, body.decidedBy, body.reason)
                ?: return Response.status(
                    Response.Status.NOT_FOUND,
                ).entity(mapOf("error" to "proposal not found")).build()
            Response.ok(updated.toDto()).build()
        } catch (e: IllegalArgumentException) {
            Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
        }
    }

    private companion object {
        /** Page cap for `?state=all`; the repository clamps anything larger. */
        const val MAX_LIST = 100
    }
}
