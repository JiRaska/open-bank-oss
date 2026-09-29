// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.account.application.port.out.ScaChallengeClient
import com.openbank.account.application.port.out.ScaChallengeSnapshot
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

// sca-service's ScaChallengeResponse carries method/expiresAt/completedAt/consumedAt/attempt
// counters too; this client only needs four fields and must not break when that DTO grows.
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScaChallengeClientResponse(val id: UUID, val partyId: UUID, val purpose: String, val status: String)

/** Mirrors sca-service's ConsumeScaRequest. Only the party is stated: a savings-withdrawal
 *  approval carries no dynamic-linking data, and sca-service authorises an unlinked challenge
 *  exactly when the consume states no operation. */
data class ConsumeScaChallengeRequest(val partyId: UUID)

/**
 * sca-service's `GET /api/v1/sca/challenges/{id}` and `POST /{id}/consume` are
 * `@RolesAllowed("ROLE_API","ROLE_OPERATOR","ROLE_ADMIN")` + `@Authorize`, so the call must carry an
 * M2M bearer. This client registered only the taint filter, so it sent none — the same shape as the
 * three sibling clients in this package, which all register
 * [OidcClientRequestReactiveFilter] (oidc-client `openbank-services`). It had never been noticed
 * because `SCA_SERVICE_URL` was unset in gitops and the request never left the pod (#10383): a
 * connection refused on localhost:8110 arrives long before any 401 could.
 */
@Path("/api/v1/sca/challenges")
@RegisterRestClient(configKey = "sca-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
interface ScaServiceRestClient {
    @GET
    @Path("/{id}")
    suspend fun getChallenge(@PathParam("id") id: UUID): ScaChallengeClientResponse

    @POST
    @Path("/{id}/consume")
    suspend fun consumeChallenge(
        @PathParam("id") id: UUID,
        request: ConsumeScaChallengeRequest,
    ): ScaChallengeClientResponse
}

@ApplicationScoped
class ResilientScaChallengeClient @Inject constructor(@RestClient private val client: ScaServiceRestClient) :
    ScaChallengeClient {

    @Timeout(2000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100, retryOn = [Exception::class])
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    override suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot =
        client.getChallenge(challengeId).toSnapshot()

    // No @Retry: consume is a state change, not a query. sca-service answers 409
    // (ScaChallengeAlreadyConsumedException) on a second attempt, so a retried consume that
    // actually succeeded the first time would surface as a spurious approval failure.
    @Timeout(2000)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    override suspend fun consumeChallenge(challengeId: UUID, expectedPartyId: UUID): ScaChallengeSnapshot =
        client.consumeChallenge(challengeId, ConsumeScaChallengeRequest(expectedPartyId)).toSnapshot()

    private fun ScaChallengeClientResponse.toSnapshot() = ScaChallengeSnapshot(
        id = id,
        partyId = partyId,
        purpose = purpose,
        status = status,
    )
}
