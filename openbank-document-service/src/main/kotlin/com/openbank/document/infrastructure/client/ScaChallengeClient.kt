// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
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

/**
 * RestClient binding to `openbank-sca-service`'s challenge endpoints (ADR-0021/ADR-0162 D4).
 * Carries the service OIDC token via the reactive client filter, like the other inter-service
 * clients (e.g. `openbank-standing-order-service`'s `SepaPaymentClient`).
 */
@RegisterRestClient(configKey = "sca-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Produces(MediaType.APPLICATION_JSON)
interface ScaChallengeClient {

    @GET
    @Path("/api/v1/sca/challenges/{id}")
    fun getChallenge(@PathParam("id") id: UUID): Uni<ScaChallengeClientResponse>

    /**
     * Spends the challenge so it cannot be presented as `evidenceRef` a second time (RTS Art. 5
     * single-use). Not in sca-service's published `openapi.yaml` (a pre-existing gap there, out of
     * scope here) but a real, live endpoint — `ScaResource.consume` — gated
     * `@Authorize(action = "scaChallenge.consume")`. Designed for the payment settlement gate
     * (amount/currency/creditor dynamic-linking check), but those fields are optional and a
     * document signature has none of them; supplying only `partyId` still exercises the same
     * single-use invariant this ceremony needs.
     */
    @POST
    @Path("/api/v1/sca/challenges/{id}/consume")
    @Consumes(MediaType.APPLICATION_JSON)
    fun consume(@PathParam("id") id: UUID, request: ScaConsumeClientRequest): Uni<ScaChallengeClientResponse>
}

data class ScaConsumeClientRequest(
    val partyId: UUID,
    /** Document content address (SHA-256), for a DOCUMENT_SIGNING challenge (ADR-0169 D2). */
    val documentSha256: String? = null,
    /** The signature ceremony this consume is scoped to, for a DOCUMENT_SIGNING challenge. */
    val ceremonyId: String? = null,
)

/** Mirror of sca-service's `ScaChallengeResponse` (tolerate unknown fields as the contract evolves). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScaChallengeClientResponse(
    val id: UUID,
    val partyId: UUID,
    val challengeType: String = "",
    val status: String,
)
