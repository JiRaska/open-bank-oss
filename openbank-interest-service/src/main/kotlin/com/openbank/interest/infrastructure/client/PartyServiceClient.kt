// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * REST client for `openbank-party-service`, used by `PartyTaxProfileProvider` to resolve the
 * withholding-tax profile of an account's beneficiary (ADR-0339 B: party-service is the sole
 * owner of tax residency; interest-service holds no copy).
 *
 * OIDC service-to-service auth rides the NAMED oidc-client `ledger` — Keycloak client
 * `openbank-interest` (ROLE_API only), the same identity the account/ledger/transaction legs
 * already use. `GET /parties/{id}` is @RolesAllowed(ROLE_API); `GET /parties/{id}/aml-profile`
 * additionally carries @Authorize("party.amlProfile.read"), granted to this principal by
 * `service-interest-party-m2m` in party_rest_ext.rego.
 *
 * GDPR minimization (ADR-0339 A): the AML-profile response also carries customers' TINs, but
 * the DTOs below deliberately expose ONLY what the withholding policy consumes — the party
 * type and the residency country codes. TINs are never deserialized into this service and
 * never persisted here; they exist for the future CRS/FATCA reporting capability.
 */
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// Same NAMED client as AccountServiceClient: #10486's batches migrated the interest-service
// edges off the shared openbank-services principal onto its own ROLE_API-only identity.
@OidcClientFilter("ledger")
@Produces(MediaType.APPLICATION_JSON)
@Path("/api/v1/parties")
interface PartyServiceClient {

    /** The party record — [PartyDetailClientResponse.partyType] decides the taxpayer type. */
    @GET
    @Path("/{id}")
    fun getParty(@PathParam("id") id: UUID): Uni<PartyDetailClientResponse>

    /**
     * The party's current personal AML profile (404 when none was declared, 422 for a non-
     * INDIVIDUAL party — person-only, see party-service `requireEligible`). Only the residency
     * countries are consumed.
     */
    @GET
    @Path("/{id}/aml-profile")
    fun getAmlProfile(@PathParam("id") id: UUID): Uni<PartyAmlProfileClientResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyDetailClientResponse(val id: UUID? = null, val partyType: String? = null, val status: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyAmlProfileClientResponse(
    /** ISO 3166-1 alpha-2 residency country codes (the response's TIN map is ignored, see file KDoc). */
    val taxResidencies: List<String> = emptyList(),
    val usPerson: Boolean = false,
)
