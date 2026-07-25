// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import com.openbank.agent.application.port.out.KillSwitchRepository
import com.openbank.agent.domain.control.HaltStatus
import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

class KillSwitchServiceTest {

    private val audit = mockk<AuditEventPublisher>().also { coEvery { it.publish(any()) } returns Unit }

    /**
     * A [KillSwitchRepository] that reports a halt only for the scopes in [haltedScopes].
     * The service's own job is precedence — a runtime halt beats the config baseline — so the store
     * is a plain stub here; the SQL this service used to inline now has its own adapter.
     */
    private fun repository(vararg haltedScopes: String): KillSwitchRepository = mockk {
        every { findHalt(any()) } answers {
            val scope = firstArg<String>()
            if (scope in haltedScopes) {
                HaltStatus(scope = scope, reason = "halted", setBy = "operator", setAt = Instant.now())
            } else {
                null
            }
        }
    }

    private fun service(
        repository: KillSwitchRepository,
        globalEnabled: Boolean = true,
        agentEnabled: Boolean = true,
    ): KillSwitchService {
        val charters = mockk<CharterRegistry> { every { isEnabled(any()) } returns agentEnabled }
        return KillSwitchService(repository, audit, Clock.systemUTC()).also {
            it.charters = charters
            it.globalEnabled = globalEnabled
        }
    }

    @Test
    fun `enabled agent with no runtime halt may run`() {
        assertThat(service(repository()).haltReason("ui-assistant")).isNull()
    }

    @Test
    fun `global config baseline off halts every agent`() {
        assertThat(service(repository(), globalEnabled = false).haltReason("ui-assistant"))
            .contains("all agents are disabled")
    }

    @Test
    fun `per-agent config baseline off halts that agent`() {
        assertThat(service(repository(), agentEnabled = false).haltReason("ui-assistant"))
            .contains("agent 'ui-assistant' is disabled")
    }

    @Test
    fun `runtime global halt wins over an otherwise-enabled baseline`() {
        // Even with both config baselines enabled, a runtime '*' halt stops the agent.
        assertThat(service(repository("*")).haltReason("ui-assistant"))
            .contains("all agents are halted")
    }

    @Test
    fun `runtime per-agent halt stops only that agent`() {
        val svc = service(repository("compliance-officer"))
        assertThat(svc.haltReason("compliance-officer")).contains("is halted")
        assertThat(svc.haltReason("ui-assistant")).isNull()
    }
}
