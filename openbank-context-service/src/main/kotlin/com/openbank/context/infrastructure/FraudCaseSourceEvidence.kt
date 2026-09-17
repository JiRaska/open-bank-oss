// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore

/** Explicit source associations, never a fraud verdict or inferred identity. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FraudCaseSourceSnapshot(
    val caseId: UUID? = null,
    val scoreId: UUID? = null,
    val accountId: UUID? = null,
    val counterpartyId: UUID? = null,
    val status: String? = null,
    val revision: Long? = null,
    val openedAt: Instant? = null,
    val closedAt: Instant? = null,
)

@RegisterRestClient(configKey = "fraud-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/fraud/cases")
@Produces(MediaType.APPLICATION_JSON)
interface FraudCaseSourceClient {
    @GET
    @Path("/{caseId}/evidence")
    fun evidence(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("Authorization") bearer: String,
        @HeaderParam("X-Investigation-Purpose") purpose: String,
    ): Uni<FraudCaseSourceSnapshot>
}

/** Bounded, uncached source read. Source and Context both authorize the human caller. */
@ApplicationScoped
class FraudCaseSourceEvidence(@param:RestClient private val client: FraudCaseSourceClient) {
    private val inFlight = Semaphore(MAX_INFLIGHT)

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun read(caseId: UUID, bearer: String): FraudCaseSourceSnapshot {
        if (!inFlight.tryAcquire()) throw FraudCaseSourceUnavailable()
        try {
            val snapshot = try {
                client.evidence(caseId, bearer, "FRAUD_INVESTIGATION")
                    .ifNoItem().after(Duration.ofMillis(TIMEOUT_MS)).fail().awaitSuspending()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: WebApplicationException) {
                if (exception.response.status in DENIED_STATUSES) throw FraudCaseSourceDenied()
                throw FraudCaseSourceUnavailable(exception)
            } catch (exception: Exception) {
                throw FraudCaseSourceUnavailable(exception)
            }
            val rightCaseAndState = snapshot?.caseId == caseId && snapshot.status == "OPEN"
            val complete = snapshot?.scoreId != null &&
                snapshot.accountId != null &&
                snapshot.revision != null &&
                snapshot.openedAt != null
            if (!rightCaseAndState || !complete || snapshot.revision < 1) {
                throw FraudCaseSourceUnavailable()
            }
            return snapshot
        } finally {
            inFlight.release()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 1500L
        const val MAX_INFLIGHT = 8
        val DENIED_STATUSES = setOf(401, 403, 404)
    }
}

class FraudCaseSourceDenied : RuntimeException()
class FraudCaseSourceUnavailable(cause: Throwable? = null) : RuntimeException("Fraud source unavailable", cause)
