// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.GraphGuaranteeRepository
import com.openbank.lending.application.port.out.LendingGraphAccessDecision
import com.openbank.lending.application.port.out.LendingGraphAccessPort
import com.openbank.lending.domain.model.GraphGuaranteeFact
import com.openbank.libs.authz.Authorize
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
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Source-owned detail; every read rechecks the investigator's live Context assignment. */
@Path("/api/v1/lending/graph/loans")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_ADMIN", "ROLE_CREDIT_RISK")
class LendingGraphSourceResource(
    private val guarantees: GraphGuaranteeRepository,
    private val access: LendingGraphAccessPort,
    private val identity: SecurityIdentity,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.lending.graph.source-read-enabled", defaultValue = "false")
    private val sourceReadEnabled: Boolean,
) {
    @GET
    @Path("/{loanId}/approved-guarantees")
    @Authorize(action = "lending.graph.read", resource = "#loanId")
    suspend fun approvedGuarantees(
        @PathParam("loanId") loanId: UUID,
        @HeaderParam("Authorization") bearer: String?,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @QueryParam("effectiveAt") effectiveAtText: String?,
        @QueryParam("knownAt") knownAtText: String?,
        @QueryParam("limit") limitParam: Int?,
    ): Response {
        if (!sourceReadEnabled) return unavailable()
        require(caseId == loanId.toString()) { "caseId must identify the assigned Lending loan" }
        require(purpose == "LENDING_EXPOSURE_REVIEW") { "LENDING_EXPOSURE_REVIEW is required" }
        require(!bearer.isNullOrBlank() && bearer.startsWith("Bearer ")) { "bearer is required" }
        if (identity.principal.name.startsWith("service-account-")) return forbidden()
        val limit = limitParam ?: DEFAULT_LIMIT
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        val now = clock.instant()
        val effectiveAt = parseTime(effectiveAtText, now)
        val knownAt = parseTime(knownAtText, now)
        require(effectiveAt <= now && knownAt <= now) { "future graph time is not supported" }

        return when (access.check(loanId, bearer)) {
            LendingGraphAccessDecision.DENIED -> forbidden()
            LendingGraphAccessDecision.UNAVAILABLE -> unavailable()
            LendingGraphAccessDecision.ALLOWED -> {
                val facts = guarantees.findApprovedForLoan(loanId, effectiveAt, knownAt, limit)
                Response.ok(
                    ApprovedGuaranteeHistory(
                        loanId,
                        effectiveAt,
                        knownAt,
                        facts.take(limit).map(GraphGuaranteeEvidence::from),
                        facts.size > limit,
                    ),
                ).header("Cache-Control", "no-store").build()
            }
        }
    }

    private fun parseTime(value: String?, default: Instant): Instant {
        if (value == null) return default
        return requireNotNull(runCatching { Instant.parse(value) }.getOrNull()) { "invalid graph time" }
    }

    private fun forbidden(): Response = Response.status(Response.Status.FORBIDDEN)
        .header("Cache-Control", "no-store").build()

    private fun unavailable(): Response = Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .header("Cache-Control", "no-store").build()

    private companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 100
    }
}

data class ApprovedGuaranteeHistory(
    val loanId: UUID,
    val effectiveAt: Instant,
    val knownAt: Instant,
    val guarantees: List<GraphGuaranteeEvidence>,
    val truncated: Boolean,
)

data class GraphGuaranteeEvidence(
    val guaranteeId: UUID,
    val contractId: UUID,
    val revision: Long,
    val supersedesGuaranteeId: UUID?,
    val guarantorPartyId: UUID,
    val capAmount: java.math.BigDecimal,
    val currency: String,
    val coverageFraction: java.math.BigDecimal,
    val seniority: Int,
    val validFrom: Instant,
    val validTo: Instant?,
    val sourceDocumentId: UUID,
    val sourceSha256: String,
    val decidedAt: Instant,
) {
    companion object {
        fun from(fact: GraphGuaranteeFact) = GraphGuaranteeEvidence(
            fact.guaranteeId,
            fact.proposal.contractId,
            fact.proposal.revision,
            fact.proposal.supersedesGuaranteeId,
            fact.proposal.guarantorPartyId,
            fact.proposal.capAmount,
            fact.proposal.currency,
            fact.proposal.coverageFraction,
            fact.proposal.seniority,
            fact.proposal.validFrom,
            fact.proposal.validTo,
            fact.proposal.sourceDocumentId,
            fact.proposal.sourceSha256,
            requireNotNull(fact.decidedAt) { "approved guarantee has no decision time" },
        )
    }
}
