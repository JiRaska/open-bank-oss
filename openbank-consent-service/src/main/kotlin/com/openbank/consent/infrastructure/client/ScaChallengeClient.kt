// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.client

import com.openbank.consent.application.port.out.ScaChallengeClient
import com.openbank.consent.application.port.out.ScaChallengeSnapshot
import com.openbank.libs.web.SyntheticTaintClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

data class ScaChallengeClientResponse(val id: UUID, val partyId: UUID, val purpose: String, val status: String)

@Path("/api/v1/sca/challenges")
@RegisterRestClient(configKey = "sca-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface ScaServiceRestClient {
    @GET
    @Path("/{id}")
    suspend fun getChallenge(@PathParam("id") id: UUID): ScaChallengeClientResponse
}

@ApplicationScoped
class ResilientScaChallengeClient @Inject constructor(@RestClient private val client: ScaServiceRestClient) :
    ScaChallengeClient {

    @Timeout(2000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100, retryOn = [Exception::class])
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    override suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot {
        val response = client.getChallenge(challengeId)
        return ScaChallengeSnapshot(
            id = response.id,
            partyId = response.partyId,
            purpose = response.purpose,
            status = response.status,
        )
    }
}
