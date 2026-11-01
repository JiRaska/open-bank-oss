// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.document.integration

import com.openbank.document.application.usecase.DocumentRenderService
import com.openbank.document.contract.PdfRendererStubResource
import com.openbank.document.infrastructure.outbox.DocumentOutboxDispatcher
import com.openbank.document.infrastructure.persistence.entity.DocumentOutboxEntity
import com.openbank.document.infrastructure.persistence.repository.DocumentOutboxRepositoryImpl
import com.openbank.document.it.PostgresRedisTestResource
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.TraceparentCapture
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Extract
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
import java.util.concurrent.TimeUnit

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
    private companion object {
        const val ROUTE = "/api/v1/documents/onboarding-agreement"
    }

    // Literals only: a profile loads in a different classloader from the test class.
    class TracedDispatchProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
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
    lateinit var openTelemetry: OpenTelemetry

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

    /** A fresh, valid W3C trace context the request under test owns (#13512). */
    private fun ownedTraceparent(): Pair<String, String> {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)
        return traceId to "00-$traceId-$spanId-01"
    }

    /** POSTs an onboarding agreement under [traceparent] and returns the document id the service answered. */
    private fun requestAgreement(traceparent: String): UUID {
        val documentId: String = Given {
            contentType("application/json")
            header(OutboxKafkaHeaders.HEADER_TRACEPARENT, traceparent)
            body("""{"partyRef": "tp-${UUID.randomUUID().toString().replace("-", "").take(8)}", "lang": "cs"}""")
        } When {
            post(ROUTE)
        } Then {
            statusCode(200)
        } Extract {
            path("documentId")
        }
        return UUID.fromString(documentId)
    }

    /** The `document.generated.v1` outbox rows the request wrote for [documentId]: (event id, trace context). */
    private fun generatedRows(documentId: UUID): List<Pair<UUID, String?>> = onEventLoop {
        Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "from DocumentOutboxEntity where aggregateId = :documentId and eventType = :eventType",
                    DocumentOutboxEntity::class.java,
                ).setParameter("documentId", documentId)
                    .setParameter("eventType", DocumentRenderService.EVENT_DOCUMENT_GENERATED)
                    .resultList
            }
        }.awaitSuspending().map { it.eventId to it.traceContext }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `the relayed record carries the trace of the request that wrote the event`() {
        // A same-route distractor with its own trace: neither its span nor its record may satisfy the
        // assertions below, so selection must be by ownership (trace id, document id, event id), not
        // by route name uniqueness or by the first traced record (#13512).
        val (distractorTraceId, distractorTraceparent) = ownedTraceparent()
        val distractorDocumentId = requestAgreement(distractorTraceparent)
        val (ownedTraceId, ownedTraceparent) = ownedTraceparent()
        val documentId = requestAgreement(ownedTraceparent)
        assertThat(documentId).isNotEqualTo(distractorDocumentId)

        // Flush every configured processor: delayed exports must not hide duplicate capture routes.
        assertThat(openTelemetry).isInstanceOf(OpenTelemetrySdk::class.java)
        val flushed = (openTelemetry as OpenTelemetrySdk).sdkTracerProvider.forceFlush().join(10, TimeUnit.SECONDS)
        assertThat(flushed.isSuccess).describedAs("all configured trace processors flushed").isTrue()
        val routeSpans = exporter.spans().filter { it.name == "POST $ROUTE" }
        assertThat(routeSpans.map { it.spanContext.traceId })
            .describedAs("both same-route requests were exported as server spans")
            .contains(ownedTraceId, distractorTraceId)
        val requestTraceId = routeSpans.single { it.spanContext.traceId == ownedTraceId }.spanContext.traceId

        val (eventId, storedTraceparent) = generatedRows(documentId).single()
        assertThat(storedTraceparent).describedAs("trace context persisted on the outbox row").isNotNull()
        val storedTraceId = Span.fromContext(
            TraceparentCapture.parentContext(storedTraceparent!!)!!,
        ).spanContext.traceId
        assertThat(storedTraceId).describedAs("persisted trace id").isEqualTo(requestTraceId)

        onEventLoop { dispatcher.dispatch() }

        val relayed = received().single { header(it, OutboxKafkaHeaders.HEADER_EVENT_ID) == eventId.toString() }
        assertThat(header(relayed, OutboxKafkaHeaders.HEADER_EVENT_TYPE))
            .isEqualTo(DocumentRenderService.EVENT_DOCUMENT_GENERATED)
        assertThat(relayed.payload).contains(documentId.toString())
        val traceparent = header(relayed, OutboxKafkaHeaders.HEADER_TRACEPARENT)
        assertThat(
            traceparent,
        ).describedAs("the owned record carries a traceparent: the outbox kept the trace").isNotNull()
        // What a consumer's instrumentation extracts from the header:
        val consumerSideTraceId = Span.fromContext(
            TraceparentCapture.parentContext(traceparent!!)!!,
        ).spanContext.traceId
        // What the Kafka sink uses as the parent of the producer span:
        val producerParentTraceId = Span.fromContext(
            TracingMetadata.fromMessage(relayed).orElseThrow().previousContext,
        ).spanContext.traceId

        assertThat(consumerSideTraceId).describedAs("consumer-side trace id").isEqualTo(requestTraceId)
        assertThat(producerParentTraceId).describedAs("producer span parent trace id").isEqualTo(requestTraceId)
        assertThat(requestTraceId).isNotEqualTo(distractorTraceId)
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
