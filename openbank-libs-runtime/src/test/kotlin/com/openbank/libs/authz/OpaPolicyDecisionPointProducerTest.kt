// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import io.quarkus.arc.DefaultBean
import io.quarkus.arc.properties.IfBuildProperty
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.time.Duration

class OpaPolicyDecisionPointProducerTest {

    @Test
    fun `defaults match the per-service AuthzProducer copies it replaces`() {
        fun default(field: String) = OpaPolicyDecisionPointProducer::class.java.getDeclaredField(field)
            .getAnnotation(ConfigProperty::class.java)
        assertThat(default("opaUrl").name).isEqualTo("opa.url")
        assertThat(default("opaUrl").defaultValue).isEqualTo("http://localhost:8181")
        assertThat(default("opaPath").name).isEqualTo("opa.path")
        assertThat(default("opaPath").defaultValue).isEqualTo("/v1/data/openbank/rest/allow")
        assertThat(default("opaTimeoutMs").name).isEqualTo("opa.timeout-ms")
        assertThat(default("opaTimeoutMs").defaultValue).isEqualTo("500")
        assertThat(Duration.ofMillis(default("opaTimeoutMs").defaultValue.toLong()))
            .isEqualTo(OpaSidecarPolicyDecisionPoint.DEFAULT_TIMEOUT)
    }

    @Test
    fun `producer is a displaceable default and opt-in per service`() {
        val method = OpaPolicyDecisionPointProducer::class.java.getDeclaredMethod("policyDecisionPoint")
        assertThat(method.isAnnotationPresent(DefaultBean::class.java)).isTrue()
        val gate = method.getAnnotation(IfBuildProperty::class.java)
        assertThat(gate.name).isEqualTo(OpaPolicyDecisionPointProducer.ENABLED_PROPERTY)
        assertThat(gate.stringValue).isEqualTo("true")
        assertThat(gate.enableIfMissing).isFalse()
    }

    @Test
    fun `builds an OPA sidecar PDP from overridden config`() {
        val producer = OpaPolicyDecisionPointProducer().apply {
            opaUrl = "http://opa.example:9191"
            opaPath = "/v1/data/openbank/psd2/allow"
            opaTimeoutMs = 250L as java.lang.Long
        }
        assertThat(producer.policyDecisionPoint()).isInstanceOf(OpaSidecarPolicyDecisionPoint::class.java)
    }
}
