// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.ap2

import com.openbank.ap2.application.Ap2MandateVerifier
import com.openbank.ap2.application.port.out.MandateKeyResolver
import com.openbank.ap2.domain.Ap2Mandate
import com.openbank.ap2.domain.MandateConstraints
import com.openbank.ap2.domain.MandateKind
import com.openbank.ap2.domain.MandateSignatureAlgorithm
import com.openbank.ap2.domain.MandateVerdict
import com.openbank.ap2.domain.PresentedPayment
import com.openbank.ap2.infrastructure.crypto.JcaSignatureVerifier
import com.openbank.ap2.infrastructure.observability.Ap2MetricsAdapter
import com.openbank.ap2.infrastructure.rest.Ap2VerifyEndpoint
import com.openbank.ap2.infrastructure.rest.VerifyRequest
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** The OPA AI_AGENT gate on the verify surface (ADR-0193 §5): allow → 200, deny → 403, PDP outage → 503. */
class Ap2VerifyEndpointTest {

    // The REAL metrics adapter over a SimpleMeterRegistry rather than a mock port, so the
    // authorization-decision assertions below fail if the endpoint stops emitting.
    private val registry = SimpleMeterRegistry()

    private val verifier = Ap2MandateVerifier(
        JcaSignatureVerifier(),
        object : MandateKeyResolver {
            override fun resolve(issuer: String): String? = null
        },
        Ap2MetricsAdapter(registry),
    )

    private val request = VerifyRequest(
        mandate = Ap2Mandate(
            kind = MandateKind.PAYMENT,
            issuer = "issuer-1",
            subject = "cust-1",
            constraints = MandateConstraints("CZ65", 100_00, "CZK", Instant.parse("2026-12-31T00:00:00Z")),
            signingInput = "h.p",
            signatureB64 = "x",
            algorithm = MandateSignatureAlgorithm.ED25519,
        ),
        payment = PresentedPayment("CZ65", 50_00, "CZK", Instant.parse("2026-06-01T00:00:00Z")),
    )

    private fun endpoint(pdp: PolicyDecisionPoint) = Ap2VerifyEndpoint(verifier, pdp, Ap2MetricsAdapter(registry))

    @Test
    fun `allow returns 200 with a verdict`() {
        val resp = endpoint(allow()).verify(request, "agent:test")
        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.entity).isInstanceOf(MandateVerdict::class.java)
    }

    @Test
    fun `deny returns 403`() {
        val resp = endpoint(deny()).verify(request, "agent:test")
        assertThat(resp.status).isEqualTo(403)
    }

    @Test
    fun `pdp outage fails closed with 503`() {
        val resp = endpoint(exploding()).verify(request, "agent:test")
        assertThat(resp.status).isEqualTo(503)
    }

    private fun allow() = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery) = AuthzDecision(allow = true)
    }

    private fun deny() = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery) = AuthzDecision(allow = false, reason = "no matching allow rule")
    }

    private fun exploding() = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision = error("PDP down")
    }

    @Test
    fun `each PDP outcome is counted, including the fail-closed outage`() {
        // The endpoint calls the PDP directly rather than through the @Authorize interceptor, so it
        // emits no openbank_authz_decisions_total. A PDP outage denies EVERY agent and previously
        // left nothing behind but a WARN line.
        endpoint(allow()).verify(request, "agent:test")
        endpoint(deny()).verify(request, "agent:test")
        endpoint(exploding()).verify(request, "agent:test")

        assertThat(decisions("allowed")).isEqualTo(1.0)
        assertThat(decisions("denied")).isEqualTo(1.0)
        assertThat(decisions("pdp_unavailable")).isEqualTo(1.0)
    }

    @Test
    fun `a denied call verifies no mandate, so it publishes no verification counter`() {
        endpoint(deny()).verify(request, "agent:test")

        assertThat(decisions("denied")).isEqualTo(1.0)
        assertThat(registry.find("openbank.ap2.mandate.verifications").counters()).isEmpty()
    }

    private fun decisions(outcome: String): Double = registry.get("openbank.ap2.authorization.decisions")
        .tag("service", "ap2")
        .tag("outcome", outcome)
        .counter().count()
}
