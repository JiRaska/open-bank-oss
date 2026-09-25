// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.GraphGuaranteeRepository
import com.openbank.lending.application.port.out.LendingAssignedCandidatesResult
import com.openbank.lending.application.port.out.LendingGraphAccessDecision
import com.openbank.lending.application.port.out.LendingGraphAccessPort
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Candidate IDs come only from Context; matched loans are rechecked before evidence is disclosed. */
@Path("/api/v1/lending/graph/loans")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_ADMIN", "ROLE_CREDIT_RISK")
class LendingSharedGuarantorResource(
    private val guarantees: GraphGuaranteeRepository,
    private val access: LendingGraphAccessPort,
    private val identity: SecurityIdentity,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.lending.graph.source-read-enabled", defaultValue = "false")
    private val sourceReadEnabled: Boolean,
) {
    @GET
    @Path("/{loanId}/shared-guarantor-candidates")
    @Authorize(action = "lending.graph.read", resource = "#loanId")
    // Keep the authorization, candidate preselection and recheck in one visible sequence.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun sharedGuarantorCandidates(
        @PathParam("loanId") loanId: UUID,
        @HeaderParam("Authorization") bearer: String?,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @QueryParam("effectiveAt") effectiveAtText: String?,
        @QueryParam("knownAt") knownAtText: String?,
    ): Response {
        if (!sourceReadEnabled) return unavailable()
        require(caseId == loanId.toString()) { "caseId must identify the assigned Lending loan" }
        require(purpose == PURPOSE) { "LENDING_EXPOSURE_REVIEW is required" }
        require(!bearer.isNullOrBlank() && bearer.startsWith("Bearer ")) { "bearer is required" }
        if (identity.principal.name.startsWith("service-account-")) return forbidden()
        val now = clock.instant()
        val effectiveAt = parseTime(effectiveAtText, now)
        val knownAt = parseTime(knownAtText, now)
        require(effectiveAt <= now && knownAt <= now) { "future graph time is not supported" }

        return try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) {
                when (access.check(loanId, bearer)) {
                    LendingGraphAccessDecision.DENIED -> return@withTimeout forbidden()
                    LendingGraphAccessDecision.UNAVAILABLE -> return@withTimeout unavailable()
                    LendingGraphAccessDecision.ALLOWED -> Unit
                }
                val assigned = when (val result = access.assignedCandidates(loanId, bearer)) {
                    LendingAssignedCandidatesResult.Denied -> return@withTimeout forbidden()
                    LendingAssignedCandidatesResult.Unavailable -> return@withTimeout unavailable()
                    is LendingAssignedCandidatesResult.Available -> result
                }
                val rootFacts = guarantees.findApprovedForLoan(loanId, effectiveAt, knownAt, ROOT_FACT_LIMIT)
                if (rootFacts.size > ROOT_FACT_LIMIT) return@withTimeout unavailable()
                val guarantors = rootFacts.map { it.proposal.guarantorPartyId }.toSet()
                val matches = if (guarantors.isEmpty() || assigned.ids.isEmpty()) {
                    emptyList()
                } else {
                    guarantees.findSharedCandidateLoanIds(
                        assigned.ids,
                        guarantors,
                        effectiveAt,
                        knownAt,
                        RELATED_LOAN_LIMIT + 1,
                    )
                }
                // Preselection remains private. Recheck each match with the same human bearer before its facts are read.
                for (candidate in matches) {
                    when (access.check(candidate, bearer)) {
                        LendingGraphAccessDecision.DENIED -> return@withTimeout forbidden()
                        LendingGraphAccessDecision.UNAVAILABLE -> return@withTimeout unavailable()
                        LendingGraphAccessDecision.ALLOWED -> Unit
                    }
                }
                val related = matches.take(RELATED_LOAN_LIMIT).map { candidate ->
                    val facts = guarantees.findApprovedForLoanAndGuarantors(
                        candidate,
                        guarantors,
                        effectiveAt,
                        knownAt,
                        FACT_LIMIT,
                    )
                    SharedGuarantorRelatedLoan(
                        candidate,
                        facts.take(FACT_LIMIT).map(GraphGuaranteeEvidence::from),
                        facts.size > FACT_LIMIT,
                    )
                }.filter { it.guarantees.isNotEmpty() }
                Response.ok(
                    SharedGuarantorCandidateResponse(
                        loanId,
                        effectiveAt,
                        knownAt,
                        assigned.truncated,
                        assigned.truncated || matches.size > RELATED_LOAN_LIMIT,
                        related,
                    ),
                ).header("Cache-Control", "no-store").build()
            }
        } catch (_: TimeoutCancellationException) {
            unavailable()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun parseTime(value: String?, default: Instant): Instant = if (value == null) {
        default
    } else {
        requireNotNull(runCatching { Instant.parse(value) }.getOrNull()) {
            "invalid graph time"
        }
    }

    private fun forbidden(): Response = Response.status(Response.Status.FORBIDDEN)
        .header("Cache-Control", "no-store").build()

    private fun unavailable(): Response = Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .header("Cache-Control", "no-store").build()

    private companion object {
        const val PURPOSE = "LENDING_EXPOSURE_REVIEW"
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val ROOT_FACT_LIMIT = 100
        const val FACT_LIMIT = 20
        const val RELATED_LOAN_LIMIT = 4
    }
}

data class SharedGuarantorCandidateResponse(
    val rootLoanId: UUID,
    val effectiveAt: Instant,
    val knownAt: Instant,
    val candidateTruncated: Boolean,
    val relatedLoansTruncated: Boolean,
    val relatedLoans: List<SharedGuarantorRelatedLoan>,
)

data class SharedGuarantorRelatedLoan(
    val loanId: UUID,
    val guarantees: List<GraphGuaranteeEvidence>,
    val truncated: Boolean,
)
