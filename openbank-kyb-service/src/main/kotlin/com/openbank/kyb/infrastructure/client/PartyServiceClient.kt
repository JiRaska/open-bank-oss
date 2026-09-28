// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.kyb.application.port.out.EntityPartyRequest
import com.openbank.kyb.application.port.out.MandateRequest
import com.openbank.kyb.application.port.out.PartyGateway
import com.openbank.kyb.application.port.out.PepProfile
import com.openbank.kyb.domain.model.InitiatorIdentity
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
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

/** The part of party-service's `GET /parties/{id}` that decides who an initiator verifiably is. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyRecord(
    val legalName: String,
    val kycStatus: String?,
    val address: PartyAddress?,
    // Compliance metadata party-service stores (`parties.pep_flag`, `pep_category`, `fatca_status`,
    // `crs_status`). Nullable and defaulted: a response that does not carry them reads as "not on
    // file", which makes the person declare rather than silently count as non-PEP.
    val pepFlag: Boolean? = null,
    val pepCategory: String? = null,
    val fatcaStatus: String? = null,
    val crsStatus: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyAddress(val line1: String?, val city: String?, val postalCode: String?, val countryCode: String?)

data class MandateBody(
    val agentPartyId: UUID,
    val role: String,
    val authority: String,
    val requiredSignatures: Int,
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
// #10486 batch 3: this client's bearer for the entity-party create and mandate grant (party.create,
// party.mandate.grant) is minted by the NAMED oidc-client `m2m` - Keycloak client `openbank-kyb`
// (ROLE_API only) - never the shared `openbank-services` default client.
@OidcClientFilter("m2m")
@RegisterRestClient(configKey = "party-service")
// Below @RegisterRestClient deliberately: the taint gate reads the window between that annotation
// and the interface, and an internal edge must PROPAGATE the synthetic marker rather than declare
// itself external — an entity party minted for a synthetic case must stay tainted in party-service.
@RegisterProvider(SyntheticTaintClientFilter::class)
interface PartyServiceRestClient {
    @POST
    suspend fun createParty(@HeaderParam("Idempotency-Key") idempotencyKey: String, body: CreatePartyBody): PartyCreated

    @POST
    @Path("/{id}/mandates")
    suspend fun grantMandate(@PathParam("id") principalPartyId: UUID, body: MandateBody): Any?

    @GET
    @Path("/{id}")
    suspend fun getParty(@PathParam("id") id: UUID): PartyRecord
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
            MandateBody(
                request.agentPartyId,
                request.role,
                request.authority,
                request.requiredSignatures,
                request.source,
                request.evidenceRef,
            ),
        )
    }

    /**
     * The initiator's verified identity. 404 is "no such party" (null); anything else propagates, so a
     * party-service outage is an outage and never reads as "identity does not match".
     */
    @Timeout(PARTY_TIMEOUT_MS)
    override suspend fun initiatorIdentity(partyId: UUID): InitiatorIdentity? {
        val party = try {
            client.getParty(partyId)
        } catch (e: WebApplicationException) {
            if (e.response?.status == HTTP_NOT_FOUND) return null
            throw e
        }
        return InitiatorIdentity(
            legalName = party.legalName,
            address = party.address?.let {
                RegisteredAddress(it.line1, it.city, it.postalCode, it.countryCode.orEmpty())
            },
            verified = party.kycStatus == KYC_APPROVED,
        )
    }

    @Timeout(PARTY_TIMEOUT_MS)
    override suspend fun pepProfile(partyId: UUID): PepProfile? {
        val party = try {
            client.getParty(partyId)
        } catch (e: WebApplicationException) {
            if (e.response?.status == HTTP_NOT_FOUND) return null
            throw e
        }
        return PepProfile(pep = party.pepFlag, category = party.pepCategory)
    }

    private companion object {
        const val PARTY_TIMEOUT_MS = 5000L
        const val HTTP_NOT_FOUND = 404
        const val KYC_APPROVED = "APPROVED"
    }
}
