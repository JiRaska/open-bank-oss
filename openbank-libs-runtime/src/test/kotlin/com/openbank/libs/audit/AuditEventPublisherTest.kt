// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JulLogger

/**
 * [LoggingAuditEventPublisher] is the DORA Art. 17 fallback that guarantees an audit event
 * is always recorded even when no durable (Kafka) publisher is wired up — so this must never
 * throw regardless of which optional fields are absent.
 *
 * These tests capture the actual emitted line rather than only asserting "did not throw". A
 * "does not throw" test alone leaves every field's presence in the message, and the `?: "-"`
 * absent-field placeholders, as NO_COVERAGE mutants: pitest measured 3 SURVIVED mutants here
 * (two negated `?:` conditionals, one nulled return) precisely because nothing ever read the
 * formatted output back.
 *
 * Capture goes through plain `java.util.logging` against the `"openbank.audit"` category:
 * `org.jboss.logging.Logger` resolves its backend once per JVM from whatever logging libraries
 * are on the classpath, and jboss-logmanager is pulled in transitively here (e.g. via
 * resteasy-core) and installed as the system `LogManager` — so `java.util.logging.Logger` and
 * the wrapped jboss-logmanager logger are, in this module's test JVM, the same underlying node.
 * Assertions below check "the real value appears somewhere in what was captured" rather than an
 * exact record count: `org.jboss.logging`'s provider can hand one `infof` call to more than one
 * attached handler invocation depending on the resolved backend, and that plumbing detail is not
 * what these tests exist to pin down.
 */
class AuditEventPublisherTest {

    private val publisher: AuditEventPublisher = LoggingAuditEventPublisher()
    private val julLogger = JulLogger.getLogger("openbank.audit")
    private val captured = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            captured += record
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    @BeforeEach
    fun attachHandler() {
        julLogger.level = Level.ALL
        julLogger.useParentHandlers = false
        julLogger.addHandler(handler)
    }

    @AfterEach
    fun detachHandler() {
        julLogger.removeHandler(handler)
        julLogger.useParentHandlers = true
    }

    /**
     * `org.jboss.logging.Logger.infof` uses `%s` printf placeholders, which plain
     * `java.util.logging` formatters cannot substitute (they only understand `{0}`-style
     * `MessageFormat`) — so the backend pre-formats the final string itself before the
     * [LogRecord] is created, leaving [LogRecord.getParameters] null in practice. This falls back
     * to a manual `String.format` for the case where a backend instead hands back the raw format
     * string with parameters still attached, so the assertion holds either way.
     */
    private fun loggedLine(record: LogRecord): String {
        val params = record.parameters
        return if (params.isNullOrEmpty()) record.message else String.format(record.message, *params)
    }

    /** All captured lines joined, so an assertion is unaffected by exactly how many times the
     * resolved logging backend re-delivered the single [AuditEventPublisher.publish] call to
     * this handler. */
    private fun allLoggedText(): String {
        assertThat(captured).describedAs("expected at least one log record to be captured").isNotEmpty()
        return captured.joinToString(" | ") { loggedLine(it) }
    }

    @Test
    fun `publishes a fully populated event, logging every field's real value`(): Unit = runBlocking {
        publisher.publish(
            AuditEvent(
                actorId = "party-123",
                actorType = "CUSTOMER",
                operation = "account.party.created",
                resourceType = "account",
                resourceId = "acc-456",
                ipAddress = "203.0.113.7",
                userAgent = "openbank-app/1.0",
                result = AuditResult.SUCCESS,
                traceId = "trace-789",
            ),
        )

        val line = allLoggedText()
        assertThat(line).contains("party-123", "CUSTOMER", "account.party.created", "account", "acc-456")
        assertThat(line).contains("SUCCESS", "trace-789")
    }

    @Test
    fun `publishes an event with absent optional fields, printing the dash placeholder not null`(): Unit = runBlocking {
        publisher.publish(
            AuditEvent(
                actorId = "system",
                actorType = "SERVICE",
                operation = "payment.sepa.recalled",
                resourceType = "payment",
                resourceId = null,
                result = AuditResult.DENIED,
                traceId = null,
            ),
        )

        val line = allLoggedText()
        assertThat(line).contains("system", "SERVICE", "payment.sepa.recalled", "payment", "DENIED")
        // Falsifying assertion: a negated `resourceId ?: "-"` / `traceId ?: "-"` elvis
        // conditional prints the literal string "null" instead of "-", or vice-versa when the
        // field IS present — either mutation is caught by asserting the dash appears and the
        // word "null" never does.
        assertThat(line).contains("resourceId=-", "traceId=-")
        assertThat(line).doesNotContain("=null")
    }

    @Test
    fun `resourceId and traceId print their real value, not the absent-field dash, when present`(): Unit = runBlocking {
        publisher.publish(
            AuditEvent(
                actorId = "party-1",
                actorType = "CUSTOMER",
                operation = "account.updated",
                resourceType = "account",
                resourceId = "acc-1",
                traceId = "trace-1",
            ),
        )

        val line = allLoggedText()
        assertThat(line).contains("resourceId=acc-1", "traceId=trace-1")
        assertThat(line).doesNotContain("resourceId=-", "traceId=-")
    }

    @Test
    fun `never logs the raw payload map — the logging fallback is not a leak path for PII`(): Unit = runBlocking {
        publisher.publish(
            AuditEvent(
                actorId = "party-1",
                actorType = "CUSTOMER",
                operation = "account.updated",
                resourceType = "account",
                resourceId = "acc-1",
                payload = mapOf("nationalId" to "990101-1234", "secret" to "do-not-log-me"),
            ),
        )

        val line = allLoggedText()
        assertThat(line).doesNotContain("990101-1234", "do-not-log-me", "nationalId")
    }
}
