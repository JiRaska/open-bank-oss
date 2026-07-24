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
import com.openbank.ap2.infrastructure.rest.Ap2VerifyEndpoint
import com.openbank.ap2.infrastructure.rest.VerifyRequest
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** The OPA AI_AGENT gate on the verify surface (ADR-0193 §5): allow → 200, deny → 403, PDP outage → 503. */
class Ap2VerifyEndpointTest {

    private val verifier = Ap2MandateVerifier(
        JcaSignatureVerifier(),
        object : MandateKeyResolver {
            override fun resolve(issuer: String): String? = null
        },
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

    private fun endpoint(pdp: PolicyDecisionPoint) = Ap2VerifyEndpoint(verifier, pdp)

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
}
