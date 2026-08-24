// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class PidPartyResponse(
    val id: UUID,
    val status: String,
    val kycAttributes: PidKycAttributes?,
    val coreAttributes: PidCoreAttributes? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PidKycAttributes(val kycLevel: String?)

/**
 * Only the two name fields, out of a `PartyResponse.coreAttributes` that also carries birthdate,
 * birth number, gender, birthplace, nationalities and identity documents. `@JsonIgnoreProperties`
 * drops the rest at the parser, so none of it is ever materialised in this service (issue #3604).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PidCoreAttributes(val givenName: String? = null, val familyName: String? = null)

@Path("/api/v1/parties")
/**
 * Carries the shared `openbank-services` client-credentials token. Every endpoint this client
 * reaches is `@RolesAllowed`, so without the filter the call goes out with no Authorization header
 * and 401s — which the caller then reports as its fail-closed verdict, not as a misconfiguration.
 *
 * Invisible to this repo's tests: they all mock the client interface, so nothing exercises the
 * wire. It surfaced only against the deployed sandbox, where the offer refused every grant with
 * "ownership could not be established" while the underlying cause was `Unauthorized, status
 * code 401` in the pod log.
 */
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "pid-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
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
            displayName = displayNameOf(party.coreAttributes),
        )
    }

    /**
     * Null rather than an empty or half-formed string: the consumer of this field renders the
     * party id when it is absent, and a blank label on a consent screen is worse than a UUID
     * because it looks like a name that failed to load.
     */
    private fun displayNameOf(core: PidCoreAttributes?): String? = core
        ?.let { listOfNotNull(it.givenName, it.familyName).joinToString(" ") { part -> part.trim() } }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
