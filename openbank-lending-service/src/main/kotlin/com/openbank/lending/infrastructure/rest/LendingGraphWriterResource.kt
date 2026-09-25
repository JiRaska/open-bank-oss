// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.GraphGuaranteeRepository
import com.openbank.lending.application.port.out.LendingGraphProofUnavailable
import com.openbank.lending.application.usecase.GraphGuaranteeRegistrationService
import com.openbank.lending.domain.model.GraphGuaranteeFact
import com.openbank.lending.domain.model.GraphGuaranteeProposal
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Disabled by default; loan-scoped maker/checker entry points for source-owned guarantee evidence. */
@Path("/api/v1/lending/graph/loans/{loanId}/guarantees")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class LendingGraphWriterResource(
    private val registration: GraphGuaranteeRegistrationService,
    private val guarantees: GraphGuaranteeRepository,
    private val identity: SecurityIdentity,
    @ConfigProperty(name = "openbank.lending.graph.writer-enabled") private val writerEnabled: Boolean,
) {
    @POST
    @RolesAllowed("ROLE_LENDING_OFFICER", "ROLE_ADMIN")
    @Authorize(action = "lending.graph.propose", resource = "#loanId")
    suspend fun propose(@PathParam("loanId") loanId: UUID, request: GuaranteeProposalRequest): Response {
        if (!writerEnabled) return unavailable()
        val actor = humanActor() ?: return forbidden()
        val fact = try {
            registration.propose(request.toProposal(loanId), actor)
        } catch (_: LendingGraphProofUnavailable) {
            return unavailable()
        }
        return Response.status(Response.Status.CREATED).entity(GuaranteeWriteResult.from(fact))
            .header("Cache-Control", "no-store").build()
    }

    @POST
    @Path("/{guaranteeId}/decision")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Authorize(action = "lending.graph.decide", resource = "#loanId")
    suspend fun decide(
        @PathParam("loanId") loanId: UUID,
        @PathParam("guaranteeId") guaranteeId: UUID,
        request: GuaranteeDecisionRequest,
    ): Response {
        if (!writerEnabled) return unavailable()
        val actor = humanActor() ?: return forbidden()
        val decision = when (request.decision) {
            "APPROVED" -> GraphGuaranteeStatus.APPROVED
            "REJECTED" -> GraphGuaranteeStatus.REJECTED
            else -> throw IllegalArgumentException("decision must be APPROVED or REJECTED")
        }
        val existing = guarantees.find(guaranteeId)
        if (existing?.proposal?.loanId != loanId) {
            return Response.status(Response.Status.NOT_FOUND).header("Cache-Control", "no-store").build()
        }
        val fact = try {
            registration.decide(guaranteeId, decision, actor)
        } catch (_: LendingGraphProofUnavailable) {
            return unavailable()
        }
        return Response.ok(GuaranteeWriteResult.from(fact)).header("Cache-Control", "no-store").build()
    }

    private fun humanActor(): String? = identity.principal.name.takeUnless { it.startsWith("service-account-") }

    private fun forbidden(): Response = Response.status(Response.Status.FORBIDDEN)
        .header("Cache-Control", "no-store").build()

    private fun unavailable(): Response = Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .header("Cache-Control", "no-store").build()
}

data class GuaranteeProposalRequest(
    val contractId: UUID,
    val revision: Long,
    val supersedesGuaranteeId: UUID?,
    val guarantorPartyId: UUID,
    val capAmount: BigDecimal,
    val currency: String,
    val coverageFraction: BigDecimal,
    val seniority: Int,
    val validFrom: Instant,
    val validTo: Instant?,
    val sourceDocumentId: UUID,
    val sourceSha256: String,
) {
    fun toProposal(loanId: UUID) = GraphGuaranteeProposal(
        contractId, revision, supersedesGuaranteeId, loanId, guarantorPartyId,
        capAmount, currency, coverageFraction, seniority, validFrom, validTo,
        sourceDocumentId, sourceSha256,
    )
}

data class GuaranteeDecisionRequest(val decision: String?)

data class GuaranteeWriteResult(val guaranteeId: UUID, val revision: Long, val status: GraphGuaranteeStatus) {
    companion object {
        fun from(fact: GraphGuaranteeFact) = GuaranteeWriteResult(fact.guaranteeId, fact.proposal.revision, fact.status)
    }
}
