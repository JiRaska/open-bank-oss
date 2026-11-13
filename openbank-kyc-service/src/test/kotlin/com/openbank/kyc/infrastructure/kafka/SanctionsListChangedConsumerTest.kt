// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.ScreeningTriggerService
import com.openbank.kyc.infrastructure.kafka.SanctionsListChangeWire.EVENT_CHANGED
import com.openbank.kyc.infrastructure.kafka.SanctionsListChangeWire.EVENT_STORM
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Named so detekt's TooGenericExceptionThrown does not have to be suppressed at the throw site. */
private class TriggerTableFailure : RuntimeException("connection refused")

/**
 * Every record here has the producer's wire shape ([SanctionsListChangeWire]): the event type on
 * the `ce-type` header, a body without `eventType`. The earlier version of this class hand-wrote
 * bodies WITH an `eventType` key, which no producer emits, and so stayed green while the consumer
 * matched no branch for any real record.
 */
class SanctionsListChangedConsumerTest {

    private val screeningTriggerService = mockk<ScreeningTriggerService>()
    private val registry = SimpleMeterRegistry()
    private lateinit var consumer: SanctionsListChangedConsumer

    @BeforeEach
    fun setUp() {
        consumer = SanctionsListChangedConsumer().also {
            it.screeningTriggerService = screeningTriggerService
            it.objectMapper = ObjectMapper()
            it.meterRegistry = registry
        }
    }

    private fun enable() {
        consumer.enabled = "true"
        consumer.candidateLimit = "500"
    }

    private fun counted(outcome: String): Double =
        registry.find(SanctionsListChangedConsumer.METRIC_EVENTS).tag("outcome", outcome).counter()?.count() ?: 0.0

    private fun changed(publicationId: UUID = UUID.randomUUID()) = SanctionsListChangeWire.record(
        EVENT_CHANGED,
        SanctionsListChangeWire.json(SanctionsListChangeWire.changedBody(publicationId)),
    )

    @Test
    fun `the wire fixtures carry exactly the producer contract's properties and no eventType`() {
        val schemas = producerContractSchemas()
        val changed = SanctionsListChangeWire.changedBody(UUID.randomUUID())
        val storm = SanctionsListChangeWire.stormBody()

        assertThat(changed.keys).isEqualTo(propertiesOf(schemas, "SanctionsListChangedPayload"))
        assertThat(storm.keys).isEqualTo(propertiesOf(schemas, "SanctionsListChangeStormPayload"))
        assertThat(changed.keys + storm.keys).doesNotContain("eventType")
        // Closed schemas: a producer cannot add a body discriminator without changing the contract.
        assertThat(schemas.getValue("SanctionsListChangedPayload")["additionalProperties"]).isEqualTo(false)
        assertThat(schemas.getValue("SanctionsListChangeStormPayload")["additionalProperties"]).isEqualTo(false)
    }

