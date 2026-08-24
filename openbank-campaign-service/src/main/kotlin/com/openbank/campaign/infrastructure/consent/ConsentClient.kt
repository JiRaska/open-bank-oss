// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.consent

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.campaign.application.port.out.ConsentCheckPort
import com.openbank.libs.contact.ContactSuppressionPort
import com.openbank.libs.contact.SuppressionEntry
import com.openbank.libs.contact.SuppressionReason
import com.openbank.libs.contact.SuppressionScope
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
interface ConsentServiceClient {
    @GET
    @Path("/party/{partyId}/grantee/{granteeId}/active")
    fun hasActiveConsent(
        @PathParam("partyId") partyId: UUID,
        @PathParam("granteeId") granteeId: String,
        @QueryParam("scope") scope: String,
    ): Uni<ConsentCheckResponse>
}

@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/suppressions")
@Produces(MediaType.APPLICATION_JSON)
interface SuppressionServiceClient {
    @GET
    @Path("/party/{partyId}")
    fun listActive(@PathParam("partyId") partyId: UUID): Uni<List<SuppressionResponse>>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SuppressionResponse(
    val scope: SuppressionScope,
    val value: String? = null,
    val reason: SuppressionReason,
    val source: String,
) {
    fun toEntry(): SuppressionEntry = SuppressionEntry(scope, value, reason, source)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConsentCheckResponse(val granted: Boolean = false)

/**
 * ADR-0198/0195: the consent check is a live call to consent-service, never a cached copy — a
 * cached consent is one that survives its own revocation. Fail-closed without inventing a policy
 * decision: an unavailable consent-service propagates to ContactPolicyGate as GATE_UNAVAILABLE.
 * The Temporal activity retries it; it neither sends nor permanently labels the party as denied.
 */
@ApplicationScoped
class LiveConsentCheckAdapter(
    @RestClient private val client: ConsentServiceClient,
    @ConfigProperty(name = "openbank.campaign.consent-grantee", defaultValue = "party-service:marketing-comms")
    private val grantee: String,
) : ConsentCheckPort {

    override suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean =
        client.hasActiveConsent(partyId, grantee, scope).awaitSuspending().granted
}

/**
 * ADR-0219 D3: the contact gate reads the platform do-not-contact list owned by consent-service.
 * Failures are deliberately not converted to an empty list:
 * [com.openbank.libs.contact.ContactPolicyGate] catches the exception and fails closed with
 * GATE_UNAVAILABLE. An outage must never look like "this party has no suppressions".
 */
@ApplicationScoped
class LiveSuppressionAdapter(@RestClient private val client: SuppressionServiceClient) : ContactSuppressionPort {
    override suspend fun activeSuppressions(partyId: UUID): List<SuppressionEntry> =
        client.listActive(partyId).awaitSuspending().map(SuppressionResponse::toEntry)
}
