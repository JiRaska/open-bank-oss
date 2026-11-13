// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactBuilder
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.kyb.application.usecase.MonitoringFindingOutcome
import com.openbank.kyb.application.usecase.MonitoringFindingService
import com.openbank.kyb.application.usecase.MonitoringSettings
import com.openbank.kyb.domain.model.MonitoringMatch
import com.openbank.kyb.infrastructure.MicrometerKybMonitoringMetrics
import com.openbank.kyb.infrastructure.client.MonitoredSubjectAck
import com.openbank.kyb.infrastructure.client.MonitoredSubjectBody
import com.openbank.kyb.infrastructure.client.SanctionsMonitoringRestClient
import com.openbank.kyb.infrastructure.kafka.MonitoredSubjectMatchedConsumer
import com.openbank.kyb.infrastructure.kafka.MonitoredSubjectMatchedWire
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.restassured.RestAssured.given
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Consumer-driven contract for everything kyb-service exchanges with sanctions-service for
 * ongoing monitoring (ADR-0376): the two registry operations it CALLS and the finding it RECEIVES.
 *
 * One class and a V4 pact, because both directions belong to one consumer/provider pair: the
 * pact writer overwrites the pair's file per class, so a second class would erase the first's
 * interactions, and only V4 holds HTTP and message interactions side by side.
 *
 * **HTTP.** The expected path is a LITERAL and the request is built from the client's own `@Path`
 * annotations and its own body type. Deriving both from the annotation would make expectation and
 * request move together, and the test would stay green with the client pointed at a route that
 * does not exist. The request side still cannot catch a wrong path by itself — the mock server
 * answers whatever it is asked — which is why the provider replays this pact on every pull
 * request (`SanctionsPactProviderVerificationTest`, `@PactFolder`).
 *
 * Two refusals are recorded, not assumed: no identity at all (401), and the fleet's shared client
 * (403). The second is the one this integration depends on — the registry takes the OWNER from
 * the token, so a caller it cannot tell apart from every other service must not get in.
 *
 * **Message.** The event type is part of the contract as message METADATA (`ce-type`), written
 * as a literal on both sides, never as a body field. Each interaction is fed to the REAL consumer
 * as a `ConsumerRecord`, body verbatim and metadata as Kafka headers; the negative cases reuse the
 * same body without the header, with the type moved into the body, and under another owner.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-sanctions-service", pactVersion = PactSpecVersion.V4)
class KybSanctionsMonitoringPactConsumerTest {

    private val findings = mockk<MonitoringFindingService>()
    private val registry = SimpleMeterRegistry()
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var consumer: MonitoredSubjectMatchedConsumer

    @BeforeEach
    fun setUp() {
        val settings = MonitoringSettings(false, BATCH, Duration.ofDays(7), OWNER)
        consumer = MonitoredSubjectMatchedConsumer().also {
            it.findings = findings
            it.settings = settings
            it.metrics = MicrometerKybMonitoringMetrics(registry, settings).apply { register() }
            it.objectMapper = mapper
            it.clock = Clock.systemUTC()
        }
        coEvery { findings.handle(any(), any()) } returns MonitoringFindingOutcome.REVIEW_OPENED
    }

    // ---- HTTP: register -----------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun registerPact(builder: PactBuilder): V4Pact = builder
        .given(STATE_KYB_CALLER)
        .expectsToReceiveHttpInteraction("PUT a related party of an approved business case for ongoing monitoring") { i ->
            i.withRequest { req ->
                req.method("PUT")
                req.path("/api/v3/sanctions/monitored-subjects/$PERSON_REF")
                req.header("Content-Type", "application/json")
                req.body(
                    newJsonBody { o ->
                        o.stringValue("entityType", "INDIVIDUAL")
                        o.stringValue("name", PERSON_NAME)
                    }.build(),
                )
            }
            i.willRespondWith { resp ->
                resp.status(201)
                resp.header("Content-Type", "application/json")
                resp.body(
                    newJsonBody { o ->
                        // The owner is the caller's own client, read from its token by the provider.
                        o.stringValue("ownerService", OWNER)
                        o.stringValue("subjectRef", PERSON_REF)
                    }.build(),
                )
            }
        }
        .toPact()

    @Test
    @PactTestFor(pactMethod = "registerPact")
    fun `a registration is acknowledged for the reference and under the caller's own client`(mockServer: MockServer) {
        val response = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(MonitoredSubjectBody(entityType = "INDIVIDUAL", name = PERSON_NAME)))
            .put(clientPath(PUT::class.java, PERSON_REF))
            .then()
            .statusCode(201)
            .extract().asString()

