// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.casecoordinator.application.port.out.CaseKillSwitchStatePort
import com.openbank.casecoordinator.domain.model.CaseClass
import com.openbank.casecoordinator.domain.model.CaseDeliveryMode
import com.openbank.casecoordinator.domain.model.CaseStart
import com.openbank.casecoordinator.infrastructure.config.CaseCoordinatorConfig
import com.openbank.libs.temporal.TemporalConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowServiceException
import io.temporal.client.WorkflowStub
import io.temporal.serviceclient.WorkflowServiceStubs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JulLogger

/**
 * Case-open authority rules (ADR-0244 D9): capability, per-agent rate limit, dedup mapping.
 * What is tested is the service's OWN logic: who is denied, when the quota bites, how Temporal's
 * already-started answer maps to Duplicate. The server-side dedup guarantee itself needs a real
 * Temporal server and is carried by the workflow-id reuse policy, not by this test.
 */
class CaseOpenServiceTest {

    private val workflowClient = mockk<WorkflowClient>()
    private val temporalConfig = mockk<TemporalConfig>()
    private val gate = mockk<CaseCapabilityGate>()
    private val config = mockk<CaseCoordinatorConfig>()
    private val caseGroup = mockk<CaseCoordinatorConfig.CaseGroup>()
    private val clock = Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC)
    private val untyped = mockk<WorkflowStub>()
    private val killSwitchState = mockk<CaseKillSwitchStatePort>()

    private lateinit var service: CaseOpenService

    companion object {
        private const val COORDINATOR = "case-coordinator"
        private const val PRINCIPAL = "svc-operator-1"
    }

    @BeforeEach
    fun setUp() {
        every { temporalConfig.enabled() } returns true
        every { temporalConfig.taskQueue() } returns "case-coordinator"
        every { gate.canOpenCase(COORDINATOR) } returns true
        every { config.case() } returns caseGroup
        every { caseGroup.enabledClasses() } returns setOf(CaseClass.INCIDENT_RESPONSE)
        every { caseGroup.maxOpensPerAgentPerHour() } returns 1
        every { caseGroup.maxConcurrent() } returns 15
        every { caseGroup.ttl() } returns java.time.Duration.ofMinutes(20)
        every { caseGroup.contestedRateThreshold() } returns 0.35
        every { caseGroup.maxContributions() } returns 40
        every { caseGroup.deliveryMode() } returns CaseDeliveryMode.HITL
        every { killSwitchState.pilotHaltReason() } returns null
        every { workflowClient.options } returns WorkflowClientOptions.newBuilder().setNamespace("openbank").build()
        every { workflowClient.newUntypedWorkflowStub("CaseWorkflow", any()) } returns untyped
        every { untyped.start(any()) } returns WorkflowExecution.getDefaultInstance()
        val stubs = mockk<WorkflowServiceStubs>()
        val blocking = mockk<WorkflowServiceGrpc.WorkflowServiceBlockingStub>()
        every { workflowClient.workflowServiceStubs } returns stubs
        every { stubs.blockingStub() } returns blocking
        every { blocking.listOpenWorkflowExecutions(any()) } returns
            ListOpenWorkflowExecutionsResponse.getDefaultInstance()
        service = CaseOpenService(workflowClient, temporalConfig, gate, config, clock, killSwitchState)
    }

    private fun open(
        agent: String = COORDINATOR,
        subject: String = "ingest-1",
        principal: String = PRINCIPAL,
    ): CaseOpenResult = service.open(principal, agent, CaseClass.INCIDENT_RESPONSE, subject, "hitl-incident-queue")

    @Test
    fun `temporal disabled means unavailable without touching the gate`() {
        every { temporalConfig.enabled() } returns false

        assertThat(open()).isEqualTo(CaseOpenResult.Unavailable)
    }

    @Test
    fun `active pilot kill switch denies before consuming quota or touching Temporal`() {
        every { killSwitchState.pilotHaltReason() } returns "kill switch *: emergency"

        assertThat(open()).isEqualTo(CaseOpenResult.Denied)
        verify(exactly = 0) { untyped.start(any()) }
    }

    @Test
    fun `an agent without case-open capability is denied`() {
        every { gate.canOpenCase("fraud-analyst") } returns false

        assertThat(open(agent = "fraud-analyst")).isEqualTo(CaseOpenResult.Denied)
    }

    @Test
    fun `a disabled case class is denied even for the coordinator`() {
        assertThat(
            service.open(PRINCIPAL, COORDINATOR, CaseClass.FRAUD_INVESTIGATION, "case-7", "hitl-fraud-queue"),
        ).isEqualTo(CaseOpenResult.Denied)
    }

    @Test
    fun `shadow mode denies money-path case classes before a workflow can start`() {
        every { caseGroup.deliveryMode() } returns CaseDeliveryMode.SHADOW
        every { caseGroup.enabledClasses() } returns setOf(CaseClass.INCIDENT_RESPONSE, CaseClass.FRAUD_INVESTIGATION)

        assertThat(
            service.open(PRINCIPAL, COORDINATOR, CaseClass.FRAUD_INVESTIGATION, "case-7", "hitl-fraud-queue"),
        ).isEqualTo(CaseOpenResult.Denied)
        io.mockk.verify(exactly = 0) {
            workflowClient.newUntypedWorkflowStub(any<String>(), any<WorkflowOptions>())
        }
    }

    @Test
    fun `the second open within the hour is rate limited`() {
        assertThat(open()).isInstanceOf(CaseOpenResult.Opened::class.java)

        assertThat(open(subject = "ingest-2")).isEqualTo(CaseOpenResult.RateLimited)
    }

    /**
     * #4834: the open-rate quota must key on the AUTHENTICATED caller, not on the agent identity
     * the request claims. Keyed on the claim, a caller splits its own ceiling into one bucket per
     * string it invents.
     *
     * Driven through two DIFFERENT allow-listed agent ids from ONE principal: under the old
     * keying those are two buckets and both opens succeed; under principal keying the second is
     * rate-limited. `maxOpensPerAgentPerHour` is 1 in this fixture.
     */
    @Test
    fun `the quota is keyed on the caller, not on the agent identity it claims`() {
        every { gate.canOpenCase(any()) } returns true

        assertThat(open(agent = "case-coordinator", subject = "a")).isInstanceOf(CaseOpenResult.Opened::class.java)
        assertThat(open(agent = "some-other-agent", subject = "b"))
            .describedAs("a second open from the SAME principal must not get a fresh bucket by renaming the agent")
            .isEqualTo(CaseOpenResult.RateLimited)
    }

    /** The flip side: two genuinely different callers keep their own ceilings. */
    @Test
    fun `separate principals keep separate quotas`() {
        every { gate.canOpenCase(any()) } returns true

        assertThat(open(subject = "a", principal = "svc-operator-1"))
            .isInstanceOf(CaseOpenResult.Opened::class.java)
        assertThat(open(subject = "b", principal = "svc-operator-2"))
            .describedAs("a different authenticated caller has its own ceiling")
            .isInstanceOf(CaseOpenResult.Opened::class.java)
    }

    @Test
    fun `an already-running case for the same subject maps to duplicate`() {
        every { untyped.start(any()) } throws WorkflowExecutionAlreadyStarted(
            WorkflowExecution.getDefaultInstance(),
            "CaseWorkflow",
            null,
        )

        assertThat(open()).isEqualTo(CaseOpenResult.Duplicate)
    }

    /**
     * #4215: `openedBy` is free-form request-body input and was interpolated into the "case opened"
     * line verbatim, so a caller could embed CRLF and forge log records. CodeQL flagged it and the
     * finding was merged past unresolved.
     *
     * The assertion reads the REAL emitted record rather than calling the sanitiser directly — a
     * test of the helper alone would stay green if the call site stopped using it, which is exactly
     * the regression worth catching.
     */
    @Test
    fun `a CRLF-laden openedBy cannot forge a log line`() {
        val captured = mutableListOf<String>()
        val jul = JulLogger.getLogger(CaseOpenService::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                captured += java.text.MessageFormat.format(record.message, *(record.parameters ?: emptyArray()))
                    .let { if (record.parameters == null) record.message else it }
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        jul.addHandler(handler)
        jul.level = Level.ALL
        try {
            every { gate.canOpenCase(any()) } returns true
            service.open(PRINCIPAL, "evil\r\nFATAL: forged entry", CaseClass.INCIDENT_RESPONSE, "acct-1", "ops")
        } finally {
            jul.removeHandler(handler)
        }

        val all = captured.joinToString("\n")
        assertThat(all).describedAs("something must have been logged, or this test proves nothing")
            .contains("case opened")
        assertThat(all).describedAs("CR/LF from request input must not reach the log verbatim")
            .doesNotContain("\r").doesNotContain("evil\nFATAL")
    }

    @Test
    fun `logged caller fields round trip without rewriting the workflow payload`() {
        val records = mutableListOf<LogRecord>()
        val logger = JulLogger.getLogger(CaseOpenService::class.java.name)
        val previousLevel = logger.level
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val claimed = "agent\r\ncaller=forged \"quote\" \\ route"
        val principal = "operator\u0085verified\u2028name\u001b[31m"
        every { gate.canOpenCase(claimed) } returns true
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            assertThat(open(agent = claimed, subject = "subject-633", principal = principal))
                .isEqualTo(CaseOpenResult.Opened(service.workflowIdFor(CaseClass.INCIDENT_RESPONSE, "subject-633")))
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
        }
        verify(exactly = 1) {
            untyped.start(match<CaseStart> { it.openedBy == claimed && it.subjectRef == "subject-633" })
        }
        val record = records.single { it.message.startsWith("case opened:") }
        val output = record.parameters?.let { String.format(record.message, *it) } ?: record.message
        assertThat(output.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }).isTrue()
        assertThat(output).doesNotContain("subject-633")
        val fields = Regex(
            """case opened: [a-f0-9]{64} claimed-by ("(?:[^"\\]|\\.)*") principal ("(?:[^"\\]|\\.)*") \(class INCIDENT_RESPONSE\)""",
        ).matchEntire(output)
        assertThat(fields).describedAs("one emitted line must contain exactly two quoted caller fields").isNotNull()
        val parsed = requireNotNull(fields)
        val mapper = ObjectMapper()
        assertThat(mapper.readValue(parsed.groupValues[1], String::class.java)).isEqualTo(claimed)
        assertThat(mapper.readValue(parsed.groupValues[2], String::class.java)).isEqualTo(principal)
    }

    @Test
    fun `opened case logs correlation without subject and retains caller attribution`() {
        val workflowId = service.workflowIdFor(CaseClass.INCIDENT_RESPONSE, "private-subject-460")
        assertPrivateOutcome(CaseOpenResult.Opened(workflowId))
    }

    @Test
    fun `duplicate case logs neither subject nor throwable payload`() {
        every { untyped.start(any()) } throws WorkflowExecutionAlreadyStarted(
            WorkflowExecution.newBuilder().setWorkflowId("private-subject-460").build(),
            "CaseWorkflow",
            IllegalStateException("private-provider-detail-460"),
        )
        assertPrivateOutcome(CaseOpenResult.Duplicate)
    }

    @Test
    fun `failed case logs neither subject nor throwable payload`() {
        every { untyped.start(any()) } throws WorkflowServiceException(
            WorkflowExecution.newBuilder().setWorkflowId("private-subject-460").build(),
            "CaseWorkflow",
            IllegalStateException("private-provider-detail-460"),
        )
        assertPrivateOutcome(CaseOpenResult.Unavailable)
    }

    private fun assertPrivateOutcome(expected: CaseOpenResult) {
        val records = mutableListOf<LogRecord>()
        val logger = JulLogger.getLogger(CaseOpenService::class.java.name)
        val previousLevel = logger.level
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            assertThat(open(subject = "private-subject-460")).isEqualTo(expected)
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
        }
        assertThat(records).isNotEmpty
        assertThat(records.map { it.thrown }).containsOnlyNulls()
        val output = records.joinToString("\n") { it.message + (it.parameters?.joinToString(" ") ?: "") }
        assertThat(output).doesNotContain("private-subject-460", "private-provider-detail-460")
        val workflowId = service.workflowIdFor(CaseClass.INCIDENT_RESPONSE, "private-subject-460")
        val correlation = java.security.MessageDigest.getInstance("SHA-256")
            .digest(workflowId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertThat(output).contains(correlation)
        if (expected is CaseOpenResult.Opened) assertThat(output).contains(PRINCIPAL, COORDINATOR)
        if (expected == CaseOpenResult.Unavailable) assertThat(output).contains("WorkflowServiceException")
    }

    @Test
    fun `workflow ids are deterministic per class and sanitized subject`() {
        assertThat(service.workflowIdFor(CaseClass.INCIDENT_RESPONSE, "ingest pod/1"))
            .isEqualTo("case-incident-response-ingest-pod-1")
    }
}
