// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import com.openbank.cardprocessing.application.port.out.AgentMandatePort
import com.openbank.cardprocessing.application.port.out.MandateOutcome
import com.openbank.cardprocessing.application.port.out.MandateVerification
import com.openbank.cardprocessing.application.port.out.PresentedMandate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Binds [AgentMandatePort] to ap2-service (ADR-0283 D6, ADR-0193).
 *
 * ## Fails CLOSED, and says which kind of closed
 *
 * Anything that is not a read verdict of `valid = true` declines the authorisation. What varies is
 * WHY, and the two reasons are kept apart everywhere they travel:
 *
 *  - `REJECTED` — ap2-service answered and the mandate does not authorise this payment. The agent
 *    exceeded its authority, or the mandate is expired, or the signature did not verify.
 *  - `UNVERIFIABLE` — nobody answered, the policy denied the call, or the body could not be read.
 *    This bank could not tell. It is not evidence about the agent at all.
 *
 * A single boolean would merge them, and the operational responses are opposite: one is a customer
 * conversation about a wallet's limits, the other is an incident about a service being down.
 *
 * ## What it does NOT do
 *
 * It does not verify the signature, hold a trust list or parse the JOSE encoding. ap2-service owns
 * that (ADR-0193), and a second verifier here would be a second opinion about whether an agent may
 * spend — two copies of one control, free to disagree.
 */
@ApplicationScoped
class Ap2MandateAdapter(@RestClient private val client: Ap2ServiceClient) : AgentMandatePort {

    private val log = Logger.getLogger(Ap2MandateAdapter::class.java)

    @Suppress(
        // The catch below is deliberately broad, and detekt is right that this usually hides a bug.
        // Here it is the fail-closed rule: this sits on a payment path, and ANY failure to reach a
        // verdict must DECLINE rather than escape as a 500 that leaves no record an agent tried.
        // Naming individual exception types would let the next transport change (a new client, a
        // proxy, a serialiser) throw something unlisted and turn a decline into an unhandled error.
        // ap2-service's own verify endpoint carries the same suppression for the same reason.
        "TooGenericExceptionCaught",
    )
    override suspend fun verify(
        mandate: PresentedMandate,
        amountMinorUnits: Long,
        currencyCode: String,
        payee: String,
        at: Instant,
    ): MandateVerification = try {
        val verdict = client.verify(
            // The acting agent, or the anonymous stand-in ap2-service's own endpoint uses when no
            // agent identifies itself. Per-agent OAuth binding is ADR-0181 phase 2; until then the
            // fixed id is what the policy can grant, and naming it here keeps that visible instead
            // of looking like a real per-agent identity.
            agentId = mandate.agentId?.takeIf { it.isNotBlank() } ?: ANONYMOUS_AGENT,
            request = Ap2VerifyRequest(
                mandate = Ap2MandatePayload(
                    kind = mandate.kind,
                    issuer = mandate.issuer,
                    subject = mandate.subject,
                    constraints = Ap2ConstraintsPayload(
                        payee = mandate.payee,
                        amountCapMinor = mandate.amountCapMinorUnits,
                        currency = mandate.currency,
                        expiresAt = mandate.expiresAt,
                        singleUse = mandate.singleUse,
                    ),
                    signingInput = mandate.signingInput,
                    signatureB64 = mandate.signatureB64,
                    algorithm = mandate.algorithm,
                ),
                // The payment as THIS service knows it, never as the mandate describes it. Sending
                // the mandate's own figures back would ask the verifier to compare a value with
                // itself, and every constraint check would pass by construction.
                payment = Ap2PaymentPayload(
                    payee = payee,
                    amountMinor = amountMinorUnits,
                    currency = currencyCode,
                    at = at,
                ),
            ),
        )
        when (verdict.valid) {
            true -> MandateVerification(MandateOutcome.VERIFIED)
            false -> MandateVerification(MandateOutcome.REJECTED, verdict.failures.orEmpty())
            // Absent, not false. Jackson would coerce a missing Boolean to `false` and turn "the
            // response was unreadable" into "the agent exceeded its authority".
            null -> MandateVerification(
                MandateOutcome.UNVERIFIABLE,
                detail = "ap2-service answered without a verdict",
            )
        }
    } catch (e: WebApplicationException) {
        // A 403 here is the POLICY denying this agent the verification capability, which is still
        // "we could not establish authority" rather than "the mandate is bad" — the mandate was
        // never examined.
        log.warnf("ap2 mandate verification unavailable (HTTP %d)", e.response?.status ?: -1)
        MandateVerification(
            MandateOutcome.UNVERIFIABLE,
            detail = "ap2-service answered HTTP ${e.response?.status ?: -1}",
        )
    } catch (e: RuntimeException) {
        // Connection refused, timeout, DNS. Caught as RuntimeException rather than Exception: a
        // coroutine cancellation must keep propagating, and swallowing it here would leave the
        // authorisation running after its request was abandoned.
        log.warnf(e, "ap2 mandate verification could not be reached")
        MandateVerification(MandateOutcome.UNVERIFIABLE, detail = e.message)
    }

    private companion object {
        /** ap2-service's own fallback principal (`Ap2VerifyEndpoint.ANONYMOUS_AGENT`). */
        const val ANONYMOUS_AGENT = "agent:ap2-anonymous"
    }
}