    @Test
    fun `a diff typed only by the ce-type header is parsed and handed to the service`(): Unit = runBlocking {
        enable()
        val publicationId = UUID.randomUUID()
        coEvery {
            screeningTriggerService.handleListChange(any(), 500)
        } returns ScreeningTriggerService.HandleResult(candidates = 3, opened = 3, failed = 0, duplicates = 0)

        consumer.consume(changed(publicationId))

        val captured = slot<ScreeningTriggerService.ListChange>()
        coVerify(exactly = 1) { screeningTriggerService.handleListChange(capture(captured), 500) }
        assertThat(captured.captured.publicationId).isEqualTo(publicationId)
        assertThat(captured.captured.chunkIndex).isEqualTo(0)
        assertThat(captured.captured.listType).isEqualTo("EU_CONSOLIDATED")
        assertThat(captured.captured.changedExternalIds).containsExactly("ext-1", "ext-2")
        assertThat(captured.captured.deactivatedExternalIds).containsExactly("ext-9")
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_HANDLED)).isEqualTo(1.0)
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_UNRECOGNISED)).isZero()
    }

    @Test
    fun `SANCTIONS_LIST_CHANGE_STORM opens no cases and touches no dependency`(): Unit = runBlocking {
        enable()
        val body = SanctionsListChangeWire.json(SanctionsListChangeWire.stormBody())
        consumer.consume(SanctionsListChangeWire.record(EVENT_STORM, body))

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_STORM)).isEqualTo(1.0)
    }

    @Test
    fun `a storm body under a storm header never opens cases even if it looks like a diff`(): Unit = runBlocking {
        enable()
        // The header decides. A storm record that happened to carry diff fields must stay a storm.
        val body = SanctionsListChangeWire.json(SanctionsListChangeWire.changedBody(UUID.randomUUID()))
        consumer.consume(SanctionsListChangeWire.record(EVENT_STORM, body))

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
    }

    @Test
    fun `screening events sharing the topic are acked as ignored without parsing the body`(): Unit = runBlocking {
        enable()
        // Not JSON on purpose: an ignored sibling must never reach the decoder (it can hold names).
        consumer.consume(SanctionsListChangeWire.record("SanctionChecked", "not json"))
        consumer.consume(SanctionsListChangeWire.record("SanctionReviewed", "not json"))

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_IGNORED)).isEqualTo(2.0)
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_MALFORMED)).isZero()
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_UNRECOGNISED)).isZero()
    }

    @Test
    fun `an unknown event type is acked and counted as unrecognised`(): Unit = runBlocking {
        enable()
        consumer.consume(SanctionsListChangeWire.record("SANCTIONS_ENTRY_ADDED", "{}"))

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_UNRECOGNISED)).isEqualTo(1.0)
    }

    @Test
    fun `a record without the ce-type header is unrecognised even when the body names a type`(): Unit = runBlocking {
        enable()
        // The pre-fix shape, inverted: a body discriminator is not part of the contract, so it
        // must not be honoured — and the record must be visible, not silently dropped.
        val body = SanctionsListChangeWire.json(
            SanctionsListChangeWire.changedBody(UUID.randomUUID()) + ("eventType" to EVENT_CHANGED),
        )
        val headers = SanctionsListChangeWire.headers(SanctionsListChangeWire.entry(EVENT_CHANGED, body))
        consumer.consume(SanctionsListChangeWire.record(body, headers - OutboxKafkaHeaders.HEADER_EVENT_TYPE))

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_UNRECOGNISED)).isEqualTo(1.0)
    }

    @Test
    fun `malformed payload is acked without throwing`(): Unit = runBlocking {
        enable()
        consumer.consume(SanctionsListChangeWire.record(EVENT_CHANGED, "not json"))

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_MALFORMED)).isEqualTo(1.0)
    }

    @Test
    fun `with the loop off a real diff is RECORDED, never handled and never dropped`(): Unit = runBlocking {
        consumer.enabled = "false"
        consumer.candidateLimit = "500"
        val captured = slot<ScreeningTriggerService.ListChange>()
        coEvery { screeningTriggerService.recordListChange(capture(captured), 500) } returns
            ScreeningTriggerService.HandleResult(candidates = 3, opened = 0, failed = 0, duplicates = 1)
        val publicationId = UUID.randomUUID()

        consumer.consume(changed(publicationId))

        coVerify(exactly = 1) { screeningTriggerService.recordListChange(any(), 500) }
        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
        assertThat(captured.captured.publicationId).isEqualTo(publicationId)
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_RECORDED_WHILE_DISABLED)).isEqualTo(1.0)
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_HANDLED)).isZero()
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_UNRECOGNISED)).isZero()
    }

    @Test
    fun `with the loop on a diff is handled, not merely recorded`(): Unit = runBlocking {
        enable()
        coEvery { screeningTriggerService.handleListChange(any(), 500) } returns
            ScreeningTriggerService.HandleResult(candidates = 1, opened = 1, failed = 0, duplicates = 0)

        consumer.consume(changed())

        coVerify(exactly = 0) { screeningTriggerService.recordListChange(any(), any()) }
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_HANDLED)).isEqualTo(1.0)
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_RECORDED_WHILE_DISABLED)).isZero()
    }

    @Test
    fun `a diff without a valid publicationId is skipped`(): Unit = runBlocking {
        enable()
        val body = SanctionsListChangeWire.changedBody(UUID.randomUUID()) + ("publicationId" to "not-a-uuid")
        consumer.consume(SanctionsListChangeWire.record(EVENT_CHANGED, SanctionsListChangeWire.json(body)))

        coVerify(exactly = 0) { screeningTriggerService.handleListChange(any(), any()) }
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_MALFORMED)).isEqualTo(1.0)
    }

    @Test
    fun `an infrastructure failure is retried then rethrown so the connector dead-letters`(): Unit = runBlocking {
        enable()
        coEvery { screeningTriggerService.handleListChange(any(), any()) } throws TriggerTableFailure()

        assertThrows<TriggerTableFailure> { consumer.consume(changed()) }

        coVerify(exactly = 3) { screeningTriggerService.handleListChange(any(), any()) }
        assertThat(counted(SanctionsListChangedConsumer.OUTCOME_HANDLED)).isZero()
    }

    @Test
    fun `a transient failure is retried and the diff is handled without dead-lettering`(): Unit = runBlocking {
        enable()
        var calls = 0
        coEvery { screeningTriggerService.handleListChange(any(), any()) } answers {
            calls++
            if (calls == 1) throw TriggerTableFailure()
            ScreeningTriggerService.HandleResult(candidates = 1, opened = 1, failed = 0, duplicates = 0)
        }

        consumer.consume(changed())

        coVerify(exactly = 2) { screeningTriggerService.handleListChange(any(), any()) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun producerContractSchemas(): Map<String, Map<String, Any?>> {
        // Gradle runs tests with the module directory as the working directory.
        val contract = Path.of("..", "openbank-contracts", "openbank-sanctions-service", "asyncapi.yaml")
        assertThat(contract).exists()
        val document = Files.newBufferedReader(contract).use { Yaml().load<Map<String, Any?>>(it) }
        val components = document.getValue("components") as Map<String, Any?>
        return components.getValue("schemas") as Map<String, Map<String, Any?>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun propertiesOf(schemas: Map<String, Map<String, Any?>>, name: String): Set<String> =
        (schemas.getValue(name).getValue("properties") as Map<String, Any?>).keys
}
