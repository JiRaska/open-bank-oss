// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.notification

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.application.port.out.NotificationDispatch
import com.openbank.pension.application.port.out.ParticipantNotification
import com.openbank.pension.application.port.out.ParticipantNotificationKind
import com.openbank.pension.application.port.out.ParticipantNotifier
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ParticipantNotificationPublisherTest {
    private val json = jacksonObjectMapper()
    private val meters = SimpleMeterRegistry()
    private val sent = mutableListOf<Pair<String, String>>()
    private val party = UUID.randomUUID()

    private val notice = ParticipantNotification(
        party,
        ParticipantNotificationKind.INCENTIVE_RECEIVED,
        mapOf("contractId" to "c", "period" to "2026-01", "amount" to "340.00", "currency" to "CZK"),
    )

    private fun publisher(
        enabled: Boolean,
        sender: NotificationRequestSender = NotificationRequestSender { k, p ->
            sent +=
                k to p
        },
        language: ParticipantLanguageResolver = ParticipantLanguageResolver { "CS" },
    ) = ParticipantNotificationPublisher(enabled, sender, json, meters, language, timeoutMillis = 200)

    private fun count(outcome: String) =
        meters.find(ParticipantNotificationPublisher.METRIC).tag("outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `switched off, a notice is SKIPPED, nothing is sent, and it is never counted as enqueued`(): Unit =
        runBlocking {
            assertThat(publisher(enabled = false).send(notice)).isEqualTo(NotificationDispatch.SKIPPED)
            assertThat(sent).isEmpty()
            assertThat(count("skipped")).isEqualTo(1.0)
            assertThat(count("enqueued")).isZero()
        }

    @Test
    fun `switched on, the notice goes out in notification-service's envelope, keyed by party`(): Unit = runBlocking {
        assertThat(publisher(enabled = true).send(notice)).isEqualTo(NotificationDispatch.ENQUEUED)
        val (key, payload) = sent.single()
        assertThat(key).isEqualTo(party.toString())
        val envelope: Map<String, Any?> = json.readValue(payload)
        assertThat(envelope).containsEntry("partyId", party.toString())
            .containsEntry("template", "PENSION_INCENTIVE_RECEIVED")
            .containsEntry("channel", "PUSH")
            .containsEntry("variables", notice.variables)
        assertThat(count("enqueued")).isEqualTo(1.0)
        // There is no "delivered" outcome: this service cannot observe delivery.
        assertThat(meters.find(ParticipantNotificationPublisher.METRIC).tag("outcome", "delivered").counter()).isNull()
    }

    @Test
    fun `a broker failure or a hang is FAILED and does not throw into the business step`(): Unit = runBlocking {
        assertThat(
            publisher(true, { _, _ ->
                error("broker down")
            }).send(notice),
        ).isEqualTo(NotificationDispatch.FAILED)
        assertThat(publisher(true, { _, _ -> delay(5_000) }).send(notice)).isEqualTo(NotificationDispatch.FAILED)
        assertThat(count("failed")).isEqualTo(2.0)
    }

    @Test
    fun `a payout-account change is refused unless its notice was ENQUEUED`(): Unit = runBlocking {
        val skipping = ParticipantNotifier { NotificationDispatch.SKIPPED }
        assertThatThrownBy {
            runBlocking { requireEnqueued(skipping, party, UUID.randomUUID(), "1234", LocalDate.of(2026, 11, 1)) }
        }.isInstanceOf(ParticipantNotificationUnavailableException::class.java)
        val recording = RecordingParticipantNotifier()
        requireEnqueued(recording, party, UUID.randomUUID(), "1234", LocalDate.of(2026, 11, 1))
        assertThat(recording.sent.single().kind).isEqualTo(ParticipantNotificationKind.PAYOUT_ACCOUNT_CHANGED)
    }

    @Test
    fun `a notice cannot carry a variable its kind does not declare, nor miss one`() {
        assertThatThrownBy {
            ParticipantNotification(party, ParticipantNotificationKind.TRANSFER_STATUS, notice.variables)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ParticipantNotification(party, ParticipantNotificationKind.TRANSFER_STATUS, mapOf("contractId" to "c"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the notice carries the participant's language so notification-service renders cs or en`(): Unit = runBlocking {
        publisher(enabled = true, language = { if (it == party) "EN" else "CS" }).send(notice)
        val envelope: Map<String, Any?> = json.readValue(sent.single().second)
        assertThat(envelope).containsEntry("language", "EN")
    }

    @Test
    fun `a party language maps onto notification-service's closed vocabulary, never an unknown value`() {
        assertThat(NoticeLanguage.of("cs", "CS")).isEqualTo("CS")
        assertThat(NoticeLanguage.of("en-GB", "CS")).isEqualTo("EN")
        assertThat(NoticeLanguage.of("EN", "CS")).isEqualTo("EN")
        assertThat(NoticeLanguage.of("de", "CS")).isEqualTo("CS")
        assertThat(NoticeLanguage.of(null, "EN")).isEqualTo("EN")
        assertThat(NoticeLanguage.of(" ", "xx")).isEqualTo("CS")
    }
}
