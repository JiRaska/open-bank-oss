// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.application.port.out

import com.openbank.ap2.domain.MandateKind
import java.time.Duration

/**
 * Outbound observability port (ADR-0002 / ADR-0077 Tier C) for AP2 mandate verification (ADR-0193).
 *
 * ap2-service answers "is this agent-presented mandate valid, and what does it authorize?" It moves
 * no funds — so it has no downstream that notices when it starts answering wrongly, and every one of
 * its failure modes is a well-formed `invalid` verdict:
 *
 *  - **`issuer_not_trusted` is the one that matters.** A rotated or mis-seeded trust list makes the
 *    resolver return null for a legitimate issuer, and *every* mandate from that issuer then fails
 *    closed. That is the correct behaviour and it is indistinguishable, from the response, from an
 *    attacker presenting a forged issuer. Only the rate tells them apart.
 *  - **`pdp_unavailable`.** The endpoint calls the shared PDP directly rather than through the
 *    `@Authorize` interceptor, so it emits no `openbank_authz_decisions_total`. A PDP outage denies
 *    every agent (correctly, fail-closed) and previously left nothing but a WARN line.
 *
 * Kept as a port rather than a direct `MeterRegistry` dependency so the application layer stays free
 * of the metrics framework, and so the counters are exercised through the real adapter (over a
 * `SimpleMeterRegistry`) in unit tests.
 *
 * Implemented by [com.openbank.ap2.infrastructure.observability.Ap2MetricsAdapter].
 */
interface Ap2MetricsPort {

    /**
     * Record one completed verification. Both stages are reported separately because they fail for
     * completely different reasons: a signature stage failure is a key/trust problem (ours), a
     * constraint failure is the presented payment being outside the mandate's authority (theirs).
     */
    fun mandateVerified(
        kind: MandateKind,
        signature: MandateSignatureOutcome,
        constraintsSatisfied: Boolean,
        valid: Boolean,
        duration: Duration,
    )

    /** Record one authorization decision on the verify surface. */
    fun authorizationDecision(outcome: Ap2AuthorizationOutcome)
}

/** How the signature stage resolved. A bounded set — safe as a tag. */
enum class MandateSignatureOutcome {
    /** Anchored to a trusted issuer key and the signature verified. */
    VALID,

    /** The issuer resolved to no trusted key. Sustained non-zero is a trust-list defect, not an attack. */
    ISSUER_NOT_TRUSTED,

    /** A trusted key was found and the signature did not verify against it. */
    INVALID,

    /** A malformed key or signature made verification throw. Treated as a failure, never propagated. */
    VERIFICATION_ERROR,
}

/** Outcome of the PDP gate on `POST /ap2/verify`. A bounded set — safe as a tag. */
enum class Ap2AuthorizationOutcome {
    /** OPA allowed the agent to verify. */
    ALLOWED,

    /** OPA denied. */
    DENIED,

    /** The PDP could not be reached, so the call was denied fail-closed (503). */
    PDP_UNAVAILABLE,
}
