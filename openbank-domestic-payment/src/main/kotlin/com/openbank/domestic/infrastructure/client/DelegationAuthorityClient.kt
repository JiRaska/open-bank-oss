// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.DelegationAuthorityPort
import com.openbank.libs.fincrime.admission.DelegationAuthorityFacts
import com.openbank.libs.fincrime.admission.DelegationAuthorityGrantWire
import com.openbank.libs.fincrime.admission.DelegationFactsLookup
import com.openbank.libs.fincrime.admission.UnavailableCause
import io.quarkus.oidc.client.OidcClient
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
interface DelegationAuthorityRestClient {
    @GET
    @Path("/{accountId}/delegation/authority-facts")
    fun authorityFacts(
        @HeaderParam("Authorization") authorization: String,
        @PathParam("accountId") accountId: String,
        @QueryParam("partyId") partyId: String,
    ): Uni<AuthorityFactsDto>
}

data class AuthorityFactsDto(val grants: List<AuthorityGrantDto> = emptyList())

data class AuthorityGrantDto(
    val grantId: String? = null,
    val grantorPartyId: String? = null,
    val capabilities: List<String> = emptyList(),
    val validFrom: String? = null,
    val validTo: String? = null,
)

/**
 * Reads account-service's authority facts for the chain's AUTHORITY step (ADR-0375 D1).
 *
 * **Every failure here is [DelegationFactsLookup.Unavailable], never an empty list.** The sibling
 * [AccountServiceClient] answers `null` on any error, which is correct for its questions and is the
 * single most dangerous shape for this one: an empty answer is a definitive "no grant", so a
 * timeout rendered as empty turns an account-service blip into a refused payment that looks to
 * everyone — customer, support, audit trail — exactly like a withdrawn authority. The cases are
 * kept apart all the way down, including a 404 (the route is not served by the peer we are talking
 * to, which is a deployment fault and not an answer about grants) and a malformed grant.
 */
@ApplicationScoped
class DelegationAuthorityClient(
    private val oidcClient: Instance<OidcClient>,
    @ConfigProperty(
        name = "quarkus.rest-client.account-service.url",
        defaultValue = "http://account-service.accounts.svc:8100",
    )
    private val baseUrl: String,
) : DelegationAuthorityPort {

    private val log = Logger.getLogger(DelegationAuthorityClient::class.java)

    private val httpClient by lazy {
        RestClientBuilder.newBuilder()
            .baseUri(URI.create(baseUrl))
            .build(DelegationAuthorityRestClient::class.java)
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun factsFor(accountId: UUID, partyId: UUID): DelegationFactsLookup = try {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val dto = httpClient
            .authorityFacts("Bearer $token", accountId.toString(), partyId.toString())
            .awaitSuspending()
        lookupOrUnavailable(dto.grants.map(::wireOf), accountId, partyId)
    } catch (ex: jakarta.ws.rs.WebApplicationException) {
        // Deliberately including 404. account-service answers 200 with an empty list for an
        // unknown account precisely so that this client never has to read a status code as a
        // statement about grants; a 404 therefore means the route is not there, which is a
        // deployment fault.
        log.warnf("Delegation authority facts unavailable: HTTP %d", ex.response.status)
        unavailable(UnavailableCause.DEPENDENCY_DOWN, "HTTP ${ex.response.status}")
    } catch (ex: Exception) {
        log.warnf("Delegation authority facts unavailable (%s)", ex.javaClass.simpleName)
        unavailable(UnavailableCause.DEPENDENCY_DOWN, ex.javaClass.simpleName)
    }

    /**
     * A grant missing any identifying field is [DelegationFactsLookup.Unavailable], not a grant we
     * silently drop: dropping it would turn a payload we cannot read into a refusal.
     */
    private fun wireOf(dto: AuthorityGrantDto): DelegationAuthorityGrantWire? {
        val grantId = dto.grantId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val grantor = dto.grantorPartyId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val validFrom = dto.validFrom ?: return null
        return DelegationAuthorityGrantWire(grantId, grantor, dto.capabilities, validFrom, dto.validTo)
    }

    private fun lookupOrUnavailable(
        wire: List<DelegationAuthorityGrantWire?>,
        accountId: UUID,
        partyId: UUID,
    ): DelegationFactsLookup {
        if (wire.any { it == null }) {
            return unavailable(UnavailableCause.MISSING_DATA, "a grant was missing an identifying field")
        }
        // The shared mapper owns the rest: timestamp parsing, `active`, and the decision that one
        // unreadable validity window makes the WHOLE lookup unavailable rather than one grant absent.
        return DelegationAuthorityFacts.lookupOf(wire.filterNotNull(), accountId, partyId)
    }

    private fun unavailable(cause: UnavailableCause, detail: String) = DelegationFactsLookup.Unavailable(cause, detail)
}
