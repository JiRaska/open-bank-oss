// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.GraphGuaranteeIdempotencyConflict
import com.openbank.lending.application.port.out.GraphGuaranteeNotFound
import com.openbank.lending.application.port.out.GraphGuaranteeReceipt
import com.openbank.lending.application.port.out.LendingGraphProofUnavailable
import com.openbank.lending.application.usecase.GraphGuaranteeRegistrationService
import com.openbank.lending.domain.model.GraphGuaranteeProposal
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Disabled by default; loan-scoped maker/checker entry points for source-owned guarantee evidence. */
@Path("/api/v1/lending/graph/loans/{loanId}/guarantees")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class LendingGraphWriterResource(
    private val registration: GraphGuaranteeRegistrationService,
    private val identity: SecurityIdentity,
    @ConfigProperty(name = "openbank.lending.graph.writer-enabled") private val writerEnabled: Boolean,
) {
    @POST
    @RolesAllowed("ROLE_LENDING_OFFICER", "ROLE_ADMIN")
    @Authorize(action = "lending.graph.propose", resource = "#loanId")
    suspend fun propose(
        @PathParam("loanId") loanId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: GuaranteeProposalRequest?,
    ): Response {
        if (!writerEnabled) return unavailable()
        val actor = humanActor() ?: return forbidden()
        val validatedKey = requireKey(key)
        val proposal = requireNotNull(request) { "proposal is required" }.toProposal(loanId)
        proposal.validate()
        val fingerprint = fingerprint(
            "PROPOSE", actor, loanId.toString(), proposal.contractId.toString(), proposal.revision.toString(),
            proposal.supersedesGuaranteeId?.toString() ?: "", proposal.guarantorPartyId.toString(),
            proposal.capAmount.stripTrailingZeros().toPlainString(), proposal.currency,
            proposal.coverageFraction.stripTrailingZeros().toPlainString(), proposal.seniority.toString(),
            proposal.validFrom.toString(), proposal.validTo?.toString() ?: "",
            proposal.sourceDocumentId.toString(), proposal.sourceSha256,
        )
        val receipt = try {
            registration.proposeIdempotent(proposal, actor, validatedKey, fingerprint)
        } catch (_: LendingGraphProofUnavailable) {
            return unavailable()
        } catch (_: GraphGuaranteeIdempotencyConflict) {
            return conflict()
        }
        return Response.status(Response.Status.CREATED).entity(GuaranteeWriteResult.from(receipt))
            .header("Cache-Control", "no-store").build()
    }

    @POST
    @Path("/{guaranteeId}/decision")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Authorize(action = "lending.graph.decide", resource = "#loanId")
    suspend fun decide(
        @PathParam("loanId") loanId: UUID,
        @PathParam("guaranteeId") guaranteeId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: GuaranteeDecisionRequest?,
    ): Response {
        if (!writerEnabled) return unavailable()
        val actor = humanActor() ?: return forbidden()
        val validatedKey = requireKey(key)
        val body = requireNotNull(request) { "decision is required" }
        val decision = GraphGuaranteeDecision.entries.firstOrNull { it.name == body.decision }
            ?.let { GraphGuaranteeStatus.valueOf(it.name) }
            ?: throw IllegalArgumentException("decision must be APPROVED or REJECTED")
        val fingerprint = fingerprint("DECIDE", actor, loanId.toString(), guaranteeId.toString(), decision.name)
        val receipt = try {
            registration.decideIdempotent(loanId, guaranteeId, decision, actor, validatedKey, fingerprint)
        } catch (_: LendingGraphProofUnavailable) {
            return unavailable()
        } catch (_: GraphGuaranteeIdempotencyConflict) {
            return conflict()
        } catch (_: GraphGuaranteeNotFound) {
            return Response.status(Response.Status.NOT_FOUND).header("Cache-Control", "no-store").build()
        }
        return Response.ok(GuaranteeWriteResult.from(receipt)).header("Cache-Control", "no-store").build()
    }

    private fun humanActor(): String? = identity.principal.name.takeUnless { it.startsWith("service-account-") }

    private fun requireKey(key: String?): String {
        require(key != null && key.matches(Regex("[A-Za-z0-9._~-]{1,128}"))) {
            "Idempotency-Key must be 1 to 128 URL-safe ASCII characters"
        }
        return key
    }

    private fun fingerprint(vararg parts: String): String {
        val canonical = buildString {
            parts.forEach { part -> append(part.length).append(':').append(part) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun conflict(): Response = Response.status(Response.Status.CONFLICT)
        .header("Cache-Control", "no-store").build()

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

/** The API accepts a decision, while the stored guarantee has an additional PENDING lifecycle state. */
enum class GraphGuaranteeDecision { APPROVED, REJECTED }

data class GuaranteeWriteResult(val guaranteeId: UUID, val revision: Long, val status: GraphGuaranteeStatus) {
    companion object {
        fun from(receipt: GraphGuaranteeReceipt) =
            GuaranteeWriteResult(receipt.guaranteeId, receipt.revision, receipt.status)
    }
}
