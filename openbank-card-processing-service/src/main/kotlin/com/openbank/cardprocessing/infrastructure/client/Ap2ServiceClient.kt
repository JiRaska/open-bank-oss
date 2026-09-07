// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.time.Instant

/**
 * ap2-service's mandate verification surface (ADR-0193).
 *
 * `SyntheticTaintClientFilter`, not `SyntheticTaintExternalBoundary`: ap2-service is an INTERNAL
 * hop, so the bank-owned synthetic marker travels with the call (ADR-0252). A synthetic
 * agent-initiated authorisation whose verification reached ap2-service as ordinary traffic would put
 * synthetic activity into that service's own aggregates.
 *
 * The path is a LITERAL here and would be a literal again in any consumer pact expectation —
 * deriving one from the other is DRY and vacuous, which is how a call to a route that never existed
 * shipped (#2269).
 *
 * `X-Agent-Id` carries the ACTING agent, forwarded from the authorisation request. ap2-service
 * classifies it as an `AI_AGENT` principal and authorises `verify.mandate` against that id, so the
 * decision and its audit record name the agent that is spending rather than this service.
 */
@Path("/ap2/verify")
@RegisterRestClient(configKey = "ap2-api")
@OidcClientFilter
@RegisterProvider(SyntheticTaintClientFilter::class)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
interface Ap2ServiceClient {
    @POST
    suspend fun verify(@HeaderParam("X-Agent-Id") agentId: String, request: Ap2VerifyRequest): Ap2VerdictResponse
}

/** Mirrors ap2-service's `VerifyRequest`. Retyped, not shared: the two services share no module. */
data class Ap2VerifyRequest(val mandate: Ap2MandatePayload, val payment: Ap2PaymentPayload)

data class Ap2MandatePayload(
    val kind: String,
    val issuer: String,
    val subject: String,
    val constraints: Ap2ConstraintsPayload,
    val signingInput: String,
    val signatureB64: String,
    val algorithm: String,
)

data class Ap2ConstraintsPayload(
    val payee: String,
    val amountCapMinor: Long,
    val currency: String,
    val expiresAt: Instant,
    val singleUse: Boolean,
)

data class Ap2PaymentPayload(val payee: String, val amountMinor: Long, val currency: String, val at: Instant)

/**
 * Mirrors ap2-service's `MandateVerdict`.
 *
 * `valid` is nullable on purpose. A body that arrived without it is not a `false` — it is a
 * response this adapter could not read, which is `UNVERIFIABLE` and a different fact from a mandate
 * that was rejected. Jackson would otherwise coerce an absent Boolean to `false`
 * (reference: Jackson-Kotlin coerces an absent Boolean to false), turning "we could not tell" into
 * "the agent exceeded its authority".
 */
data class Ap2VerdictResponse(val valid: Boolean?, val failures: List<String>? = null)
