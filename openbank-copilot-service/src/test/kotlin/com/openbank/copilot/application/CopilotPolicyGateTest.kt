// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.coJustRun
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CopilotPolicyGateTest {

    private fun gate(): CopilotPolicyGate {
        val publisher = mockk<AuditEventPublisher>().also { coJustRun { it.publish(any()) } }
        return CopilotPolicyGate(publisher)
    }

    @Test
    fun `allows a whitelisted read capability`() {
        val decision = runBlocking { gate().authorize("cust-1", "get_account_balance", "account.balance.read") }

        assertThat(decision.allow).isTrue()
    }

    @Test
    fun `denies an unknown or action capability (deny-by-default)`() {
        val unknown = runBlocking { gate().authorize("cust-1", "evil", "payment.execute") }
        val missing = runBlocking { gate().authorize("cust-1", "evil", null) }

        assertThat(unknown.allow).isFalse()
        assertThat(missing.allow).isFalse()
    }
}
