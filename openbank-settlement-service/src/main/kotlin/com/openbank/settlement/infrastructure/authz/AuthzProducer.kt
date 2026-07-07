// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.authz

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

@ApplicationScoped
class AuthzProducer {
    @ConfigProperty(name = "opa.url", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_BASE_URL)
    lateinit var opaUrl: String

    @ConfigProperty(name = "opa.path", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_QUERY_PATH)
    lateinit var opaPath: String

    @ConfigProperty(name = "opa.timeout-ms", defaultValue = DEFAULT_TIMEOUT_MS_STR)
    var opaTimeoutMs: Long = DEFAULT_TIMEOUT_MS

    @Produces
    @ApplicationScoped
    fun policyDecisionPoint(): PolicyDecisionPoint = OpaSidecarPolicyDecisionPoint(
        baseUrl = opaUrl,
        queryPath = opaPath,
        timeout = Duration.ofMillis(opaTimeoutMs),
    )

    companion object {
        // Mirrors OpaSidecarPolicyDecisionPoint.DEFAULT_TIMEOUT (500ms); kept as a
        // named long here too since @ConfigProperty needs a compile-time default and
        // a plain Duration constant can't be used as an annotation default value.
        private const val DEFAULT_TIMEOUT_MS: Long = 500
        private const val DEFAULT_TIMEOUT_MS_STR: String = "500"
    }
}
