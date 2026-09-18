// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.ContextAccessDenied
import com.openbank.context.application.ContextAuthorizationUnavailable
import com.openbank.context.application.ContextQueryService
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

/** Live, case-scoped authorization check for the owning Fraud service. No case data is returned. */
@Path("/api/v1/context/fraud-cases")
@RolesAllowed("ROLE_ADMIN")
class FraudCaseAccessResource(
    private val queries: ContextQueryService,
    private val references: FraudCaseReferenceRepository,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @GET
    @Path("/{caseId}/assigned-candidates")
    suspend fun assignedCandidates(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("X-Investigation-Case-Id") investigationCaseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
    ): Response {
        require(investigationCaseId == caseId.toString()) { "caseId must identify the assigned Fraud case" }
        require(purpose == "FRAUD_INVESTIGATION") { "FRAUD_INVESTIGATION is required" }
        val actor = Investigator(identity.principal.name, identity.roles.sorted())
        val at = clock.instant()
        return try {
            queries.fraudCaseEvidence(
                caseId.toString(),
                actor,
                InvestigationContext(investigationCaseId, purpose, at),
            ) {
                Response.ok(references.assignedCandidates(caseId, actor.id, at))
                    .header("Cache-Control", "no-store").build()
            }
        } catch (_: ContextAccessDenied) {
            Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        } catch (_: ContextAuthorizationUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        } catch (_: FraudReferenceUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        }
    }

    @GET
    @Path("/{caseId}/access")
    suspend fun access(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("X-Investigation-Case-Id") investigationCaseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
    ): Response {
        require(investigationCaseId == caseId.toString()) { "caseId must identify the assigned Fraud case" }
        require(purpose == "FRAUD_INVESTIGATION") { "FRAUD_INVESTIGATION is required" }
        return try {
            queries.fraudCaseEvidence(
                caseId.toString(),
                Investigator(identity.principal.name, identity.roles.sorted()),
                InvestigationContext(investigationCaseId, purpose, clock.instant()),
            ) {
                Response.noContent().header("Cache-Control", "no-store").build()
            }
        } catch (_: ContextAccessDenied) {
            Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        } catch (_: ContextAuthorizationUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        }
    }
}
