// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.consent

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.engagement.application.port.out.ConsentCheckPort
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

/** Same shape as campaign-service's `ConsentServiceClient` — the live per-call check, never cached. */
@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
interface ConsentRestClient {
    @GET
    @Path("/party/{partyId}/grantee/{granteeId}/active")
    fun hasActiveConsent(
        @PathParam("partyId") partyId: UUID,
        @PathParam("granteeId") granteeId: String,
        @QueryParam("scope") scope: String,
    ): Uni<ConsentCheckResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConsentCheckResponse(val granted: Boolean = false)

@ApplicationScoped
class ConsentServiceClientAdapter(
    @RestClient private val client: ConsentRestClient,
    // Same fixed internal marketing grantee campaign-service's notification consumer checks
    // against (ADR-0205 D3) — one constant fleet-wide so the gate cannot drift per call site.
    @ConfigProperty(name = "openbank.engagement.marketing-grantee", defaultValue = "party-service:marketing-comms")
    private val marketingGrantee: String,
) : ConsentCheckPort {
    override suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean =
        client.hasActiveConsent(partyId, marketingGrantee, scope).awaitSuspending().granted
}

/**
 * ADR-0219 D3: the platform do-not-contact list owned by consent-service, same client shape as
 * campaign-service's and notification-service's. Read per call, never cached — a cached list
 * outlives the objection it records (GDPR Art. 21).
 */
@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/suppressions")
@Produces(MediaType.APPLICATION_JSON)
interface SuppressionRestClient {
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

/**
 * Failures are deliberately not converted to an empty list: `ContactPolicyGate` catches the
 * exception and fails closed with GATE_UNAVAILABLE, so an outage can never read as "this party
 * has no suppressions".
 */
@ApplicationScoped
class LiveSuppressionAdapter(@RestClient private val client: SuppressionRestClient) : ContactSuppressionPort {
    override suspend fun activeSuppressions(partyId: UUID): List<SuppressionEntry> =
        client.listActive(partyId).awaitSuspending().map(SuppressionResponse::toEntry)
}
