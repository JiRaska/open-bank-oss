// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.pid.application.port.`in`.EudiVerifyPresentationUseCase
import com.openbank.pid.application.port.`in`.VerifyPresentationCommand
import com.openbank.pid.application.port.out.PidVerificationException
import com.openbank.pid.infrastructure.openid4vp.PresentationExchangeStore
import io.quarkus.logging.Log
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Clock
import java.time.Instant

/**
 * Public OpenID4VP `direct_post` callback the wallet POSTs its presentation to (eIDAS 2.0, ADR-0094).
 *
 * Anonymous **by necessity** — the wallet holds no RP credentials. It is hosted in its OWN resource
 * class (no class-level `@RolesAllowed`) so the method-level `@PermitAll` is honoured rather than
 * pre-empted by an OIDC challenge, mirroring the customer-edge public-onboarding pattern.
 *
 * Security: the only thing that admits a request is a still-PENDING, unexpired `state` (transaction
 * id) the RP itself minted — there is no way to forge one. The verifier then enforces signature,
 * issuer trust, disclosure binding AND holder key-binding against the exchange's single-use nonce;
 * the nonce is spent atomically ([PresentationExchangeStore.complete]) so a replay loses the race.
 * The response is neutral (`received`) and never reveals the resolution decision — that is collected
 * out-of-band by the RP via the authenticated poll endpoint (no identity-existence oracle to the
 * wallet). Abuse defence on the unauthenticated route is the ingress per-IP rate limit.
 */
@Path("/api/v1/parties/eudi")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "EUDI identity", description = "eIDAS 2.0 wallet presentation verification (ADR-0094)")
class EudiWalletCallbackResource(
    private val eudiVerify: EudiVerifyPresentationUseCase,
    private val exchangeStore: PresentationExchangeStore,
    private val clock: Clock,
) {

    @POST
    @Path("/presentation-responses")
    @PermitAll
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "OpenID4VP direct_post: a wallet submits its PID presentation for a pending exchange")
    suspend fun submitPresentation(
        @FormParam("vp_token") vpToken: String?,
        @FormParam("state") state: String?,
        @Suppress("UNUSED_PARAMETER") @FormParam("presentation_submission") presentationSubmission: String?,
    ): Response {
        if (vpToken.isNullOrBlank() || state.isNullOrBlank()) return invalidRequest()
        val now = Instant.now(clock)
        val exchange = exchangeStore.find(state, now)
        if (exchange == null || exchange.status != PresentationExchangeStore.Status.PENDING) {
            // Unknown / already-consumed / expired — neutral, non-enumerable.
            return invalidRequest()
        }
        // Verify + resolve BEFORE spending the nonce, so a crypto failure leaves the exchange PENDING
        // for a legitimate-wallet retry within the TTL. The failure reason is NEVER surfaced on this
        // public route — it would be an oracle for an attacker tuning a forged/replayed VP — so the
        // PidVerificationException is collapsed to the same neutral invalid_request, logged server-side
        // only at debug.
        val result = try {
            eudiVerify.verifyAndResolve(
                VerifyPresentationCommand(vpToken = vpToken, nonce = exchange.nonce, audience = exchange.audience),
            )
        } catch (e: PidVerificationException) {
            Log.debugf("EUDI OpenID4VP: presentation verification failed for exchange %s: %s", state, e.message)
            return invalidRequest()
        }
        if (!exchangeStore.complete(state, result, now)) {
            // Lost the single-use race (a concurrent valid post already consumed it) — treat as replay.
            return invalidRequest()
        }
        Log.infof("EUDI OpenID4VP: presentation accepted for exchange %s", state)
        return Response.ok("""{"status":"received"}""").build()
    }

    private fun invalidRequest(): Response =
        Response.status(Response.Status.BAD_REQUEST).entity("""{"error":"invalid_request"}""").build()
}
