// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

class KillSwitchServiceTest {

    private val audit = mockk<AuditEventPublisher>().also { coEvery { it.publish(any()) } returns Unit }

    /** A DataSource whose halt query returns a row only for the scopes in [haltedScopes]. */
    private fun dataSource(vararg haltedScopes: String): DataSource {
        val ds = mockk<DataSource>()
        val conn = mockk<Connection>(relaxed = true)
        every { ds.connection } returns conn
        every { conn.prepareStatement(any()) } answers {
            val sql = firstArg<String>()
            val ps = mockk<PreparedStatement>(relaxed = true)
            var boundScope = ""
            every { ps.setString(1, any()) } answers { boundScope = secondArg() }
            val rs = mockk<ResultSet>(relaxed = true)
            // runtimeHalt() filters by scope = ?; return a row only when that scope is halted.
            every { ps.executeQuery() } answers {
                val hit = sql.contains("scope = ?") && boundScope in haltedScopes
                every { rs.next() } returnsMany listOf(hit, false)
                every { rs.getString("scope") } returns boundScope
                every { rs.getString("reason") } returns "halted"
                every { rs.getString("set_by") } returns "operator"
                every { rs.getTimestamp(any<String>()) } returns Timestamp.from(Instant.now())
                rs
            }
            ps
        }
        return ds
    }

    private fun service(
        ds: DataSource,
        globalEnabled: Boolean = true,
        agentEnabled: Boolean = true,
    ): KillSwitchService {
        val charters = mockk<CharterRegistry> { every { isEnabled(any()) } returns agentEnabled }
        return KillSwitchService(ds, audit, Clock.systemUTC()).also {
            it.charters = charters
            it.globalEnabled = globalEnabled
        }
    }

    @Test
    fun `enabled agent with no runtime halt may run`() {
        assertThat(service(dataSource()).haltReason("ui-assistant")).isNull()
    }

    @Test
    fun `global config baseline off halts every agent`() {
        assertThat(service(dataSource(), globalEnabled = false).haltReason("ui-assistant"))
            .contains("all agents are disabled")
    }

    @Test
    fun `per-agent config baseline off halts that agent`() {
        assertThat(service(dataSource(), agentEnabled = false).haltReason("ui-assistant"))
            .contains("agent 'ui-assistant' is disabled")
    }

    @Test
    fun `runtime global halt wins over an otherwise-enabled baseline`() {
        // Even with both config baselines enabled, a runtime '*' halt stops the agent.
        assertThat(service(dataSource("*")).haltReason("ui-assistant"))
            .contains("all agents are halted")
    }

    @Test
    fun `runtime per-agent halt stops only that agent`() {
        val svc = service(dataSource("compliance-officer"))
        assertThat(svc.haltReason("compliance-officer")).contains("is halted")
        assertThat(svc.haltReason("ui-assistant")).isNull()
    }
}
