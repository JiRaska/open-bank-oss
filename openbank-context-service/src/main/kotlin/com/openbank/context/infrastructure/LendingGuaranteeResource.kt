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
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Clock
import java.util.UUID

/** Source-backed evidence for one assigned loan; no shared-party expansion. */
@Path("/api/v1/context/lending-loans")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_ADMIN", "ROLE_CREDIT_RISK")
class LendingGuaranteeResource(
    private val queries: ContextQueryService,
    private val source: LendingGuaranteeSourceEvidence,
    private val identity: SecurityIdentity,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.context.lending-source-read-enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    @GET
    @Path("/{loanId}/approved-guarantees")
    suspend fun approvedGuarantees(
        @PathParam("loanId") loanId: UUID,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
    ): Response {
        if (!enabled) return unavailable()
        require(caseId == loanId.toString()) { "caseId must identify the assigned Lending loan" }
        require(purpose == PURPOSE) { "LENDING_EXPOSURE_REVIEW is required" }
        if (identity.principal.name.startsWith("service-account-")) return forbidden()
        val bearer = (identity.principal as? JsonWebToken)?.rawToken?.let { "Bearer $it" } ?: return forbidden()
        return try {
            queries.lendingLoanAccess(
                loanId.toString(),
                Investigator(identity.principal.name, identity.roles.sorted()),
                InvestigationContext(caseId, purpose, clock.instant()),
            ) {
                val history = source.read(loanId, bearer)
                val facts = requireNotNull(history.guarantees)
                ContextReadResult(
                    Response.ok(history).header("Cache-Control", "no-store").build(),
                    ContextDisclosure(
                        facts.map { "lending-guarantee:${it.guaranteeId}:document:${it.sourceDocumentId}" },
                        facts.size,
                        requireNotNull(history.truncated),
                    ),
                )
            }
        } catch (_: ContextAccessDenied) {
            forbidden()
        } catch (_: LendingGuaranteeSourceDenied) {
            forbidden()
        } catch (_: ContextAuthorizationUnavailable) {
            unavailable()
        } catch (_: LendingGuaranteeSourceUnavailable) {
            unavailable()
        }
    }

    private fun forbidden() = Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
    private fun unavailable() = Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .header("Cache-Control", "no-store").build()

    private companion object {
        const val PURPOSE = "LENDING_EXPOSURE_REVIEW"
    }
}
