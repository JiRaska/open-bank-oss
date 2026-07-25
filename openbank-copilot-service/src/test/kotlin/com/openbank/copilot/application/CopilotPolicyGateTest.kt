// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

import com.openbank.copilot.application.port.out.ToolPolicyDecision
import com.openbank.copilot.application.port.out.ToolPolicyPort
import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CopilotPolicyGateTest {

    private fun gate(opaEnforce: Boolean = false, opaAllow: Boolean = true): CopilotPolicyGate {
        val publisher = mockk<AuditEventPublisher>().also { coJustRun { it.publish(any()) } }
        val opaGate = mockk<ToolPolicyPort>().also {
            // The port is fail-closed and never throws: a deny — policy, unreachable sidecar or
            // unparseable body alike — arrives as a decision the gate must honour.
            every { it.authorize(any(), any(), any()) } returns if (opaAllow) {
                ToolPolicyDecision.ALLOWED
            } else {
                ToolPolicyDecision.denied("opa-denied: policy")
            }
        }
        return CopilotPolicyGate(publisher, opaGate, opaEnforce)
    }

    @Test
    fun `allows a whitelisted read capability (advisory OPA)`() {
        val decision = runBlocking {
            gate(opaEnforce = false).authorize("cust-1", "get_account_balance", "account.balance.read")
        }

        assertThat(decision.allow).isTrue()
        assertThat(decision.reason).contains("read-whitelist")
    }

    @Test
    fun `allows scheduled-payments with its own capability`() {
        val decision = runBlocking {
            gate().authorize("cust-1", "get_scheduled_payments", "account.scheduled-payments.read")
        }

        assertThat(decision.allow).isTrue()
    }

    @Test
    fun `denies an unknown or action capability (deny-by-default)`() {
        val unknown = runBlocking { gate().authorize("cust-1", "evil", "payment.execute") }
        val missing = runBlocking { gate().authorize("cust-1", "evil", null) }

        assertThat(unknown.allow).isFalse()
        assertThat(missing.allow).isFalse()
    }

    @Test
    fun `allows action-whitelist capability in propose-only mode`() {
        val decision = runBlocking {
            gate().authorize("cust-1", "propose_payment", "payment.propose")
        }

        assertThat(decision.allow).isTrue()
        assertThat(decision.reason).contains("action-whitelist")
    }

    // OPA advisory mode: OPA says deny but gate still allows through (issue #998 staged rollout).
    @Test
    fun `OPA deny in advisory mode is logged but does not block (allow-through)`() {
        val decision = runBlocking {
            gate(opaEnforce = false, opaAllow = false)
                .authorize("cust-1", "get_my_balances", "account.balance.read")
        }

        assertThat(decision.allow).isTrue()
    }

    // OPA enforce mode: OPA deny blocks the tool call.
    @Test
    fun `OPA deny in enforce mode blocks the tool call`() {
        val decision = runBlocking {
            gate(opaEnforce = true, opaAllow = false)
                .authorize("cust-1", "get_my_balances", "account.balance.read")
        }

        assertThat(decision.allow).isFalse()
        assertThat(decision.reason).contains("opa-denied")
    }

    // Whitelist deny short-circuits before OPA regardless of enforce mode.
    @Test
    fun `whitelist deny short-circuits before OPA is consulted`() {
        // Even if OPA would allow, a non-whitelisted capability must be denied.
        val decision = runBlocking {
            gate(opaEnforce = true, opaAllow = true).authorize("cust-1", "evil", "payment.execute")
        }

        assertThat(decision.allow).isFalse()
        assertThat(decision.reason).contains("not-permitted")
    }
}
