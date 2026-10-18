// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.document.integration

import com.openbank.document.contract.PdfRendererStubResource
import com.openbank.document.infrastructure.outbox.DocumentOutboxDispatcher
import com.openbank.document.infrastructure.persistence.repository.DocumentOutboxRepositoryImpl
import com.openbank.document.it.PostgresRedisTestResource
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.TraceparentCapture
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.opentelemetry.api.trace.Span
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.TracingMetadata
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The outbox used to cut the trace: the request wrote the event, the dispatcher (a scheduler
 * thread with no request) relayed it, and the record left the service in a trace of its own, so a
 * consumer span could not be linked back to the request that caused it.
 *
 * This drives the real chain -- HTTP request, outbox INSERT in the request's transaction, dispatch,
 * the Kafka record's headers and tracing metadata -- and reads the ids off the spans the service
 * itself produced. Nothing asserted is created by the test.
 *
 * Scope, stated plainly: the channel is the in-memory connector, which does not run the Kafka
 * OpenTelemetry interceptors, so the consumer SPAN is not observed here. What is observed is
 * exactly what that instrumentation reads: the `traceparent` header on the record, and the
 * [TracingMetadata] previous context the Kafka sink uses as the producer span's parent.
 */
@QuarkusTest
@TestProfile(DocumentOutboxTracePropagationIT.TracedDispatchProfile::class)
@QuarkusTestResource(
    value = DocumentOutboxTracePropagationIT.InMemoryKafkaResource::class,
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(PostgresRedisTestResource::class)
@QuarkusTestResource(PdfRendererStubResource::class, restrictToAnnotatedClass = true)
class DocumentOutboxTracePropagationIT {

    // Literals only: a profile loads in a different classloader from the test class.
    class TracedDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.otel.sdk.disabled" to "false",
            "openbank.outbox.dispatch-enabled" to "true",
        )
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("document-events-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("account-created-in", "billing-outbox-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Inject
    lateinit var dispatcher: DocumentOutboxDispatcher

    @Inject
    lateinit var repository: DocumentOutboxRepositoryImpl

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @Suppress("UNCHECKED_CAST")
    private fun received(): List<Message<String>> =
        connector.sink<String>("document-events-out").received().map { it as Message<String> }

    private fun header(message: Message<String>, name: String): String? =
        message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow().headers.lastHeader(name)
            ?.let { String(it.value(), StandardCharsets.UTF_8) }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `the relayed record carries the trace of the request that wrote the event`() {
        Given {
            contentType("application/json")
            body("""{"partyRef": "tp-${UUID.randomUUID().toString().replace("-", "").take(8)}", "lang": "cs"}""")
        } When {
            post("/api/v1/documents/onboarding-agreement")
        } Then {
            statusCode(200)
        }
        val requestTraceId = exporter.spans()
            .single { it.name == "POST /api/v1/documents/onboarding-agreement" }
            .spanContext.traceId

        onEventLoop { dispatcher.dispatch() }

        val relayed = received().filter { header(it, OutboxKafkaHeaders.HEADER_TRACEPARENT) != null }
        assertThat(relayed)
            .describedAs("no relayed record carried a traceparent: the outbox cut the trace")
            .isNotEmpty()
        val traceparent = header(relayed.first(), OutboxKafkaHeaders.HEADER_TRACEPARENT)!!
        // What a consumer's instrumentation extracts from the header:
        val consumerSideTraceId = Span.fromContext(TraceparentCapture.parentContext(traceparent)!!).spanContext.traceId
        // What the Kafka sink uses as the parent of the producer span:
        val producerParentTraceId = Span.fromContext(
            TracingMetadata.fromMessage(relayed.first()).orElseThrow().previousContext,
        ).spanContext.traceId

        assertThat(consumerSideTraceId).describedAs("consumer-side trace id").isEqualTo(requestTraceId)
        assertThat(producerParentTraceId).describedAs("producer span parent trace id").isEqualTo(requestTraceId)
    }

    @Test
    fun `a row written outside any span is relayed without a traceparent, exactly as before`() {
        val message = OutboxMessage(
            aggregateId = UUID.randomUUID(),
            eventType = "document.untraced",
            payload = "{}",
        )
        onEventLoop { Panache.withTransaction { repository.persistInTransaction(message) }.awaitSuspending() }

        onEventLoop { dispatcher.dispatch() }

        val mine = received().single { header(it, OutboxKafkaHeaders.HEADER_EVENT_ID) == message.eventId.toString() }
        assertThat(header(mine, OutboxKafkaHeaders.HEADER_TRACEPARENT)).isNull()
        assertThat(TracingMetadata.fromMessage(mine).map { it.previousContext }.orElse(null))
            .describedAs("no stored trace means the sink keeps its default parent")
            .isNull()
    }
}
