// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.authz

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * Wires the production [PolicyDecisionPoint] for party-service — the first
 * pilot adopter of ADR-0034 D5 (Phase 3). All knobs come from
 * `application.yaml` so the same image runs locally (no sidecar →
 * advisory-mode logs) and in cluster (real sidecar at `localhost:8181`).
 *
 * Test profiles override `quarkus.arc.alternative-priority` to swap in
 * [com.openbank.libs.authz.AllowAllPolicyDecisionPoint] without touching
 * this class.
 */
@ApplicationScoped
class AuthzProducer {
    @ConfigProperty(name = "opa.url", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_BASE_URL)
    lateinit var opaUrl: String

    @ConfigProperty(name = "opa.path", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_QUERY_PATH)
    lateinit var opaPath: String

    @ConfigProperty(name = "opa.timeout-ms", defaultValue = "500")
    var opaTimeoutMs: Long = 500

    @Produces
    @ApplicationScoped
    fun policyDecisionPoint(): PolicyDecisionPoint = OpaSidecarPolicyDecisionPoint(
        baseUrl = opaUrl,
        queryPath = opaPath,
        timeout = Duration.ofMillis(opaTimeoutMs),
    )
}
