// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.notification.application.NotificationConsumer
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ConsentNotificationConsumerTest {

    private val notificationConsumer = mockk<NotificationConsumer>(relaxed = true)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private lateinit var consumer: ConsentNotificationConsumer

    private val consentId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        consumer = ConsentNotificationConsumer(notificationConsumer, objectMapper)
        every { notificationConsumer.consume(any()) } returns Uni.createFrom().voidItem()
    }

    /** Captures the [NotificationRequest] JSON(s) handed to [NotificationConsumer.consume]. */
    private fun capturedRequests(): List<NotificationRequest> {
        val slot = mutableListOf<String>()
        verify { notificationConsumer.consume(capture(slot)) }
        return slot.map { objectMapper.readValue(it, NotificationRequest::class.java) }
    }

    private fun assertNotNotified() = verify(exactly = 0) { notificationConsumer.consume(any()) }

    private fun eventPayload(
        eventType: String,
        scopes: List<String>? = listOf("ACCOUNTS_READ"),
        accountAccess: Any? = true,
        aggregateId: UUID? = consentId,
        party: UUID? = partyId,
    ): String {
        val fields = mutableListOf(
            "\"eventType\":\"$eventType\"",
            "\"occurredAt\":\"${Instant.parse("2026-09-03T10:00:00Z")}\"",
        )
        aggregateId?.let { fields += "\"aggregateId\":\"$it\"" }
        party?.let { fields += "\"partyId\":\"$it\"" }
        scopes?.let { fields += "\"scopes\":[${it.joinToString(",") { s -> "\"$s\"" }}]" }
        accountAccess?.let {
            val rendered = if (it is String) "\"$it\"" else it.toString()
            fields += "\"accountAccess\":$rendered"
        }
        return "{${fields.joinToString(",")}}"
    }

    private fun consume(payload: String) = consumer.consume(payload).subscribe().with({}, {})

    @Test
    fun `ConsentGranted for account access notifies the party who granted it`() {
        consume(eventPayload("ConsentGranted"))

        val req = capturedRequests().single()
        assertThat(req.partyId).isEqualTo(partyId)
        assertThat(req.template).isEqualTo(NotificationTemplate.CONSENT_GRANTED)
        assertThat(req.channel).isEqualTo(NotificationChannel.PUSH)
        assertThat(req.correlationId).isEqualTo(consentId)
        assertThat(req.variables).isEqualTo(mapOf("scope" to "ACCOUNTS_READ"))
    }

    @Test
    fun `ConsentRevoked notifies with the revoked template`() {
        consume(eventPayload("ConsentRevoked"))

        assertThat(capturedRequests().single().template).isEqualTo(NotificationTemplate.CONSENT_REVOKED)
    }

    /**
     * The load-bearing guard. `ConsentScope` mixes account access with pure data-processing
     * preferences, both raise these two events, and the templates say account DATA was granted —
     * so a marketing opt-in must not produce a security push the customer generated themselves.
     */
    @Test
    fun `a data-processing preference raises no notification`() {
        consume(eventPayload("ConsentGranted", scopes = listOf("MARKETING_COMMS_EMAIL"), accountAccess = false))
        consume(eventPayload("ConsentRevoked", scopes = listOf("TELEMETRY_RUM"), accountAccess = false))

        assertNotNotified()
    }

    /**
     * Fails CLOSED rather than guessing: an event from before the producer published the flag
     * cannot be classified, and either default is a wrong customer outcome.
     */
    @Test
    fun `an event with no usable accountAccess flag is dropped, not guessed`() {
        consume(eventPayload("ConsentGranted", accountAccess = null))
        consume(eventPayload("ConsentGranted", accountAccess = "yes"))

        assertNotNotified()
    }

    /**
     * Superseded is not a withdrawal — access continues under a newer consent for the same grantee
     * and scopes (#6487) — so saying it ended would be false. Expiry and rejection have no template
     * that fits and need a customer-facing decision first.
     */
    @Test
    fun `superseded, expired and rejected do not notify`() {
        listOf("ConsentSuperseded", "ConsentExpired", "ConsentRejected", "SuppressionCreated", "Whatever")
            .forEach { consume(eventPayload(it)) }

        assertNotNotified()
    }

    @Test
    fun `scopes are listed in a stable order`() {
        consume(
            eventPayload("ConsentGranted", scopes = listOf("TRANSACTIONS_READ", "ACCOUNTS_READ", "BALANCES_READ")),
        )

        assertThat(capturedRequests().single().variables["scope"])
            .isEqualTo("ACCOUNTS_READ, BALANCES_READ, TRANSACTIONS_READ")
    }

    @Test
    fun `an event with no scopes still satisfies the template's required variable`() {
        consume(eventPayload("ConsentGranted", scopes = emptyList()))

        val variables = capturedRequests().single().variables
        assertThat(variables["scope"]).isNotNull().isNotEqualTo("")
        // The closed variable-schema check (ADR-0176 D1) rejects a request missing a declared
        // variable, so an empty scope list must still produce the key, not a blank.
        assertThat(variables.keys).containsAll(NotificationTemplate.CONSENT_GRANTED.variables)
    }

    @Test
    fun `missing or unparseable identifiers are dropped`() {
        consume(eventPayload("ConsentGranted", party = null))
        consume(eventPayload("ConsentGranted", aggregateId = null))
        consume("{\"eventType\":\"ConsentGranted\",\"accountAccess\":true,\"partyId\":\"not-a-uuid\"}")

        assertNotNotified()
    }

    /** A malformed record must never wedge the partition (mirrors DelegationNotificationConsumer). */
    @Test
    fun `a poison pill is swallowed`() {
        consume("{ this is not json")

        assertNotNotified()
    }

    /**
     * No deep link: `openbank://consents` is not in MobileDeepLink's closed allow-list, and a push
     * carrying a link the app cannot route is a tap that goes nowhere.
     */
    @Test
    fun `no deep link is sent`() {
        consume(eventPayload("ConsentGranted"))

        assertThat(capturedRequests().single().deepLink).isNull()
    }
}
