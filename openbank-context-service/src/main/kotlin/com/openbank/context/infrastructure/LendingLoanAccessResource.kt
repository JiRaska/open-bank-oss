// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.ContextAccessDenied
import com.openbank.context.application.ContextAuthorizationUnavailable
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
import java.time.Clock
import java.util.UUID

/** Data-free, live assignment decision for one source loan. A shared asset grants no other loan. */
@Path("/api/v1/context/lending-loans")
@RolesAllowed("ROLE_ADMIN", "ROLE_CREDIT_RISK")
class LendingLoanAccessResource(
    private val queries: ContextQueryService,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
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
}