        val ack = mapper.readValue<MonitoredSubjectAck>(response)
        // What the adapter requires before it records a delivery, and what the consumer compares findings with.
        assertThat(ack.subjectRef).isEqualTo(PERSON_REF)
        assertThat(ack.ownerService).isEqualTo("openbank-kyb")
    }

    // ---- HTTP: withdraw -----------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun withdrawPact(builder: PactBuilder): V4Pact = builder
        .given(STATE_KYB_SUBJECT_REGISTERED)
        .expectsToReceiveHttpInteraction("DELETE a monitored subject whose business case has ended") { i ->
            i.withRequest { req ->
                req.method("DELETE")
                req.path("/api/v3/sanctions/monitored-subjects/$WITHDRAWN_REF")
            }
            i.willRespondWith { resp -> resp.status(204) }
        }
        .toPact()

    @Test
    @PactTestFor(pactMethod = "withdrawPact")
    fun `a withdrawal is answered 204`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).delete(clientPath(DELETE::class.java, WITHDRAWN_REF)).then().statusCode(204)
    }

    // ---- HTTP: refusals -----------------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedPact(builder: PactBuilder): V4Pact = builder
        .given(STATE_NO_IDENTITY)
        .expectsToReceiveHttpInteraction("PUT a monitored subject with no identity at all") { i ->
            i.withRequest { req ->
                req.method("PUT")
                req.path("/api/v3/sanctions/monitored-subjects/$PERSON_REF")
                req.header("Content-Type", "application/json")
                req.body("""{"entityType":"INDIVIDUAL","name":"$PERSON_NAME"}""")
            }
            i.willRespondWith { resp -> resp.status(401) }
        }
        .toPact()

    @Test
    @PactTestFor(pactMethod = "unauthenticatedPact")
    fun `a registration without an identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).contentType("application/json")
            .body("""{"entityType":"INDIVIDUAL","name":"$PERSON_NAME"}""")
            .put(clientPath(PUT::class.java, PERSON_REF)).then().statusCode(401)
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun sharedClientPact(builder: PactBuilder): V4Pact = builder
        .given(STATE_SHARED_CLIENT)
        .expectsToReceiveHttpInteraction("PUT a monitored subject as the fleet's shared client") { i ->
            i.withRequest { req ->
                req.method("PUT")
                req.path("/api/v3/sanctions/monitored-subjects/$PERSON_REF")
                req.header("Content-Type", "application/json")
                req.body("""{"entityType":"INDIVIDUAL","name":"$PERSON_NAME"}""")
            }
            i.willRespondWith { resp -> resp.status(403) }
        }
        .toPact()

    @Test
    @PactTestFor(pactMethod = "sharedClientPact")
    fun `a registration through the shared client is refused with 403`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).contentType("application/json")
            .body("""{"entityType":"INDIVIDUAL","name":"$PERSON_NAME"}""")
            .put(clientPath(PUT::class.java, PERSON_REF)).then().statusCode(403)
    }

    // ---- Message: the finding -----------------------------------------------------------------

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun findingPact(builder: PactBuilder): V4Pact = builder
        .given(STATE_KYB_SUBJECT_MATCHED)
        .expectsToReceiveMessageInteraction("a MONITORED_SUBJECT_MATCHED event") { i ->
            i.withContents { c ->
                c.withMetadata(mapOf("ce-type" to "MONITORED_SUBJECT_MATCHED"))
                c.withContent(
                    newJsonBody { o ->
                        // Pinned: a generated example would make the committed pact differ on every run.
                        o.uuid("findingId", UUID.fromString(FINDING_ID))
                        o.stringValue("ownerService", OWNER)
                        o.stringValue("subjectRef", MATCHED_REF)
                        o.stringType("matchKind", "SANCTIONS")
                        o.stringType("listType", "OFAC_SDN")
                        o.stringType("listEntryRef", "id:pact-monitoring-entry-1")
                        o.stringType("listEntryVersion", "0f0f")
                        o.decimalType("matchScore", 0.95)
                        o.stringType("matchStatus", "POTENTIAL_HIT")
                        o.stringType("detection", "LIST_DELTA")
                    }.build(),
                )
            }
        }
        .toPact()

    @Test
    @PactTestFor(pactMethod = "findingPact", providerType = ProviderType.ASYNCH)
    fun `a finding typed by the ce-type header reaches the case review`(
        messages: List<V4Interaction.AsynchronousMessage>,
    ): Unit = runBlocking {
        val message = messages.single()
        // LITERAL, on purpose — see the class KDoc.
        assertThat(headersOf(message)["ce-type"]).isEqualTo("MONITORED_SUBJECT_MATCHED")
        assertThat(body(message).has("eventType")).isFalse()

        consumer.consume(MonitoredSubjectMatchedWire.record(json(message), headersOf(message)))

        val match = slot<MonitoringMatch>()
        coVerify(exactly = 1) { findings.handle(MATCHED_REF, capture(match)) }
        assertThat(match.captured.findingId).isEqualTo(UUID.fromString(body(message).path("findingId").asText()))
        assertThat(match.captured.matchKind).isEqualTo("SANCTIONS")
        assertThat(match.captured.listType).isEqualTo("OFAC_SDN")
        assertThat(match.captured.listEntryRef).isEqualTo("id:pact-monitoring-entry-1")
        assertThat(match.captured.listEntryVersion).isEqualTo("0f0f")
        assertThat(match.captured.matchScore).isEqualTo(0.95)
        assertThat(match.captured.matchStatus).isEqualTo("POTENTIAL_HIT")
        assertThat(match.captured.detection).isEqualTo("LIST_DELTA")
        assertThat(counted("review_opened")).isEqualTo(1.0)
        assertThat(counted("unrecognised")).isEqualTo(0.0)
    }

    /** Negative: the contract's body alone, without the header, is NOT a finding. */
    @Test
    @PactTestFor(pactMethod = "findingPact", providerType = ProviderType.ASYNCH)
    fun `the same body without the ce-type header is unrecognised`(
        messages: List<V4Interaction.AsynchronousMessage>,
    ): Unit = runBlocking {
        consumer.consume(MonitoredSubjectMatchedWire.record(json(messages.single()), emptyMap()))

        coVerify(exactly = 0) { findings.handle(any(), any()) }
        assertThat(counted("unrecognised")).isEqualTo(1.0)
    }

    /** Negative: the type in the body and none on the header is NOT a finding. */
    @Test
    @PactTestFor(pactMethod = "findingPact", providerType = ProviderType.ASYNCH)
    fun `a type moved into the body is unrecognised`(messages: List<V4Interaction.AsynchronousMessage>): Unit =
        runBlocking {
            val withBodyType = (body(messages.single()) as ObjectNode).put("eventType", "MONITORED_SUBJECT_MATCHED")

            consumer.consume(MonitoredSubjectMatchedWire.record(mapper.writeValueAsString(withBodyType), emptyMap()))

            coVerify(exactly = 0) { findings.handle(any(), any()) }
            assertThat(counted("unrecognised")).isEqualTo(1.0)
        }

    /** Negative: the same finding addressed to another owner is not this service's to act on. */
    @Test
    @PactTestFor(pactMethod = "findingPact", providerType = ProviderType.ASYNCH)
    fun `a finding owned by another service is not acted on`(messages: List<V4Interaction.AsynchronousMessage>): Unit =
        runBlocking {
            val message = messages.single()
            val foreign = (body(message) as ObjectNode).put("ownerService", "openbank-kyc")

            consumer.consume(MonitoredSubjectMatchedWire.record(mapper.writeValueAsString(foreign), headersOf(message)))

            coVerify(exactly = 0) { findings.handle(any(), any()) }
            assertThat(counted("foreign_owner")).isEqualTo(1.0)
        }

    private fun json(message: V4Interaction.AsynchronousMessage): String = message.contents.contents.valueAsString()

    private fun body(message: V4Interaction.AsynchronousMessage) = mapper.readTree(json(message))

    /** Every metadata entry except pact's own content type travels as a Kafka header. */
    private fun headersOf(message: V4Interaction.AsynchronousMessage): Map<String, String> =
        message.contents.metadata.filterKeys { !it.equals("contentType", ignoreCase = true) }
            .mapValues { (_, value) -> value.toString() }

    private fun counted(outcome: String): Double =
        registry.find(MicrometerKybMonitoringMetrics.FINDINGS).tag("outcome", outcome).counter()?.count() ?: -1.0

    /** The path the client would call, read off its annotations: the request side only. */
    private fun clientPath(verb: Class<out Annotation>, subjectRef: String): String {
        val client = SanctionsMonitoringRestClient::class.java
        val operation = client.methods.single { it.isAnnotationPresent(verb) }.getAnnotation(Path::class.java).value
        return (client.getAnnotation(Path::class.java).value + operation).replace("{subjectRef}", subjectRef)
    }

    private companion object {
        const val CONSUMER = "openbank-kyb-service"
        const val PROVIDER = "openbank-sanctions-service"
        const val OWNER = "openbank-kyb"
        const val BATCH = 50

        const val STATE_KYB_CALLER =
            "ongoing monitoring is enabled and the caller is kyb-service under its own service account"
        const val STATE_KYB_SUBJECT_REGISTERED = "kyb-service has registered a subject for ongoing monitoring"
        const val STATE_KYB_SUBJECT_MATCHED = "a subject registered by kyb-service matches a changed list entry"
        const val STATE_SHARED_CLIENT = "the caller presents the fleet's shared client"

        /** The state name every consumer of sanctions-service uses for its missing-identity interaction. */
        const val STATE_NO_IDENTITY = "no valid M2M identity is presented"

        // Opaque references, as kyb mints them. Each interaction has its own so replays do not interfere.
        const val PERSON_REF = "7c1d5b0e-2a54-4c8b-9f0e-5d1e6a7b8c01"
        const val WITHDRAWN_REF = "7c1d5b0e-2a54-4c8b-9f0e-5d1e6a7b8c02"
        const val MATCHED_REF = "7c1d5b0e-2a54-4c8b-9f0e-5d1e6a7b8c03"
        const val PERSON_NAME = "Quentin Exampleson"
        const val FINDING_ID = "3b9a6f52-8c1d-4e7a-b2f0-6d5c4e3f2a10"
    }
}
