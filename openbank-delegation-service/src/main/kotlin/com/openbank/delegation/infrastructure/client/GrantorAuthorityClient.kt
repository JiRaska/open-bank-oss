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
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
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
data class ActingForResponse(val partyId: UUID)

@Path("/api/v1/parties")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
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
 * `acting-for` returns only active, in-window mandates over live entities, so matching the
 * principal is the complete ADR-0284 representation decision. Every transport or parse failure
 * remains distinguishable from a real denial and fails closed in the use case.
 */
@ApplicationScoped
class RestGrantorAuthorityClient @Inject constructor(@RestClient private val client: PartyAuthorityRestClient) :
    GrantorAuthorityClient {

    // Every transport or parse failure must become an explicit fail-closed verdict.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun authorityFor(principalPartyId: UUID, actorPartyId: UUID): GrantorAuthority = try {
        val principal = client.getParty(principalPartyId)
        val authorized = when (principal.partyType) {
            "INDIVIDUAL" -> actorPartyId == principalPartyId && principal.status == "ACTIVE"
            "SOLE_TRADER", "COMPANY", "TRUST" ->
                principal.status == "ACTIVE" && client.actingFor(actorPartyId).any { it.partyId == principalPartyId }
            else -> false
        }
        GrantorAuthority(
            verdict = if (authorized) GrantorAuthorityVerdict.AUTHORIZED else GrantorAuthorityVerdict.DENIED,
            displayName = principal.legalName?.trim()?.takeIf { it.isNotEmpty() },
        )
    } catch (e: NotFoundException) {
        GrantorAuthority(GrantorAuthorityVerdict.DENIED)
    } catch (e: Exception) {
        Log.errorf(e, "grantor authority lookup for principal %s failed — refusing", principalPartyId)
        GrantorAuthority(GrantorAuthorityVerdict.UNVERIFIABLE)
    }
}
