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
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Clock
import java.util.UUID

/** Data-free, live assignment decision for one source loan. A shared asset grants no other loan. */
@Path("/api/v1/context/lending-loans")
@RolesAllowed("ROLE_ADMIN", "ROLE_CREDIT_RISK")
class LendingLoanAccessResource(
    private val queries: ContextQueryService,
    private val candidates: LendingAssignedCandidatesRepository,
    private val identity: SecurityIdentity,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.context.lending-source-read-enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    @GET
    @Path("/{loanId}/assigned-candidates")
    suspend fun assignedCandidates(
        @PathParam("loanId") loanId: UUID,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
    ): Response {
        if (!enabled) return unavailable()
        require(caseId == loanId.toString()) { "caseId must identify the assigned Lending loan" }
        require(purpose == "LENDING_EXPOSURE_REVIEW") { "LENDING_EXPOSURE_REVIEW is required" }
        if (identity.principal.name.startsWith("service-account-") ||
            (identity.principal as? JsonWebToken)?.rawToken == null
        ) {
            return forbidden()
        }
        val actor = Investigator(identity.principal.name, identity.roles.sorted())
        val at = clock.instant()
        return try {
            queries.lendingLoanAccess(
                loanId.toString(),
                actor,
                InvestigationContext(caseId, purpose, at),
            ) {
                val assigned = candidates.assignedCandidates(loanId, actor.id, at)
                ContextReadResult(
                    Response.ok(assigned)
                        .header("Cache-Control", "no-store").build(),
                    ContextDisclosure(
                        assigned.ids.map { "lending-loan:$it" },
                        assigned.ids.size,
                        assigned.truncated,
                    ),
                )
            }
        } catch (_: ContextAccessDenied) {
            forbidden()
        } catch (_: ContextAuthorizationUnavailable) {
            unavailable()
        } catch (_: LendingCandidatesUnavailable) {
            unavailable()
        }
    }

    @GET
    @Path("/{loanId}/access")
    suspend fun access(
        @PathParam("loanId") loanId: UUID,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
    ): Response {
        require(caseId == loanId.toString()) { "caseId must identify the assigned Lending loan" }
        require(purpose == "LENDING_EXPOSURE_REVIEW") { "LENDING_EXPOSURE_REVIEW is required" }
        if (identity.principal.name.startsWith("service-account-")) {
            return Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        }
        return try {
            queries.lendingLoanAccess(
                loanId.toString(),
                Investigator(identity.principal.name, identity.roles.sorted()),
                InvestigationContext(caseId, purpose, clock.instant()),
            ) {
                ContextReadResult(Response.noContent().header("Cache-Control", "no-store").build(), null)
            }
        } catch (_: ContextAccessDenied) {
            Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        } catch (_: ContextAuthorizationUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        }
    }

    private fun forbidden() = Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
    private fun unavailable() = Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .header("Cache-Control", "no-store").build()
}
