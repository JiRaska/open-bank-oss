// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.authz

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthzProducerTest {

    @Test
    fun `produces an OPA sidecar policy decision point from the configured coordinates`() {
        val producer = AuthzProducer()
        producer.opaUrl = "http://localhost:8181"
        producer.opaPath = "/v1/data/openbank/rest/allow"
        producer.opaTimeoutMs = 500

        val pdp = producer.policyDecisionPoint()

        // Fail-closed production PDP (ADR-0018/0034) — never an allow-all fallback.
        assertThat(pdp).isInstanceOf(OpaSidecarPolicyDecisionPoint::class.java)
    }
}
