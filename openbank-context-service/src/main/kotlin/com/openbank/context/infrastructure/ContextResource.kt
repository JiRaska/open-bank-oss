// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.ContextAccessDenied
import com.openbank.context.application.ContextAuthorizationUnavailable
import com.openbank.context.application.ContextQueryService
import com.openbank.context.domain.InvestigationContext
import com.openbank.context.domain.Investigator
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Clock
import java.time.Instant

@Path("/api/v1/context")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.COMPLIANCE, Roles.OPERATOR, Roles.ADMIN)
class ContextResource(
    private val queries: ContextQueryService,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @GET
    @Path("/complaints/{reference}")
    suspend fun complaint(
        @PathParam("reference") reference: String,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @QueryParam("asOf") asOf: String?,
    ): Response = respond {
        require(reference.length <= MAX_REFERENCE_LENGTH) { "reference is too long" }
        queries.complaint(reference, actor(), context(caseId, purpose, asOf))
            ?.let { Response.ok(it).build() } ?: Response.status(Response.Status.NOT_FOUND).build()
    }

    @GET
    @Path("/incidents/{reference}/impact")
    suspend fun incident(
        @PathParam("reference") reference: String,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @QueryParam("asOf") asOf: String?,
    ): Response = respond {
        require(reference.length <= MAX_REFERENCE_LENGTH) { "reference is too long" }
        Response.ok(queries.incident(reference, actor(), context(caseId, purpose, asOf))).build()
    }

    private fun actor() = Investigator(identity.principal.name, identity.roles.sorted())
    private fun context(caseId: String?, purpose: String?, asOf: String?) = InvestigationContext(
        requireNotNull(caseId?.takeIf { it.isNotBlank() && it.length <= MAX_CASE_LENGTH }) {
            "X-Investigation-Case-Id is required and must not exceed $MAX_CASE_LENGTH characters"
        },
        requireNotNull(purpose?.takeIf { it.isNotBlank() && it.length <= MAX_PURPOSE_LENGTH }) {
            "X-Investigation-Purpose is required and must not exceed $MAX_PURPOSE_LENGTH characters"
        },
        asOf?.let(Instant::parse) ?: clock.instant(),
    )
    private suspend fun respond(block: suspend () -> Response): Response = try {
        block()
    } catch (_: ContextAccessDenied) {
        Response.status(Response.Status.FORBIDDEN).build()
    } catch (_: ContextAuthorizationUnavailable) {
        Response.status(Response.Status.SERVICE_UNAVAILABLE).build()
    }

    private companion object {
        const val MAX_REFERENCE_LENGTH = 200
        const val MAX_CASE_LENGTH = 200
        const val MAX_PURPOSE_LENGTH = 80
    }
}
