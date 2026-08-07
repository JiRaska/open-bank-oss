// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
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
 * Resolves a party's e-mail address for EMAIL delivery (issue #3581).
 *
 * Every producer on `openbank.notification.requests` — campaign-service, account-service and
 * sca-service alike — puts the **party id** in the request's `recipient` field, because none of
 * them holds customer PII. Nothing resolved it, so the UUID reached `Mail.withHtml(...)` as the
 * SMTP envelope and every such mail was undeliverable. PUSH never had this problem: it looks its
 * destination up from `partyId` in the device-token registry. This client closes the asymmetry by
 * giving EMAIL the same treatment, once, on the delivery side.
 *
 * `GET /api/v1/parties/{id}` is authorized to ROLE_VIEWER/OPERATOR/ADMIN/KYC/ROLE_API and the
 * shared `openbank-services` client_credentials filter carries one of those — the same M2M pattern
 * as [ConsentServiceClient]. The response is not cached: an address the customer has since changed
 * is exactly the thing a cache would get wrong.
 */
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
interface PartyContactClient {
    @GET
    @Path("/{id}")
    fun getParty(@PathParam("id") id: UUID): Uni<PartyContactResponse>
}

/**
 * Only the address is read. `@JsonIgnoreProperties(ignoreUnknown = true)` is load-bearing: the
 * party response carries the full customer record, and binding fields this service has no reason
 * to hold would copy PII into a heap it never needs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyContactResponse(val email: String? = null)
