// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.identity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/*
 * Outbound REST clients for the identity and SCA checks (ADR-0334, #12377). Every one authenticates
 * with pension-service's OWN client-credentials token (default oidc-client -> Keycloak client
 * `openbank-pension`, ROLE_API only), never the shared `openbank-services` client. The routes and
 * shapes are the providers' published ones on origin/main; the consumer pacts in
 * `src/test/.../contract` pin them and the providers' @PactFolder classes replay them.
 */

/** sca-service `POST /api/v1/sca/challenges/{id}/consume` (openapi 1.16.0). */
@Path("/api/v1/sca/challenges")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "sca-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface ScaConsumeRestClient {
    @POST
    @Path("/{id}/consume")
    suspend fun consume(@PathParam("id") id: UUID, request: ScaConsumeRequestDto): ScaChallengeDto
}

/** The APPROVAL-shaped subset of sca-service's ConsumeScaRequest; null fields are not sent. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScaConsumeRequestDto(val partyId: UUID, val approvalRequestId: String, val payloadSha256: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScaChallengeDto(
    val id: UUID? = null,
    val partyId: UUID? = null,
    val purpose: String? = null,
    val status: String? = null,
    val consumedAt: String? = null,
)

/** party-service `GET /api/v1/parties/{id}` (ROLE_API, no @Authorize). */
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface PartyRestClient {
    @GET
    @Path("/{id}")
    suspend fun party(@PathParam("id") id: UUID): PartyDto
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyAddressDto(val countryCode: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyDto(
    val id: UUID? = null,
    val partyType: String? = null,
    val status: String? = null,
    val legalName: String? = null,
    val kycStatus: String? = null,
    val address: PartyAddressDto? = null,
    /** The participant's notice language (cs / en), when the party record carries one. */
    val preferredLanguage: String? = null,
)

/**
 * account-service `POST /api/v1/accounts/ownership-verifications` (action `account.verifyOwnership`,
 * ADR-0335 D2). A boolean projection: pension never reads an account. An unknown IBAN and another
 * party's IBAN answer the same; the IBAN travels in the body, never in a path or access log.
 */
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface AccountRestClient {
    @POST
    @Path("/ownership-verifications")
    suspend fun verifyOwnership(request: OwnershipVerificationRequestDto): OwnershipVerificationDto
}

data class OwnershipVerificationRequestDto(val iban: String, val partyId: UUID)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OwnershipVerificationDto(
    val owned: Boolean = false,
    val active: Boolean = false,
    val accountId: UUID? = null,
)
