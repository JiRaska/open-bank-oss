// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.kyb.application.port.out.EntityPartyRequest
import com.openbank.kyb.application.port.out.MandateRequest
import com.openbank.kyb.application.port.out.PartyGateway
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

data class CreatePartyBody(
    val partyType: String,
    val legalName: String,
    val tradingName: String?,
    val dateOfBirth: String?,
    val nationality: String?,
    val taxId: String?,
    val registrationNumber: String?,
    val registrationCountry: String?,
    val legalForm: String?,
    val email: String,
    val phone: String?,
    val address: AddressBody?,
)

data class AddressBody(
    val line1: String,
    val line2: String?,
    val city: String,
    val postalCode: String,
    val countryCode: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyCreated(val id: UUID)

data class MandateBody(
    val agentPartyId: UUID,
    val role: String,
    val authority: String,
    val source: String,
    val evidenceRef: String,
)

/**
 * party-service, over the shared `openbank-services` client-credentials token. `POST /parties`
 * is ROLE_OPERATOR-gated and the shared service account carries that role; the mandate route is
 * `party.mandate.grant`, granted to this service's principal in `party_rest_ext.rego`.
 */
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterRestClient(configKey = "party-service")
interface PartyServiceRestClient {
    @POST
    suspend fun createParty(@HeaderParam("Idempotency-Key") idempotencyKey: String, body: CreatePartyBody): PartyCreated

    @POST
    @Path("/{id}/mandates")
    suspend fun grantMandate(@PathParam("id") principalPartyId: UUID, body: MandateBody): Any?
}

@ApplicationScoped
class PartyServiceGateway : PartyGateway {

    @Inject @RestClient
    lateinit var client: PartyServiceRestClient

    @Timeout(PARTY_TIMEOUT_MS)
    override suspend fun createEntityParty(request: EntityPartyRequest): UUID {
        val address = if (request.city != null && request.postalCode != null) {
            AddressBody(
                request.addressLine1 ?: "",
                null,
                request.city,
                request.postalCode,
                request.countryCode ?: request.registrationCountry ?: "XX",
            )
        } else {
            null
        }
        return client.createParty(
            request.idempotencyKey,
            CreatePartyBody(
                partyType = request.partyType,
                legalName = request.legalName,
                tradingName = null,
                dateOfBirth = null,
                nationality = request.registrationCountry,
                taxId = request.taxId,
                registrationNumber = request.registrationNumber,
                registrationCountry = request.registrationCountry,
                legalForm = request.legalForm,
                email = entityEmail(request),
                phone = null,
                address = address,
            ),
        ).id
    }

    /**
     * party-service requires a unique email on every party. An entity has no login and its contact
     * channels are its representatives-, so this is a deterministic, non-deliverable placeholder
     * derived from the identifier — never a person-s address.
     */
    private fun entityEmail(request: EntityPartyRequest): String {
        val country = request.registrationCountry?.lowercase() ?: "xx"
        return "$country-${request.registrationNumber.lowercase()}@entity.openbank.invalid"
    }

    @Timeout(PARTY_TIMEOUT_MS)
    override suspend fun grantMandate(request: MandateRequest) {
        client.grantMandate(
            request.principalPartyId,
            MandateBody(request.agentPartyId, request.role, request.authority, request.source, request.evidenceRef),
        )
    }

    private companion object {
        const val PARTY_TIMEOUT_MS = 5000L
    }
}
