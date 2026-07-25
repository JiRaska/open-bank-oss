// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.reactive.messaging.kafka.Record
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * The push-notification sender (#4) is how a party learns there is a payment to approve — an
 * un-emitted or malformed SCA_APPROVAL request means the approve-on-device flow silently stalls.
 * It was 0% covered. Verifies the emitted contract (channel/template/recipient) and that the
 * CWE-117 log-injection guard actually neutralises CR/LF in the caller-supplied message before it
 * reaches the log line (the emitted event deliberately carries the raw message; only the log is
 * sanitised, so the guard can only be observed on the captured log record).
 */
class LoggingNotificationSenderTest {

    private val objectMapper = ObjectMapper()
    private val emitter = mockk<Emitter<Record<String, String>>>(relaxed = true)
    private val sender = LoggingNotificationSender(objectMapper, emitter)

    private val captured = mutableListOf<LogRecord>()
    private val captureHandler = object : Handler() {
        override fun publish(record: LogRecord) {
            captured += record
        }
        override fun flush() = Unit
        override fun close() = Unit
    }
    private val senderLogger = Logger.getLogger(LoggingNotificationSender::class.java.name)

    @BeforeEach
    fun attachHandler() {
        senderLogger.level = Level.ALL
        senderLogger.addHandler(captureHandler)
    }

    @AfterEach
    fun detachHandler() {
        senderLogger.removeHandler(captureHandler)
    }

    @Test
    fun `sendPushNotification emits an SCA_APPROVAL push request keyed by partyId`() {
        val partyId = UUID.randomUUID()
        val challengeId = UUID.randomUUID()
        val slot = slot<Record<String, String>>()

        runBlocking { sender.sendPushNotification(partyId, challengeId, "Approve payment of 250 EUR") }

        verify { emitter.send(capture(slot)) }
        val record = slot.captured
        assertThat(record.key()).isEqualTo(partyId.toString())

        val payload = objectMapper.readTree(record.value())
        assertThat(payload["partyId"].asText()).isEqualTo(partyId.toString())
        assertThat(payload["channel"].asText()).isEqualTo("PUSH")
        assertThat(payload["template"].asText()).isEqualTo("SCA_APPROVAL")
        assertThat(payload["recipient"].asText()).isEqualTo(partyId.toString())
        assertThat(payload["variables"]["detail"].asText()).isEqualTo("Approve payment of 250 EUR")
    }

    @Test
    fun `the log line strips CR-LF from the caller-supplied message (CWE-117 guard)`() {
        val partyId = UUID.randomUUID()

        runBlocking {
            sender.sendPushNotification(partyId, UUID.randomUUID(), "line1\nFORGED admin=true\r\nline2")
        }

        // The message flows into the log line as a parameter; the guard must have replaced every
        // CR/LF with '_' so an attacker cannot forge extra log lines. Render message + params and
        // assert the forged content survives (message passed through) but not as separate lines.
        val logRecord = captured.single { it.message.contains("PUSH") }
        val rendered = logRecord.message + " " + (logRecord.parameters?.joinToString(" ") ?: "")
        assertThat(rendered).contains("line1_FORGED admin=true")
        assertThat(rendered).doesNotContain("\n").doesNotContain("\r")
    }

    @Test
    fun `sendPushNotification still emits the raw message on the event despite log sanitisation`() {
        val partyId = UUID.randomUUID()
        val slot = slot<Record<String, String>>()

        runBlocking { sender.sendPushNotification(partyId, UUID.randomUUID(), "raw\nmessage") }

        verify { emitter.send(capture(slot)) }
        val payload = objectMapper.readTree(slot.captured.value())
        assertThat(payload["variables"]["detail"].asText()).isEqualTo("raw\nmessage")
    }
}
