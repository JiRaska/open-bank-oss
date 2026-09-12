// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class SecurityTelemetryTest {

    private fun withRegistry(reg: MeterRegistry?): SecurityTelemetry {
        val inst = mockk<Instance<MeterRegistry>>()
        if (reg == null) {
            every { inst.isResolvable } returns false
        } else {
            every { inst.isResolvable } returns true
            every { inst.get() } returns reg
        }
        return SecurityTelemetry().apply {
            registryInstance = inst
            serviceName = "openbank-test-service"
        }
    }

    @Test
    fun `authz decisions are counted with low-cardinality tags`() {
        val reg = SimpleMeterRegistry()
        val telemetry = withRegistry(reg)

        telemetry.recordAuthorizationDecision(SecurityTelemetry.AuthzDecision.DENY, "role-missing", true)
        telemetry.recordAuthorizationDecision(SecurityTelemetry.AuthzDecision.DENY, "role-missing", true)
        telemetry.recordAuthorizationDecision(SecurityTelemetry.AuthzDecision.ALLOW, "role-present", true)

        val denied = reg.find(SecurityTelemetry.AUTHZ_DECISIONS)
            .tags("service", "openbank-test-service", "decision", "deny", "reason", "role-missing", "enforced", "true")
            .counter()
        val allowed = reg.find(SecurityTelemetry.AUTHZ_DECISIONS)
            .tags("service", "openbank-test-service", "decision", "allow", "reason", "role-present", "enforced", "true")
            .counter()
        assertThat(denied).isNotNull
        assertThat(denied!!.count()).isEqualTo(2.0)
        assertThat(allowed).isNotNull
        assertThat(allowed!!.count()).isEqualTo(1.0)
    }

    @Test
    fun `an advisory deny is a separate series from an enforced one`() {
        // AuthzDenyRatioElevated scopes to enforced="true". If both modes shared one series the
        // alert could not tell a service acting on its denials from one merely measuring them,
        // and an ADR-0034 D5 advisory pilot would page as an enumeration attack.
        val reg = SimpleMeterRegistry()
        val telemetry = withRegistry(reg)

        telemetry.recordAuthorizationDecision(SecurityTelemetry.AuthzDecision.DENY, "role-missing", false)
        telemetry.recordAuthorizationDecision(SecurityTelemetry.AuthzDecision.DENY, "role-missing", true)

        val advisory = reg.find(SecurityTelemetry.AUTHZ_DECISIONS)
            .tags("decision", "deny", "enforced", "false").counter()
        val enforced = reg.find(SecurityTelemetry.AUTHZ_DECISIONS)
            .tags("decision", "deny", "enforced", "true").counter()
        assertThat(advisory).isNotNull
        assertThat(advisory!!.count()).isEqualTo(1.0)
        assertThat(enforced).isNotNull
        assertThat(enforced!!.count()).isEqualTo(1.0)
    }

    @Test
    fun `no micrometer registry means a silent no-op, never an exception`() {
        val telemetry = withRegistry(null)

        assertThatCode {
            telemetry.recordAuthorizationDecision(SecurityTelemetry.AuthzDecision.DENY, "delegation-expired", true)
            telemetry.recordDelegationDepth(3)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `span attribute writes are best-effort and never throw, with or without OTel`() {
        assertThatCode {
            OtelSpanAttributeWriter.set("openbank.authz.decision", "deny")
            OtelSpanAttributeWriter.setLong("openbank.delegation.depth", 2L)
        }.doesNotThrowAnyException()
    }
}
