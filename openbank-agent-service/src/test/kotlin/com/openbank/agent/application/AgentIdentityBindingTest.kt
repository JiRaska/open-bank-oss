// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.agent.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0031 D3: the role→agent binding is the security control that stops a lower-privileged
 * operator from asserting a higher-privileged charter via X-Agent-Id. Deny-by-default is the
 * load-bearing property here.
 */
class AgentIdentityBindingTest {

    private fun binding(raw: String, enforced: Boolean = true) = AgentIdentityBinding(enforced, raw)

    @Test
    fun `permits only the exact bound agent for a role`() {
        val b = binding("ROLE_OPERATOR=ui-assistant")
        assertThat(b.permits(setOf("ROLE_OPERATOR"), "ui-assistant")).isTrue()
        assertThat(b.permits(setOf("ROLE_OPERATOR"), "compliance-officer")).isFalse()
    }

    @Test
    fun `deny-by-default for an unlisted role or no roles`() {
        val b = binding("ROLE_OPERATOR=ui-assistant")
        assertThat(b.permits(setOf("ROLE_GUEST"), "ui-assistant")).isFalse()
        assertThat(b.permits(emptySet(), "ui-assistant")).isFalse()
    }

    @Test
    fun `wildcard role may assert any agent (break-glass admin)`() {
        val b = binding("ROLE_ADMIN=*")
        assertThat(b.permits(setOf("ROLE_ADMIN"), "compliance-officer")).isTrue()
        assertThat(b.permits(setOf("ROLE_ADMIN"), "anything-new")).isTrue()
    }

    @Test
    fun `binding is a union over the caller's roles`() {
        val b = binding("ROLE_OPERATOR=ui-assistant;ROLE_COMPLIANCE=compliance-officer")
        assertThat(b.permits(setOf("ROLE_OPERATOR", "ROLE_COMPLIANCE"), "compliance-officer")).isTrue()
        assertThat(b.permits(setOf("ROLE_OPERATOR", "ROLE_COMPLIANCE"), "ui-assistant")).isTrue()
    }

    @Test
    fun `permittedAgents returns the explicit set, null for wildcard, empty for unbound`() {
        val b = binding("ROLE_OPERATOR=ui-assistant,extra;ROLE_ADMIN=*")
        assertThat(b.permittedAgents(setOf("ROLE_OPERATOR"))).containsExactlyInAnyOrder("ui-assistant", "extra")
        assertThat(b.permittedAgents(setOf("ROLE_ADMIN"))).isNull()
        assertThat(b.permittedAgents(setOf("ROLE_GUEST"))).isEmpty()
    }

    @Test
    fun `parser tolerates whitespace and drops malformed entries`() {
        val b = binding(" ROLE_OPERATOR = ui-assistant , extra ; garbage ; =orphan ; ROLE_EMPTY= ")
        assertThat(b.permits(setOf("ROLE_OPERATOR"), "ui-assistant")).isTrue()
        assertThat(b.permits(setOf("ROLE_OPERATOR"), "extra")).isTrue()
        assertThat(b.permits(setOf("ROLE_EMPTY"), "orphan")).isFalse()
    }
}
