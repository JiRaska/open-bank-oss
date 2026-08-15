// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.domain.model.AttributionSource
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.infrastructure.persistence.AuditRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/**
 * Attribution recovery from the broker address (#3994).
 *
 * EVERY assertion here checks the VALUE, never `isNotNull()`. That is the point: the defect under
 * test is a *default* — `?: "unknown"` and `?: "UNKNOWN"` — so a non-null assertion passes against
 * the broken code exactly as it does against the fixed code. Non-nullity is what let 76% of the
 * audit trail go unattributed without a single test going red.
 */
class AuditAttributionTest {

    private val repo = mockk<AuditRepository>()

    private val registry = SimpleMeterRegistry()

    private lateinit var consumer: AuditConsumer

    @BeforeEach
    fun setUp() {
        consumer = AuditConsumer().also {
            it.repo = repo
            it.objectMapper = jacksonObjectMapper().findAndRegisterModules()
            it.clock = Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC)
            it.meterRegistry = registry
        }
    }

    @Test
    fun `the ce-type header names the event when the body carries no discriminator`(): Unit = runBlocking {
        // The body of an outbox-relayed event is the bare domain event, with no eventType key at
        // all — while the type rides in the `ce-type` header the whole time. RED against the old
        // consume(payload: String): the header was unreadable, so this stored "UNKNOWN". That is
        // the live 131-row UNKNOWN bucket, all of it money-path payment settlements.
        val entry = capturingSave()

        consumer.consume(
            """{"paymentId":"${UUID.randomUUID()}","status":"SETTLED"}""",
            EventAddress(topic = "openbank.domestic.payment.events", ceType = "domestic.payment.status-changed"),
        )

        assertThat(entry.captured.eventType).isEqualTo("domestic.payment.status-changed")
    }

    @Test
    fun `the topic names the producing service when the producer does not`(): Unit = runBlocking {
        // 1353 of 1774 live rows are here: every producer except customer-edge omits sourceService.
        // RED against the old code, which stored "unknown" with ABSENT-equivalent silence.
        val entry = capturingSave()

        consumer.consume(
            """{"partyId":"${UUID.randomUUID()}","eventType":"PARTY_MERGED"}""",
            EventAddress(topic = "openbank.party.events"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("party-service")
        // The value alone is not enough. A derived attribution stored as though the producer had
        // claimed it is the same class of defect one level up.
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.TOPIC)
    }

    @Test
    fun `the producer's own claim wins over the topic, and is marked as the producer's`(): Unit = runBlocking {
        // Body-first ordering: this change can only turn a sentinel into a value. It must never
        // re-attribute a row that is already attributed, or customer-edge's 421 correct rows move.
        val entry = capturingSave()

        consumer.consume(
            """{"partyId":"${UUID.randomUUID()}","sourceService":"customer-edge"}""",
            EventAddress(topic = "openbank.party.events"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("customer-edge")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `an unrecognised topic attributes nothing rather than guessing`(): Unit = runBlocking {
        // The honest failure mode. A convention-based derivation would answer
        // "openbank-<segment>-service" for anything at all, which is how a confident FALSE service
        // name gets chain-hashed into an evidentiary record. Unknown stays unknown.
        val entry = capturingSave()

        consumer.consume(
            """{"accountId":"${UUID.randomUUID()}"}""",
            EventAddress(topic = "com.acme.some.other.topic"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("unknown")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.ABSENT)
    }

    @Test
    fun `a topic-derived row is still counted as a producer gap`(): Unit = runBlocking {
        // Folding TOPIC in with EVENT would make the outstanding producer work vanish from the
        // dashboard the moment this ships — the same silence that let the defect reach 76%.
        coEvery { repo.save(any()) } returns Unit

        consumer.consume(
            """{"partyId":"${UUID.randomUUID()}"}""",
            EventAddress(topic = "openbank.party.events"),
        )

        assertThat(
            registry.counter(
                "openbank.audit.attribution.missing",
                "source_service",
                "party-service",
                "provenance",
                "TOPIC",
            ).count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a message is always acked, even when it cannot be stored`(): Unit = runBlocking {
        // The one way this change could be WORSE than the defect it fixes. Taking Message<String>
        // switches SmallRye from auto-ack to manual ack; an un-acked message stalls the partition
        // and the audit trail stops dead. Unparseable payload = the path most likely to skip it.
        val acked = AtomicBoolean(false)
        val message = Message.of(
            "this is not json",
            Supplier<CompletionStage<Void>> {
                acked.set(true)
                CompletableFuture.completedFuture(null)
            },
        )

        consumer.consume(message)

        assertThat(acked.get()).isTrue()
    }

    @Test
    fun `a message with no broker metadata still stores, on the sentinels`(): Unit = runBlocking {
        // Nothing is rejected: absent metadata is not an error, it is just no extra information.
        val entry = capturingSave()
        val message = Message.of(
            """{"accountId":"${UUID.randomUUID()}"}""",
            Supplier<CompletionStage<Void>> { CompletableFuture.completedFuture(null) },
        )

        consumer.consume(message)

        assertThat(entry.captured.sourceService).isEqualTo("unknown")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.ABSENT)
    }

    private fun capturingSave() = slot<AuditEntry>().also {
        coEvery { repo.save(capture(it)) } returns Unit
    }
}
