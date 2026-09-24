// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.ContextAccessDenied
import com.openbank.context.application.ContextAuthorizationUnavailable
import com.openbank.context.application.ContextDisclosure
import com.openbank.context.application.ContextQueryService
import com.openbank.context.application.ContextReadResult
import com.openbank.context.domain.InvestigationContext
import com.openbank.context.domain.Investigator
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
import java.time.format.DateTimeParseException
import java.util.UUID

@Path("/api/v1/context/authorizations")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_COMPLIANCE", "ROLE_ADMIN")
class AuthorityHistoryResource(
    private val queries: ContextQueryService,
    private val history: AuthorityHistoryRepository,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @GET
    @Path("/{id}")
    suspend fun history(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @QueryParam("effectiveAt") effectiveAt: String?,
        @QueryParam("knownAt") knownAt: String?,
    ): Response {
        val now = clock.instant()
        val effective = timestamp(effectiveAt, now)
        val known = knownAt?.let { timestamp(it, now) }
        require(effective <= now && (known == null || known <= now)) {
            "historical evidence cannot establish future authorization"
        }
        val case =
            requireNotNull(caseId?.takeIf { it.isNotBlank() && it.length <= MAX_CASE_LENGTH }) { "caseId is required" }
        require(purpose == "AUTHORIZATION_REVIEW") { "AUTHORIZATION_REVIEW is required" }
        return try {
            queries.authorizationEvidence(
                id.toString(),
                Investigator(identity.principal.name, identity.roles.sorted()),
                InvestigationContext(case, purpose, effective, known),
            ) {
                val selected = history.history(id, effective, known ?: history.databaseNow())
                ContextReadResult(
                    Response.ok(selected).header("Cache-Control", "no-store").build(),
                    ContextDisclosure(
                        selected.observations.map { it.evidenceRef },
                        selected.observations.size,
                        selected.truncated,
                    ),
                )
            }
        } catch (_: ContextAccessDenied) {
            Response.status(Response.Status.FORBIDDEN).build()
        } catch (_: ContextAuthorizationUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).build()
        }
    }

    private fun timestamp(value: String?, fallback: Instant): Instant = try {
        value?.let(Instant::parse) ?: fallback
    } catch (exception: DateTimeParseException) {
        throw IllegalArgumentException("timestamps must use RFC 3339", exception)
    }
    private companion object {
        const val MAX_CASE_LENGTH = 200
    }
}
