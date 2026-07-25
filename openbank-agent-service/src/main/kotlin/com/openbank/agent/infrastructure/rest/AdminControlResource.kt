// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.port.`in`.KillSwitchControlUseCase
import com.openbank.agent.application.port.`in`.KillSwitchQueries
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant

/**
 * Runtime break-glass kill switch (ADR-0031 D7). An operator halts/resumes an agent — or every
 * agent (scope `*`) — without a redeploy or GitOps round-trip. ADR-0031 D3: restricted to
 * ROLE_ADMIN (the most sensitive control — resume() undoes a deliberate safety halt), and the
 * `setBy` actor is the authenticated OIDC subject, NOT a caller-supplied body field — so the audit
 * trail cannot be spoofed. @Blocking — imperative JDBC store.
 */
@Path("/api/v1/admin/agents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_ADMIN")
class AdminControlResource {

    @Inject lateinit var control: KillSwitchControlUseCase

    @Inject lateinit var queries: KillSwitchQueries

    @Inject lateinit var identity: SecurityIdentity

    /** The authenticated operator who flipped the switch — never caller-supplied. */
    private fun actor(): String = identity.principal?.name?.takeIf { it.isNotBlank() } ?: "unknown"

    data class HaltRequest(val scope: String, val reason: String)

    data class ResumeRequest(val scope: String)

    data class HaltDto(val scope: String, val reason: String, val setBy: String, val setAt: Instant)

    @POST
    @Path("/halt")
    @Blocking
    fun halt(body: HaltRequest): Response {
        if (body.scope.isBlank() || body.reason.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "scope and reason are required")).build()
        }
        control.halt(body.scope.trim(), body.reason.trim(), actor())
        return Response.ok(mapOf("scope" to body.scope.trim(), "halted" to true)).build()
    }

    @POST
    @Path("/resume")
    @Blocking
    fun resume(body: ResumeRequest): Response {
        if (body.scope.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "scope is required")).build()
        }
        control.resume(body.scope.trim(), actor())
        return Response.ok(mapOf("scope" to body.scope.trim(), "halted" to false)).build()
    }

    @GET
    @Path("/halts")
    @Blocking
    fun halts(): List<HaltDto> = queries.listHalts().map { HaltDto(it.scope, it.reason, it.setBy, it.setAt) }
}
