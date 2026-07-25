// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.infrastructure.observability

import com.openbank.ap2.application.port.out.Ap2AuthorizationOutcome
import com.openbank.ap2.application.port.out.Ap2MetricsPort
import com.openbank.ap2.application.port.out.MandateSignatureOutcome
import com.openbank.ap2.domain.MandateKind
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Duration

/**
 * Micrometer adapter for [Ap2MetricsPort] (ADR-0077 Tier C). Emits, all tagged `service="ap2"`:
 *
 *  - `openbank_ap2_mandate_verifications_total{kind,verdict}` — the headline rate. AP2 is an
 *    AI-agent-facing payment-authorization surface, so the valid:invalid ratio is the first thing an
 *    incident asks for.
 *  - `openbank_ap2_mandate_signature_total{kind,outcome}` — the signature stage, split. A sustained
 *    `issuer_not_trusted` is a rotated or mis-seeded trust list rejecting a legitimate issuer, which
 *    on the wire is indistinguishable from a forged one; `verification_error` is a malformed
 *    key/signature that the verifier deliberately swallows into a failed verdict.
 *  - `openbank_ap2_mandate_constraints_total{kind,outcome}` — the pure constraint stage (payee, cap,
 *    currency, expiry). `violated` is a presented payment outside the delegated authority: normal in
 *    small numbers, an agent misbehaving in large ones.
 *  - `openbank_ap2_mandate_verification_duration_seconds{kind}` — the crypto path's latency.
 *  - `openbank_ap2_authorization_decisions_total{outcome}` — the PDP gate. The endpoint calls the
 *    shared PDP directly rather than via the `@Authorize` interceptor, so no
 *    `openbank_authz_decisions_total` is emitted for it and `pdp_unavailable` — which denies every
 *    agent, fail-closed — previously produced only a WARN log line.
 *
 * No mandate hash, issuer, subject, agent id or amount is ever a tag: `kind` is a 3-value enum and
 * every other tag is a closed enum (cardinality contract). The issuer in particular is attacker-
 * controlled — tagging it would be an unbounded-series hole on an agent-facing surface.
 *
 * Service-local `MeterRegistry`, null-safe via [Instance] exactly like libs `DomainMetrics`: mandate
 * verification counters are AP2-specific, so adding them to the shared libs facade would force a
 * fleet-wide rebuild for a one-service concern.
 */
@ApplicationScoped
class Ap2MetricsAdapter(private val registry: MeterRegistry?) : Ap2MetricsPort {

    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. slim test slices). Without an explicit @Inject ctor, ArC sees two constructors,
    // registers no bean, and Ap2MandateVerifier is left with an unsatisfied dependency at build time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    override fun mandateVerified(
        kind: MandateKind,
        signature: MandateSignatureOutcome,
        constraintsSatisfied: Boolean,
        valid: Boolean,
        duration: Duration,
    ) {
        val r = registry ?: return
        val kindTag = kind.name
        counter(r, "openbank.ap2.mandate.verifications", kindTag, "verdict", if (valid) "valid" else "invalid")
            .increment()
        counter(r, "openbank.ap2.mandate.signature", kindTag, "outcome", signature.name.lowercase()).increment()
        counter(
            r,
            "openbank.ap2.mandate.constraints",
            kindTag,
            "outcome",
            if (constraintsSatisfied) "satisfied" else "violated",
        ).increment()
        Timer.builder("openbank.ap2.mandate.verification.duration")
            .tag("service", SERVICE)
            .tag("kind", kindTag)
            .publishPercentiles(P50, P95, P99)
            .publishPercentileHistogram()
            .description("Time to verify one AP2 mandate, both stages")
            .register(r)
            .record(duration)
    }

    override fun authorizationDecision(outcome: Ap2AuthorizationOutcome) {
        registry?.let { r ->
            Counter.builder("openbank.ap2.authorization.decisions")
                .tag("service", SERVICE)
                .tag("outcome", outcome.name.lowercase())
                .description("PDP decisions on the AP2 verify surface")
                .register(r)
                .increment()
        }
    }

    private fun counter(
        registry: MeterRegistry,
        name: String,
        kind: String,
        tagKey: String,
        tagValue: String,
    ): Counter = Counter.builder(name)
        .tag("service", SERVICE)
        .tag("kind", kind)
        .tag(tagKey, tagValue)
        .register(registry)

    companion object {
        private const val SERVICE = "ap2"

        // The fleet-standard percentile set (libs DomainMetrics publishes the same three).
        private const val P50 = 0.5
        private const val P95 = 0.95
        private const val P99 = 0.99
    }
}
