// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.AgentChatService
import com.openbank.agent.application.OversightService
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.runBlocking

/**
 * Manual trigger for the compliance-officer oversight sweep (ADR-0031 D9 phase 2). ADR-0031 D3:
 * an authenticated operator only — a manual sweep runs the OPA-gated MCP tools (reads
 * sanctions/AML/disputes) and can file proposals, so it is as sensitive as the assistant surface
 * and must not be reachable by an anonymous in-cluster caller. The sweep itself stays fully
 * governed — charter tool filter, OPA gate, rate limits (counts against runs_per_day) and D5 run
 * audit (`trigger=manual`) all apply; this endpoint adds no privilege beyond a start button.
 */
@Path("/agent/oversight")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_ADMIN", "ROLE_COMPLIANCE")
class OversightResource {

    @Inject lateinit var oversight: OversightService

    data class SweepResponse(
        val reply: String,
        val model: String,
        val toolCalls: List<AgentChatService.ToolCallRecord>,
        val isProposal: Boolean,
    )

    // Plain (non-suspend) method for the same reason as ChatEndpoint.chat: the governed loop's
    // blocking clients need a worker thread, and runBlocking keeps CDI/audit context on it.
    @POST
    @Path("/run")
    fun run(): SweepResponse = runBlocking {
        val outcome = oversight.sweep(trigger = "manual")
        SweepResponse(
            reply = outcome.reply,
            model = outcome.model,
            toolCalls = outcome.toolCalls,
            isProposal = outcome.isProposal,
        )
    }
}
