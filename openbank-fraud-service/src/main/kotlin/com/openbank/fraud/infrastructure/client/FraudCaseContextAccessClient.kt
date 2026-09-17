// SPDX-License-Identifier: Apache-2.0
package com.openbank.fraud.infrastructure.client

import com.openbank.fraud.application.port.out.FraudCaseAccessDecision
import com.openbank.fraud.application.port.out.FraudCaseContextAccess
import com.openbank.libs.web.SyntheticTaintClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

private const val ACCESS_TIMEOUT_MS = 3000L
private const val NO_CONTENT = 204
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404

@Path("/api/v1/context/fraud-cases")
@RegisterRestClient(configKey = "context-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface FraudCaseContextAccessRestClient {
    @GET
    @Path("/{caseId}/access")
    suspend fun check(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("Authorization") bearer: String,
        @HeaderParam("X-Investigation-Case-Id") investigationCaseId: String,
        @HeaderParam("X-Investigation-Purpose") purpose: String,
    ): Response
}

/** Propagates the human investigator's bearer. A service identity cannot substitute for it. */
@ApplicationScoped
class FraudCaseContextAccessAdapter : FraudCaseContextAccess {
    @Inject
    @RestClient
    lateinit var client: FraudCaseContextAccessRestClient

    @Timeout(ACCESS_TIMEOUT_MS)
    override suspend fun check(caseId: UUID, bearer: String): FraudCaseAccessDecision = try {
        client.check(caseId, bearer, caseId.toString(), "FRAUD_INVESTIGATION").use { response ->
            when (response.status) {
                NO_CONTENT -> FraudCaseAccessDecision.ALLOWED
                UNAUTHORIZED, FORBIDDEN, NOT_FOUND -> FraudCaseAccessDecision.DENIED
                else -> FraudCaseAccessDecision.UNAVAILABLE
            }
        }
    } catch (exception: WebApplicationException) {
        when (exception.response?.status) {
            UNAUTHORIZED, FORBIDDEN, NOT_FOUND -> FraudCaseAccessDecision.DENIED
            else -> FraudCaseAccessDecision.UNAVAILABLE
        }
    } catch (_: Exception) {
        FraudCaseAccessDecision.UNAVAILABLE
    }
}
