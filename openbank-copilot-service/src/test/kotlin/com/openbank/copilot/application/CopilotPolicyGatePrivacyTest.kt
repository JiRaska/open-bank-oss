// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.copilot.application

import com.openbank.copilot.application.port.out.ToolPolicyDecision
import com.openbank.copilot.application.port.out.ToolPolicyPort
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class CopilotPolicyGatePrivacyTest {
    private val customer = "private-customer-marker\r\nFORGED"
    private val tool = "private-tool-marker\r\nFORGED"
    private val capability = "private-capability-marker\r\nFORGED"
    private val policyReason = "private-reason-marker\r\nFORGED"

    @Test
    fun `policy logs omit caller values while decisions and audit attribution remain intact`() {
        val events = mutableListOf<AuditEvent>()
        val publisher = object : AuditEventPublisher {
            override suspend fun publish(event: AuditEvent) {
                events += event
            }
        }
        val opaGate = mockk<ToolPolicyPort>()
        every { opaGate.authorize(any(), any(), any()) } returns ToolPolicyDecision.denied(policyReason)
        val enforce = CopilotPolicyGate(publisher, opaGate, true)
        val advisory = CopilotPolicyGate(publisher, opaGate, false)
        val logs = mutableListOf<LogRecord>()

        capture(logs) {
            val whitelistDeny = runBlocking { enforce.authorize(customer, tool, capability) }
            assertThat(whitelistDeny).isEqualTo(
                CopilotPolicyGate.Decision(false, "not-permitted (deny-by-default)"),
            )

            val enforcedDeny = runBlocking { enforce.authorize(customer, tool, "account.balance.read") }
            assertThat(enforcedDeny).isEqualTo(CopilotPolicyGate.Decision(false, policyReason))

            val advisoryAllow = runBlocking { advisory.authorize(customer, tool, "account.balance.read") }
            assertThat(advisoryAllow).isEqualTo(
                CopilotPolicyGate.Decision(true, "read-whitelist; opa=deny"),
            )
        }

        assertThat(logs).hasSize(3)
        assertThat(logs.map { it.level }).containsOnly(Level.WARNING)
        val logText = logs.joinToString("\n") { record ->
            val parameters = record.parameters
            val message = if (parameters.isNullOrEmpty()) {
                record.message.orEmpty()
            } else {
                runCatching { String.format(record.message, *parameters) }.getOrDefault(record.message.orEmpty())
            }
            "$message ${parameters?.joinToString().orEmpty()}"
        }
        assertThat(logText).doesNotContain(
            "private-customer-marker",
            "private-tool-marker",
            "private-capability-marker",
            "private-reason-marker",
            "FORGED",
        )
        assertThat(logText).contains(
            "policy denied by capability whitelist",
            "OPA policy denied tool authorization (enforce mode)",
            "OPA policy denied tool authorization; allowing in advisory mode",
        )

        assertThat(events).hasSize(3)
        assertThat(events[0].actorId).isEqualTo(customer)
        assertThat(events[0].resourceId).isEqualTo(tool)
        assertThat(events[0].payload).containsEntry("capability", capability)
        assertThat(events[0].payload).containsEntry("reason", "not-permitted (deny-by-default)")
        assertThat(events[1].actorId).isEqualTo(customer)
        assertThat(events[1].resourceId).isEqualTo(tool)
        assertThat(events[1].payload).containsEntry("reason", policyReason)
        assertThat(events[2].actorId).isEqualTo(customer)
        assertThat(events[2].resourceId).isEqualTo(tool)
        assertThat(events[2].payload).containsEntry("reason", "read-whitelist; opa=deny")
    }

    private fun capture(records: MutableList<LogRecord>, test: () -> Unit) {
        val logger = Logger.getLogger(CopilotPolicyGate::class.java.name)
        val previousLevel = logger.level
        val previousParent = logger.useParentHandlers
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.level = Level.ALL
        logger.useParentHandlers = false
        logger.addHandler(handler)
        try {
            test()
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
            logger.useParentHandlers = previousParent
        }
    }
}
