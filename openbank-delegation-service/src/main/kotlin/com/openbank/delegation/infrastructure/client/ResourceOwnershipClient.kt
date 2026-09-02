// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.logging.Log
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
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
data class AccountOwnerResponse(val id: UUID, val partyId: UUID)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CardOwnerResponse(val id: UUID, val partyId: UUID)

@Path("/api/v1/accounts")
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
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface AccountServiceRestClient {
    @GET
    @Path("/{id}")
    suspend fun getAccount(@PathParam("id") id: UUID): AccountOwnerResponse
}

@Path("/api/v1/cards")
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
@RegisterRestClient(configKey = "card-issuance-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface CardIssuanceRestClient {
    @GET
    @Path("/{id}")
    suspend fun getCard(@PathParam("id") id: UUID): CardOwnerResponse
}

/**
 * Answers "does this grantor own that resource?" against the service that owns the resource,
 * because no other party to the grant can. See [ResourceOwnershipClient] for why this gate has
 * to exist at all.
 *
 * A SAVINGS_GOAL grant is keyed on the OWNING ACCOUNT's id, not a goal id — the convention the
 * account-service projection already relies on (a savings goal is account metadata, ADR-0153),
 * so both resource types resolve through the same account lookup.
 *
 * Object-level types (PAYMENT, STATEMENT, DOCUMENT — ADR-0232 D7) return [UNVERIFIABLE]: no
 * ownership lookup is wired for them yet, and D7 disclosure is not implemented. UNVERIFIABLE is
 * rejected by the caller, so an unimplemented resource type is refused rather than waved through.
 */
@ApplicationScoped
class RestResourceOwnershipClient @Inject constructor(
    @RestClient private val accountClient: AccountServiceRestClient,
    @RestClient private val cardClient: CardIssuanceRestClient,
) : ResourceOwnershipClient {

    @Timeout(2000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100, retryOn = [Exception::class])
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    override suspend fun verifyOwnership(
        grantorPartyId: UUID,
        resourceType: DelegationResourceType,
        resourceId: UUID,
    ): OwnershipVerdict = when (resourceType) {
        DelegationResourceType.ACCOUNT, DelegationResourceType.SAVINGS_GOAL ->
            verdictFor(grantorPartyId, resourceId) { accountClient.getAccount(it).partyId }

        DelegationResourceType.CARD ->
            verdictFor(grantorPartyId, resourceId) { cardClient.getCard(it).partyId }

        DelegationResourceType.PAYMENT,
        DelegationResourceType.STATEMENT,
        DelegationResourceType.DOCUMENT,
        -> OwnershipVerdict.UNVERIFIABLE
    }

    /**
     * A 404 from the owning service is NOT_OWNED: the resource does not exist, which is a
     * definitive negative answer. Every other failure is UNVERIFIABLE so an outage stays
     * distinguishable from a real ownership violation in the logs and metrics — both refuse the
     * offer, but only one of them is somebody trying something.
     */
    @Suppress("TooGenericExceptionCaught") // any transport failure must land on UNVERIFIABLE
    private suspend fun verdictFor(
        grantorPartyId: UUID,
        resourceId: UUID,
        lookupOwner: suspend (UUID) -> UUID,
    ): OwnershipVerdict = try {
        if (lookupOwner(resourceId) == grantorPartyId) OwnershipVerdict.OWNED else OwnershipVerdict.NOT_OWNED
    } catch (e: NotFoundException) {
        Log.warnf("ownership lookup: resource %s does not exist (%s)", resourceId, e.message)
        OwnershipVerdict.NOT_OWNED
    } catch (e: Exception) {
        Log.errorf(e, "ownership lookup for resource %s failed — refusing the offer", resourceId)
        OwnershipVerdict.UNVERIFIABLE
    }
}
