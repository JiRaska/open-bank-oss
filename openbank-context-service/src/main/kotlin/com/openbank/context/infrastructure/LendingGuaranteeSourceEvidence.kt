// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Semaphore

data class LendingGuaranteeEvidence(
    val guaranteeId: UUID?,
    val contractId: UUID?,
    val revision: Long?,
    val supersedesGuaranteeId: UUID?,
    val guarantorPartyId: UUID?,
    val capAmount: BigDecimal?,
    val currency: String?,
    val coverageFraction: BigDecimal?,
    val seniority: Int?,
    val validFrom: Instant?,
    val validTo: Instant?,
    val sourceDocumentId: UUID?,
    val sourceSha256: String?,
    val decidedAt: Instant?,
)

data class LendingGuaranteeHistory(
    val loanId: UUID?,
    val effectiveAt: Instant?,
    val knownAt: Instant?,
    val guarantees: List<LendingGuaranteeEvidence>?,
    val truncated: Boolean?,
)

data class LendingSharedGuarantorHistory(
    val rootLoanId: UUID?,
    val effectiveAt: Instant?,
    val knownAt: Instant?,
    val candidateTruncated: Boolean?,
    val relatedLoansTruncated: Boolean?,
    val relatedLoans: List<LendingRelatedLoanEvidence>?,
)

data class LendingRelatedLoanEvidence(
    val loanId: UUID?,
    val guarantees: List<LendingGuaranteeEvidence>?,
    val truncated: Boolean?,
)

@RegisterRestClient(configKey = "lending-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/lending/graph/loans")
@Produces(MediaType.APPLICATION_JSON)
interface LendingGuaranteeSourceClient {
    @GET
    @Path("/{loanId}/approved-guarantees")
    fun approvedGuarantees(
        @PathParam("loanId") loanId: UUID,
        @HeaderParam("Authorization") bearer: String,
        @HeaderParam("X-Investigation-Case-Id") caseId: String,
        @HeaderParam("X-Investigation-Purpose") purpose: String,
        @QueryParam("limit") limit: Int,
    ): Uni<LendingGuaranteeHistory>

    @GET
    @Path("/{loanId}/shared-guarantor-candidates")
    fun sharedGuarantorCandidates(
        @PathParam("loanId") loanId: UUID,
        @HeaderParam("Authorization") bearer: String,
        @HeaderParam("X-Investigation-Case-Id") caseId: String,
        @HeaderParam("X-Investigation-Purpose") purpose: String,
    ): Uni<LendingSharedGuarantorHistory>
}

@ApplicationScoped
class LendingGuaranteeSourceEvidence(
    @param:RestClient private val client: LendingGuaranteeSourceClient,
    // Check the effective REST-client destination, including higher-priority config overrides.
    @ConfigProperty(name = "quarkus.rest-client.lending-service.url") private val sourceUrl: Optional<String>,
) {
    private val inFlight = Semaphore(MAX_INFLIGHT)

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun read(loanId: UUID, bearer: String): LendingGuaranteeHistory {
        if (!isTrustedLendingSourceUrl(sourceUrl.orElse(null))) throw LendingGuaranteeSourceUnavailable()
        if (!inFlight.tryAcquire()) throw LendingGuaranteeSourceUnavailable()
        try {
            val history = try {
                client.approvedGuarantees(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW", MAX_RECORDS)
                    .ifNoItem().after(Duration.ofSeconds(TIMEOUT_SECONDS)).fail().awaitSuspending()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: WebApplicationException) {
                if (exception.response.status in DENIED_STATUSES) throw LendingGuaranteeSourceDenied()
                throw LendingGuaranteeSourceUnavailable(exception)
            } catch (exception: Exception) {
                throw LendingGuaranteeSourceUnavailable(exception)
            }
            if (!validHistory(history, loanId)) throw LendingGuaranteeSourceUnavailable()
            return history
        } finally {
            inFlight.release()
        }
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun readShared(loanId: UUID, bearer: String): LendingSharedGuarantorHistory {
        if (!isTrustedLendingSourceUrl(sourceUrl.orElse(null))) throw LendingGuaranteeSourceUnavailable()
        if (!inFlight.tryAcquire()) throw LendingGuaranteeSourceUnavailable()
        try {
            val history = try {
                client.sharedGuarantorCandidates(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW")
                    .ifNoItem().after(Duration.ofSeconds(TIMEOUT_SECONDS)).fail().awaitSuspending()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: WebApplicationException) {
                if (exception.response.status in DENIED_STATUSES) throw LendingGuaranteeSourceDenied()
                throw LendingGuaranteeSourceUnavailable(exception)
            } catch (exception: Exception) {
                throw LendingGuaranteeSourceUnavailable(exception)
            }
            if (!validSharedHistory(history, loanId)) throw LendingGuaranteeSourceUnavailable()
            return history
        } finally {
            inFlight.release()
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun validSharedHistory(history: LendingSharedGuarantorHistory?, root: UUID): Boolean {
        val related = history?.relatedLoans ?: return false
        val ids = related.map { it.loanId }
        return history.rootLoanId == root &&
            history.effectiveAt != null &&
            history.knownAt != null &&
            history.candidateTruncated != null &&
            history.relatedLoansTruncated != null &&
            related.size <= MAX_RELATED_LOANS &&
            ids.none { it == null || it == root } &&
            ids.distinct().size == ids.size &&
            related.all { loan ->
                val facts = loan.guarantees ?: return@all false
                loan.truncated != null &&
                    facts.isNotEmpty() &&
                    facts.size <= MAX_RELATED_FACTS &&
                    facts.map { it.guaranteeId }.distinct().size == facts.size &&
                    facts.all(::validFact)
            }
    }

    private fun validHistory(history: LendingGuaranteeHistory?, loanId: UUID): Boolean {
        val facts = history?.guarantees ?: return false
        return history.loanId == loanId &&
            history.effectiveAt != null &&
            history.knownAt != null &&
            history.truncated != null &&
            facts.size <= MAX_RECORDS &&
            facts.map { it.guaranteeId }.distinct().size == facts.size &&
            facts.all(::validFact)
    }

    private fun validFact(fact: LendingGuaranteeEvidence): Boolean = fact.guaranteeId != null &&
        fact.contractId != null &&
        fact.revision != null &&
        fact.revision > 0 &&
        fact.guarantorPartyId != null &&
        fact.capAmount != null &&
        fact.currency?.isNotBlank() == true &&
        fact.coverageFraction != null &&
        fact.seniority != null &&
        fact.validFrom != null &&
        fact.sourceDocumentId != null &&
        fact.sourceSha256?.matches(SHA256_PATTERN) == true &&
        fact.decidedAt != null

    private companion object {
        const val MAX_RECORDS = 100
        const val MAX_RELATED_LOANS = 4
        const val MAX_RELATED_FACTS = 20
        const val MAX_INFLIGHT = 8
        const val TIMEOUT_SECONDS = 4L
        val DENIED_STATUSES = setOf(401, 403, 404)
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}

private const val LENDING_MTLS_PORT = 8443

internal fun isTrustedLendingSourceUrl(value: String?): Boolean {
    if (value == null) return false
    return try {
        val uri = URI(value)
        uri.scheme == "https" &&
            uri.host != null &&
            uri.port == LENDING_MTLS_PORT &&
            uri.userInfo == null &&
            uri.rawPath.isNullOrEmpty() &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    } catch (_: IllegalArgumentException) {
        false
    }
}

class LendingGuaranteeSourceDenied : RuntimeException()
class LendingGuaranteeSourceUnavailable(cause: Throwable? = null) : RuntimeException("source unavailable", cause)
