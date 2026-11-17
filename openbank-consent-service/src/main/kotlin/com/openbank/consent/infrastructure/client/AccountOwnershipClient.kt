// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.consent.application.port.out.AccountOwnershipPort
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

data class OwnershipVerificationRequestDto(val iban: String, val partyId: UUID)

/** Absent fields default to "not owned", so a malformed answer can never read as ownership. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OwnershipVerificationResponseDto(
    val owned: Boolean = false,
    val active: Boolean = false,
    val accountId: UUID? = null,
)

/**
 * account-service `POST /api/v1/accounts/ownership-verifications` (action `account.verifyOwnership`,
 * ADR-0335 D2), as consent-service's own principal `service-account-openbank-consent` via the named
 * oidc-client `m2m`. The projection answers exactly `{owned, active}`; an unknown IBAN and another
 * party's IBAN answer alike, and the IBAN travels in the body, out of access logs.
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/accounts/ownership-verifications")
interface AccountOwnershipClient {
    @POST
    suspend fun verify(request: OwnershipVerificationRequestDto): OwnershipVerificationResponseDto
}

/**
 * Ownership, not activity: an unrestricted consent covers the party's own accounts whatever their
 * lifecycle state, so `active` is not consulted. A failure propagates; the validate use case turns
 * it into `CONSENT_ACCOUNT_UNVERIFIABLE`.
 */
@ApplicationScoped
class AccountOwnershipAdapter @Inject constructor(@RestClient private val client: AccountOwnershipClient) :
    AccountOwnershipPort {

    @Timeout(2000)
    @Retry(maxRetries = 1, delay = 200, jitter = 100, retryOn = [Exception::class])
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    override suspend fun isOwnedBy(iban: String, partyId: UUID): Boolean =
        client.verify(OwnershipVerificationRequestDto(iban, partyId)).owned
}
