// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.delegation.application.port.out.GrantorAuthority
import com.openbank.delegation.application.port.out.GrantorAuthorityClient
import com.openbank.delegation.application.port.out.GrantorAuthorityVerdict
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.logging.Log
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuthorityPartyResponse(
    val id: UUID,
    val partyType: String,
    val status: String,
    val legalName: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ActingForResponse(val partyId: UUID, val mandate: MandateResponse? = null)

// #10486 batch 8: party.mandate.read is minted by the NAMED oidc-client `m2m` - Keycloak client
// `openbank-delegation` (ROLE_API only) - never the shared `openbank-services` one.
@OidcClientFilter("m2m")
@Path("/api/v1/parties")
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface PartyAuthorityRestClient {
    @GET
    @Path("/{id}")
    suspend fun getParty(@PathParam("id") id: UUID): AuthorityPartyResponse

    @GET
    @Path("/{id}/acting-for")
    suspend fun actingFor(@PathParam("id") actorPartyId: UUID): List<ActingForResponse>
}

/**
 * Resolves the principal type and re-checks an organisation mandate at the authority boundary.
 * `acting-for` returns active, in-window mandates. A matching row authorizes profile access;
 * sole signing requires explicit authority and quorum from its mandate. Transport or parse
 * failures remain distinguishable from a denial.
 */
@ApplicationScoped
class RestGrantorAuthorityClient @Inject constructor(@RestClient private val client: PartyAuthorityRestClient) :
    GrantorAuthorityClient {

    // Every transport or parse failure must become an explicit fail-closed verdict.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun authorityFor(principalPartyId: UUID, actorPartyId: UUID): GrantorAuthority = try {
        val principal = client.getParty(principalPartyId)
        val verdict = when {
            principal.id != principalPartyId || principal.status != "ACTIVE" -> GrantorAuthorityVerdict.DENIED
            principal.partyType == "INDIVIDUAL" && actorPartyId == principalPartyId ->
                GrantorAuthorityVerdict.AUTHORIZED
            principal.partyType in ORGANIZATION_TYPES -> organizationAuthority(principalPartyId, actorPartyId)
            else -> GrantorAuthorityVerdict.DENIED
        }
        GrantorAuthority(
            verdict = verdict,
            displayName = principal.legalName?.trim()?.takeIf { it.isNotEmpty() },
            partyType = principal.partyType,
        )
    } catch (e: NotFoundException) {
        GrantorAuthority(GrantorAuthorityVerdict.DENIED)
    } catch (e: Exception) {
        Log.errorf(e, "grantor authority lookup for principal %s failed — refusing", principalPartyId)
        GrantorAuthority(GrantorAuthorityVerdict.UNVERIFIABLE)
    }

    private suspend fun organizationAuthority(principalPartyId: UUID, actorPartyId: UUID): GrantorAuthorityVerdict {
        val rows = client.actingFor(actorPartyId).filter { it.partyId == principalPartyId }
        return when {
            rows.isEmpty() -> GrantorAuthorityVerdict.DENIED
            rows.all { it.mandate?.isSoleFor(actorPartyId) == true } -> GrantorAuthorityVerdict.AUTHORIZED
            else -> GrantorAuthorityVerdict.MANDATE_ONLY
        }
    }

    private companion object {
        val ORGANIZATION_TYPES = setOf("SOLE_TRADER", "COMPANY", "TRUST")
    }
}

private fun MandateResponse.isSoleFor(actorPartyId: UUID): Boolean = agentPartyId == actorPartyId &&
    status == "ACTIVE" &&
    authority == "SOLE" &&
    requiredSignatures == 1
