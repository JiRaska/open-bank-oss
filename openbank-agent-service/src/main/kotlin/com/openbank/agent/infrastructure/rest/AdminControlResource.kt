// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.KillSwitchService
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

    @Inject lateinit var killSwitch: KillSwitchService

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
        killSwitch.halt(body.scope.trim(), body.reason.trim(), actor())
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
        killSwitch.resume(body.scope.trim(), actor())
        return Response.ok(mapOf("scope" to body.scope.trim(), "halted" to false)).build()
    }

    @GET
    @Path("/halts")
    @Blocking
    fun halts(): List<HaltDto> = killSwitch.listHalts().map { HaltDto(it.scope, it.reason, it.setBy, it.setAt) }
}
