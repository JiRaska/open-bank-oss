// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.infrastructure.config.CaseCoordinatorConfig
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Parsing and deny-by-default for the role-to-identity binding (#4834). The IT alongside this
 * proves the binding is actually CONSULTED over real HTTP; this proves it says the right thing,
 * including for inputs an operator can produce by mistyping a ConfigMap.
 */
class CaseCapabilityGateTest {

    private fun gateWith(bindings: String): CaseCapabilityGate {
        val group = mockk<CaseCoordinatorConfig.CaseGroup>()
        every { group.identityBindings() } returns bindings
        val config = mockk<CaseCoordinatorConfig>()
        every { config.case() } returns group
        return CaseCapabilityGate(config)
    }

    @Test
    fun `a bound role may act as the identities it is bound to, and no others`() {
        val gate = gateWith("ROLE_OPERATOR=case-coordinator,incident-triage;ROLE_VIEWER=case-coordinator")

        assertThat(gate.permitsAssertedIdentity(setOf("ROLE_OPERATOR"), "incident-triage")).isTrue()
        assertThat(gate.permitsAssertedIdentity(setOf("ROLE_VIEWER"), "incident-triage")).isFalse()
    }

    @Test
    fun `a role with no entry may assert nothing`() {
        val gate = gateWith("ROLE_OPERATOR=case-coordinator")

        assertThat(gate.permitsAssertedIdentity(setOf("ROLE_ADMIN"), "case-coordinator")).isFalse()
        assertThat(gate.permitsAssertedIdentity(emptySet(), "case-coordinator")).isFalse()
    }

    @Test
    fun `there is no wildcard - a star is an agent id like any other`() {
        // Deliberate divergence from agent-service's AgentIdentityBinding: a wildcard here would
        // restore exactly the property this closes, one config edit at a time.
        val gate = gateWith("ROLE_ADMIN=*")

        assertThat(gate.permitsAssertedIdentity(setOf("ROLE_ADMIN"), "case-coordinator")).isFalse()
    }

    @Test
    fun `a malformed or empty binding denies rather than opening up`() {
        assertThat(gateWith("").permitsAssertedIdentity(setOf("ROLE_ADMIN"), "case-coordinator")).isFalse()
        assertThat(gateWith("ROLE_ADMIN").permitsAssertedIdentity(setOf("ROLE_ADMIN"), "case-coordinator")).isFalse()
        assertThat(gateWith("ROLE_ADMIN=").permitsAssertedIdentity(setOf("ROLE_ADMIN"), "case-coordinator")).isFalse()
        assertThat(gateWith("=case-coordinator").permitsAssertedIdentity(setOf(""), "case-coordinator")).isFalse()
    }

    @Test
    fun `whitespace around a ConfigMap entry does not silently break the binding`() {
        val gate = gateWith(" ROLE_OPERATOR = case-coordinator , incident-triage ")

        assertThat(gate.permitsAssertedIdentity(setOf("ROLE_OPERATOR"), "incident-triage")).isTrue()
    }
}
