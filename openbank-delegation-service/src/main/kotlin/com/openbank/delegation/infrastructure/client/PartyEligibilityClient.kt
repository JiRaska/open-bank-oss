// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class PidPartyResponse(val id: UUID, val status: String, val kycAttributes: PidKycAttributes?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PidKycAttributes(val kycLevel: String?)

@Path("/api/v1/parties")
@RegisterRestClient(configKey = "pid-service")
interface PidServiceRestClient {
    @GET
    @Path("/{id}")
    suspend fun getParty(@PathParam("id") id: UUID): PidPartyResponse
}

@ApplicationScoped
class ResilientPartyEligibilityClient @Inject constructor(@RestClient private val client: PidServiceRestClient) :
    PartyEligibilityClient {

    @Timeout(2000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100, retryOn = [Exception::class])
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    override suspend fun eligibilityOf(partyId: UUID): PartyEligibility {
        val party = client.getParty(partyId)
        return PartyEligibility(
            partyId = party.id,
            active = party.status == "ACTIVE",
            kycLevel = party.kycAttributes?.kycLevel ?: "NONE",
        )
    }
}
