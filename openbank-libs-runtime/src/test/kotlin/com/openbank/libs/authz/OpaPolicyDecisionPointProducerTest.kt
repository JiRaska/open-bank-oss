// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The config surface of [OpaPolicyDecisionPointProducer]: the three `lateinit` config fields fail
 * fast when read before CDI injection, and read back the injected values after — pitest flagged
 * every one of these accessors as uncovered because the producer is only ever exercised inside
 * ArC, where injection happens before any test could observe the unset state.
 */
class OpaPolicyDecisionPointProducerTest {

    @Test
    fun `lateinit config fields fail fast before injection and round-trip after`() {
        val producer = OpaPolicyDecisionPointProducer()
        assertThatThrownBy { producer.opaUrl }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { producer.opaPath }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { producer.opaTimeoutMs }.isInstanceOf(UninitializedPropertyAccessException::class.java)

        producer.opaUrl = "http://opa:8181"
        producer.opaPath = "/v1/data/openbank/authz/allow"
        producer.opaTimeoutMs = 750L as java.lang.Long
        assertThat(producer.opaUrl).isEqualTo("http://opa:8181")
        assertThat(producer.opaPath).isEqualTo("/v1/data/openbank/authz/allow")
        assertThat(producer.opaTimeoutMs).isEqualTo(750L)
    }
}
