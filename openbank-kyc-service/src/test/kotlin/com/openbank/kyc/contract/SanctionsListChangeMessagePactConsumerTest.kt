// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.contract

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.annotations.Pact
import au.com.dius.pact.core.model.messaging.Message
import au.com.dius.pact.core.model.messaging.MessagePact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.kyc.application.ScreeningTriggerService
import com.openbank.kyc.infrastructure.kafka.SanctionsListChangedConsumer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven MESSAGE contract for the sanctions list-change events kyc-service consumes
 * ([SanctionsListChangedConsumer], ADR-0256 trigger loop, issue #15908).
 *
 * **The event type is part of the contract as message METADATA, not as a body field.** The topic
 * is outbox-relayed: the producer sends the outbox row's payload verbatim and moves the row's type
 * onto the `ce-type` Kafka header. A consumer that looked for the type in the body matched no
 * branch and acked every record (#15900), and nothing replayed the shape against the producer —
 * so the header value is written here as a LITERAL on both sides of the assertion. Deriving it
 * from the consumer's own constants would make expectation and dispatch move together, and the
 * test would stay green through a rename the producer never made.
 *
 * Each interaction is fed to the REAL consumer as a `ConsumerRecord` built from the pact message:
 * its body verbatim and its metadata as Kafka headers. That is what makes the body fields the
 * ones the consumer reads — nothing more — and what the two negative cases at the bottom rely on:
 * the same message without the header, or with the type moved into the body, opens nothing.
 *
 * sanctions-service replays this pact on every pull request from its `@PactFolder` class, through
 * the production publication and outbox-publisher path
 * (`SanctionsListChangePactMessages` in openbank-sanctions-service).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-sanctions-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class SanctionsListChangeMessagePactConsumerTest {

    private val screeningTriggerService = mockk<ScreeningTriggerService>()
    private val registry = SimpleMeterRegistry()
    private val mapper = ObjectMapper()
    private lateinit var consumer: SanctionsListChangedConsumer

    @BeforeEach
    fun setUp() {
        consumer = SanctionsListChangedConsumer().also {
            it.screeningTriggerService = screeningTriggerService
            it.objectMapper = mapper
            it.meterRegistry = registry
            it.enabled = "true"
            it.candidateLimit = "500"
        }
        coEvery {
            screeningTriggerService.handleListChange(any(), 500)
        } returns ScreeningTriggerService.HandleResult(candidates = 1, opened = 1, failed = 0, duplicates = 0)
    }

    @Pact(consumer = "openbank-kyc-service", provider = "openbank-sanctions-service")
    fun listChangedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a sanctions list diff with a changed and a deactivated entry has been published")
        .expectsToReceive("a SANCTIONS_LIST_CHANGED event")
        .withMetadata(mapOf("ce-type" to "SANCTIONS_LIST_CHANGED"))
        .withContent(
            newJsonBody { o ->
                o.uuid("publicationId", UUID.fromString("5f0c1a52-6d1e-4b7e-9d0a-3c1f2b7a9e10"))
                o.integerType("chunkIndex", 0)
                o.integerType("chunkCount", 1)
                o.stringType("listType", "EU_CONSOLIDATED")
                o.minArrayLike("changedExternalIds", 1, PactDslJsonRootValue.stringType("ext-1"), 1)
                o.minArrayLike("deactivatedExternalIds", 1, PactDslJsonRootValue.stringType("ext-9"), 1)
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-kyc-service", provider = "openbank-sanctions-service")
    fun listChangeStormPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a sanctions list refresh has been withheld by the storm guard")
        .expectsToReceive("a SANCTIONS_LIST_CHANGE_STORM event")
        .withMetadata(mapOf("ce-type" to "SANCTIONS_LIST_CHANGE_STORM"))
        .withContent(
            newJsonBody { o ->
                o.stringType("listType", "EU_CONSOLIDATED")
                o.stringType("reason", "CHANGE_SHARE")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listChangedPact")
    fun `a list change typed by the ce-type header reaches the trigger service`(messages: List<Message>): Unit =
        runBlocking {
            val message = messages.single()
            // LITERAL, on purpose — see the class KDoc.
            assertThat(message.metadata["ce-type"]).isEqualTo("SANCTIONS_LIST_CHANGED")
            assertThat(body(message).has("eventType")).isFalse()

            consumer.consume(record(json(message), headersOf(message)))

            val change = slot<ScreeningTriggerService.ListChange>()
            coVerify(exactly = 1) { screeningTriggerService.handleListChange(capture(change), 500) }
            assertThat(change.captured.publicationId)
                .isEqualTo(UUID.fromString("5f0c1a52-6d1e-4b7e-9d0a-3c1f2b7a9e10"))
            assertThat(change.captured.chunkIndex).isEqualTo(0)
            assertThat(change.captured.listType).isEqualTo("EU_CONSOLIDATED")
            assertThat(change.captured.changedExternalIds).containsExactly("ext-1")
            assertThat(change.captured.deactivatedExternalIds).containsExactly("ext-9")
            assertThat(counted("handled")).isEqualTo(1.0)
            assertThat(counted("unrecognised")).isZero()
        }

    @Test
    @PactTestFor(pactMethod = "listChangeStormPact")
    fun `a storm typed by the ce-type header is recognised and opens no case`(messages: List<Message>): Unit =
        runBlocking {
            val message = messages.single()
            assertThat(message.metadata["ce-type"]).isEqualTo("SANCTIONS_LIST_CHANGE_STORM")

            consumer.consume(record(json(message), headersOf(message)))

            coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
            assertThat(counted("storm")).isEqualTo(1.0)
            assertThat(counted("unrecognised")).isZero()
        }

    /** Negative: the contract's body alone, without the header, is NOT a list change. */
    @Test
    @PactTestFor(pactMethod = "listChangedPact")
    fun `the same body without the ce-type header is unrecognised and opens no case`(messages: List<Message>): Unit =
        runBlocking {
            consumer.consume(record(json(messages.single()), emptyMap()))

            coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
            assertThat(counted("unrecognised")).isEqualTo(1.0)
            assertThat(counted("handled")).isZero()
        }

    /** Negative: the pre-#15901 shape — type in the body, none on the header — is NOT a list change. */
    @Test
    @PactTestFor(pactMethod = "listChangedPact")
    fun `a type moved into the body is unrecognised and opens no case`(messages: List<Message>): Unit = runBlocking {
        val withBodyType = (body(messages.single()) as ObjectNode).put("eventType", "SANCTIONS_LIST_CHANGED")

        consumer.consume(record(mapper.writeValueAsString(withBodyType), emptyMap()))

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
        assertThat(counted("unrecognised")).isEqualTo(1.0)
    }

    private fun json(message: Message): String = String(message.contentsAsBytes(), Charsets.UTF_8)

    private fun body(message: Message) = mapper.readTree(json(message))

    /** Every metadata entry except pact's own content type travels as a Kafka header. */
    private fun headersOf(message: Message): Map<String, String> =
        message.metadata.filterKeys { !it.equals("contentType", ignoreCase = true) }
            .mapValues { (_, value) -> value.toString() }

    private fun record(body: String, headers: Map<String, String>): ConsumerRecord<String, String> =
        ConsumerRecord("openbank.sanctions.screening.event", 0, 0L, "k", body).also { record ->
            headers.forEach { (name, value) -> record.headers().add(name, value.toByteArray()) }
        }

    private fun counted(outcome: String): Double =
        registry.find("openbank.kyc.sanctions_list_change_events").tag("outcome", outcome).counter()?.count() ?: 0.0
}
