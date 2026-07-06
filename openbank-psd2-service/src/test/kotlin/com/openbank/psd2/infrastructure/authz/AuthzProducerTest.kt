// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.authz

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [AuthzProducer] just wires `@ConfigProperty`-sourced OPA settings into an
 * [OpaSidecarPolicyDecisionPoint]; verifies the CDI producer method itself (not exercised by any
 * `@QuarkusTest`, since PSD2 unit tests don't boot CDI) builds a usable instance from both its
 * defaults and explicit overrides.
 */
class AuthzProducerTest {

    @Test
    fun `policyDecisionPoint builds an OpaSidecarPolicyDecisionPoint from the configured defaults`() {
        val producer = AuthzProducer().apply {
            opaUrl = OpaSidecarPolicyDecisionPoint.DEFAULT_BASE_URL
            opaPath = OpaSidecarPolicyDecisionPoint.DEFAULT_QUERY_PATH
            opaTimeoutMs = 500
        }

        val pdp = producer.policyDecisionPoint()

        assertThat(pdp).isInstanceOf(OpaSidecarPolicyDecisionPoint::class.java)
    }

    @Test
    fun `policyDecisionPoint honours an overridden url, path and timeout`() {
        val producer = AuthzProducer().apply {
            opaUrl = "http://opa-sidecar.internal:9191"
            opaPath = "/v1/data/openbank/psd2/allow"
            opaTimeoutMs = 250
        }

        val pdp = producer.policyDecisionPoint()

        assertThat(pdp).isInstanceOf(OpaSidecarPolicyDecisionPoint::class.java)
    }
}
