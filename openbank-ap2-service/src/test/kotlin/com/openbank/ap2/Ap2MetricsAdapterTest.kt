// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.ap2

import com.openbank.ap2.application.port.out.Ap2AuthorizationOutcome
import com.openbank.ap2.application.port.out.MandateSignatureOutcome
import com.openbank.ap2.domain.MandateKind
import com.openbank.ap2.infrastructure.observability.Ap2MetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class Ap2MetricsAdapterTest {

    private val registry = SimpleMeterRegistry()
    private val adapter = Ap2MetricsAdapter(registry)

    @Test
    fun `the mandate kind is a tag, so INTENT and PAYMENT are separable`() {
        adapter.mandateVerified(
            MandateKind.INTENT,
            MandateSignatureOutcome.VALID,
            constraintsSatisfied = true,
            valid = true,
            duration = Duration.ofMillis(3),
        )
        adapter.mandateVerified(
            MandateKind.PAYMENT,
            MandateSignatureOutcome.VALID,
            constraintsSatisfied = true,
            valid = true,
            duration = Duration.ofMillis(3),
        )

        assertThat(verdicts(MandateKind.INTENT, "valid")).isEqualTo(1.0)
        assertThat(verdicts(MandateKind.PAYMENT, "valid")).isEqualTo(1.0)
    }

    @Test
    fun `every signature outcome gets a distinct lower-cased tag value`() {
        MandateSignatureOutcome.entries.forEach {
            adapter.mandateVerified(
                MandateKind.PAYMENT,
                it,
                constraintsSatisfied = false,
                valid = false,
                duration = Duration.ZERO,
            )
        }

        MandateSignatureOutcome.entries.forEach { outcome ->
            assertThat(
                registry.get("openbank.ap2.mandate.signature")
                    .tag("kind", "PAYMENT").tag("outcome", outcome.name.lowercase()).counter().count(),
            ).isEqualTo(1.0)
        }
    }

    @Test
    fun `every authorization outcome gets a distinct lower-cased tag value`() {
        Ap2AuthorizationOutcome.entries.forEach { adapter.authorizationDecision(it) }

        Ap2AuthorizationOutcome.entries.forEach { outcome ->
            assertThat(
                registry.get("openbank.ap2.authorization.decisions")
                    .tag("service", "ap2").tag("outcome", outcome.name.lowercase()).counter().count(),
            ).isEqualTo(1.0)
        }
    }

    @Test
    fun `is a silent no-op when no meter registry is resolvable`() {
        // Slim slices without a Prometheus registry must not crash a verification.
        val noRegistry = Ap2MetricsAdapter(null)

        noRegistry.mandateVerified(
            MandateKind.CART,
            MandateSignatureOutcome.VALID,
            constraintsSatisfied = true,
            valid = true,
            duration = Duration.ZERO,
        )
        noRegistry.authorizationDecision(Ap2AuthorizationOutcome.ALLOWED)
    }

    private fun verdicts(kind: MandateKind, verdict: String): Double =
        registry.get("openbank.ap2.mandate.verifications")
            .tag("service", "ap2")
            .tag("kind", kind.name)
            .tag("verdict", verdict)
            .counter().count()
}
