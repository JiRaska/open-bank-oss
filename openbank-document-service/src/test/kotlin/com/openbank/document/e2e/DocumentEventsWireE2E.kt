// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.it.PostgresRedisTestResource
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker wire E2E for the document outbox relay (`document-events-out` →
 * `openbank.documents.document.event`). Until this suite the producer side was proven only up to
 * the in-memory connector: `DocumentLifecycleE2E` and `BusinessAgreementIT` switch the channel to
 * `InMemoryConnector`, and `DocumentOutboxTracePropagationIT` — the trace proof — reads the
 * relayed message out of the in-memory sink. Here the real smallrye-kafka producer runs against an
 * isolated Redpanda broker and every scenario reads the topic back with a fresh consumer group:
 *
 *  - Render publishes `document.generated.v1` keyed by the document id, and the record carries the
 *    W3C `traceparent` of the HTTP request that rendered it — the first time trace continuity is
 *    observed on an actual Kafka record rather than inside the JVM's in-memory sink. That matters
 *    because `openbank-audit-service` and `openbank-analytics-sink` both consume this topic and
 *    both attribute business time and ingest lag from what reaches them over the wire.
 *  - A completed signature ceremony publishes `signature-ceremony.completed.v1` keyed by the
 *    ceremony id, and a replayed decision / idempotent re-ensure emits nothing further — the
 *    at-least-once wire discipline consumers rely on for dedupe.
 *
 * What is real and what is stood in for: Postgres, Valkey, the render pipeline, the PAdES client
 * signature + seal adapters, the outbox row, the dispatcher and the smallrye-kafka producer all
 * run for real. The renderer sidecar, product catalog, sca-service consume endpoint and OIDC
 * token endpoint are localhost stubs — the same boundary `BusinessAgreementIT` stands in for;
 * the render/SCA planes belong to their own services and are pinned by their own suites.
 */
@QuarkusTest
@TestProfile(DocumentEventsWireE2E.WireProfile::class)
class DocumentEventsWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "e2e-wire-operator", roles = ["ROLE_OPERATOR"])
    fun `a rendered document emits the generated event on the wire with the request trace`() {
        val code = "WIRE_STM_${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
        val templateId = createAndPublishTemplate(code)
        assertThat(templateId).isNotBlank()

        val rendered: Map<String, Any?> = Given {
            contentType("application/json")
            body(
                """
                {
                  "templateCode": "$code",
                  "data": {"name": "Wire E2E"},
                  "partyRef": "party-wire-${UUID.randomUUID()}"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/documents/render")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        val documentId = rendered["id"] as String

        val record = awaitWireRecord("document.generated.v1", documentId)
        assertTransport(record, documentId, "document.generated.v1")

        val traceparent = header(record, "traceparent")
        assertThat(traceparent)
            .describedAs(
                "the record on the real wire must carry the W3C traceparent of the render request " +
                    "— this is what audit-service's and analytics-sink's instrumentation extracts",
            )
            .matches(TRACEPARENT_PATTERN.pattern)

        val body = mapper.readTree(record.value())
        assertThat(body["documentId"].asText()).isEqualTo(documentId)
        assertThat(body["templateCode"].asText()).isEqualTo(code)
        assertThat(body["templateVersion"].asText()).isEqualTo("1.0")
        assertThat(body["sha256"].asText()).isEqualTo(rendered["sha256"])
        assertThat(body["sourceService"].asText()).isEqualTo("document-service")
        // A DateTimeParseException here fails the test — that IS the assertion that occurredAt
        // is ISO-8601 on the wire (#3914: audit and analytics-sink both read business time from it).
        Instant.parse(body["occurredAt"].asText())

        assertOutboxSent(documentId, "document.generated.v1")
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `a completed signature ceremony emits the completed event keyed by the ceremony`() {
        val caseId = UUID.randomUUID()
        val entityId = UUID.randomUUID()
        val alice = UUID.randomUUID().toString()
        val bob = UUID.randomUUID().toString()

        val created = ensureBusinessAgreement(caseId, entityId, alice, bob)
        val ceremonyId = created["ceremonyId"] as String
        val documentId = created["documentId"] as String

        signDecision(ceremonyId, bob)
        signDecision(ceremonyId, alice)

        val record = awaitWireRecord("signature-ceremony.completed.v1", ceremonyId)
        assertTransport(record, ceremonyId, "signature-ceremony.completed.v1")

        val body = mapper.readTree(record.value())
        assertThat(body["ceremonyId"].asText()).isEqualTo(ceremonyId)
        assertThat(body["documentId"].asText()).isEqualTo(documentId)
        assertThat(body["sourceService"].asText()).isEqualTo("document-service")
        Instant.parse(body["occurredAt"].asText())

        // Negative sentinel for the at-least-once discipline: the decision replay is absorbed by
        // recordDecision's convergent-replay branch (never re-executes the signature act), and the
        // idempotent re-ensure returns the existing ceremony — so after both, the wire must still
        // carry EXACTLY ONE ceremony event for this aggregate. A second one would mean a consumer
        // could observe the completion twice and double-count a legally significant act.
        signDecision(ceremonyId, alice)
        ensureBusinessAgreement(caseId, entityId, alice, bob)
        Thread.sleep(2_000)
        val completions = observed().filter {
            it.key() == ceremonyId && header(it, "ce-type") == "signature-ceremony.completed.v1"
        }
        assertThat(completions)
            .describedAs("a replayed decision and an idempotent re-ensure must not re-emit the ceremony")
            .hasSize(1)

        assertOutboxSent(ceremonyId, "signature-ceremony.completed.v1")
    }

    // ── REST flows ───────────────────────────────────────────────────────────────

    private fun createAndPublishTemplate(code: String): String {
        val created: Map<String, Any?> = Given {
            contentType("application/json")
            body(
                """
                {
                  "code": "$code",
                  "version": "1.0",
                  "name": "Wire Template $code",
                  "bodyHtml": "<h1>Statement</h1>",
                  "locale": "en",
                  "classification": "restricted",
                  "createdBy": "e2e-wire"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/documents/templates")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        val templateId = created["id"] as String
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/documents/templates/$templateId/publish")
        } Then {
            statusCode(200)
        }
        return templateId
    }

    private fun ensureBusinessAgreement(caseId: UUID, entityId: UUID, alice: String, bob: String): Map<String, Any?> =
        Given {
            contentType("application/json")
            body(
                """
                {
                  "caseId": "$caseId", "entityPartyId": "$entityId", "lang": "cs",
                  "entity": {"name": "Wire Stavby s.r.o.", "ico": "27074358",
                             "seat": "Jindřišská 16, 110 00 Praha 1",
                             "legalForm": "společnost s ručením omezeným"},
                  "representatives": [{"partyRef": "$alice", "name": "Petr Horák", "role": "jednatel"}],
                  "signers": [{"partyRef": "$alice", "name": "Petr Horák", "role": "jednatel"},
                              {"partyRef": "$bob", "name": "Eva Horáková", "role": "jednatelka"}],
                  "signingRule": "Za společnost jednají dva jednatelé společně.",
                  "product": {"code": "prod-004", "name": "Business Current Account", "currency": "EUR"}
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/business-agreements")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }

    private fun signDecision(ceremonyId: String, partyRef: String) {
        Given {
            contentType("application/json")
            body("""{"partyRef":"$partyRef","decision":"SIGNED","evidenceRef":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/signature-ceremonies/$ceremonyId/decisions")
        } Then {
            statusCode(200)
        }
    }

    // ── wire read-back ───────────────────────────────────────────────────────────

    /**
     * Polls the topic with a fresh consumer group until a record with [expectedType] (`ce-type`
     * header) and key [aggregateId] arrives. Everything polled is appended to [OBSERVED] so later
     * negative assertions see the full window.
     */
    private fun awaitWireRecord(expectedType: String, aggregateId: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { it.key() == aggregateId && header(it, "ce-type") == expectedType }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${header(it, "ce-type")}" }
        throw AssertionError("no $expectedType on the wire for $aggregateId within 45s; observed: [$seen]")
    }

    private fun assertTransport(record: ConsumerRecord<String, String>, aggregateId: String, type: String) {
        assertThat(record.topic()).isEqualTo(TOPIC)
        assertThat(record.key())
            .describedAs("the outbox partition key IS the aggregate id — consumers key idempotently by it")
            .isEqualTo(aggregateId)
        assertThat(header(record, "ce-type")).isEqualTo(type)
        val eventId = header(record, "ce-id")
        assertThat(eventId)
            .describedAs("the CloudEvents id header must be present on every outbox-relayed record")
            .isNotBlank()
        assertThat(header(record, "idempotency-key"))
            .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe")
            .isEqualTo(eventId)
    }

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    private fun header(record: ConsumerRecord<String, String>, name: String): String? =
        record.headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    // ── outbox table read-back ───────────────────────────────────────────────────

    private fun assertOutboxSent(aggregateId: String, eventType: String) {
        val deadline = Instant.now().plusSeconds(15)
        while (Instant.now().isBefore(deadline)) {
            val status = outboxStatus(aggregateId, eventType)
            if (status == "SENT") return
            Thread.sleep(200)
        }
        throw AssertionError("document_outbox row for $aggregateId/$eventType never reached SENT")
    }

    private fun outboxStatus(aggregateId: String, eventType: String): String? =
        dataSource.connection.use { queryStatus(it, aggregateId, eventType) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String, eventType: String): String? {
        val ps = c.prepareStatement("select status from document_outbox where aggregate_id = ? and event_type = ?")
        ps.use {
            it.setObject(1, UUID.fromString(aggregateId))
            it.setString(2, eventType)
            val rs = it.executeQuery()
            rs.use { r -> return if (r.next()) r.getString(1) else null }
        }
    }

    class WireProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        // The infra comes through the PROFILE, not class-level @QuarkusTestResource, on purpose:
        // config returned by restrictToAnnotatedClass resources is not augmentation-visible, so
        // Kafka Dev Services launched anyway (measured in consent-service: an apache/kafka-native
        // container took the producer while the tap consumer read the Redpanda bootstrap from the
        // same map) and the wire stayed empty. Profile test resources are visible when the
        // profile's application is built.
        override fun testResources() = listOf(
            TestResourceEntry(PostgresRedisTestResource::class.java),
            TestResourceEntry(DocumentWireRedpandaResource::class.java),
            TestResourceEntry(WireRemoteStubs::class.java),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // A profile forces this class its own Quarkus boot; pin an ephemeral port so the
            // restart cannot die on QuarkusBindException against the still-bound 8081.
            "quarkus.http.test-port" to "0",
            // Belt and braces on top of the resource's global kill switch: never let Dev Services
            // stand up a second broker for this boot.
            "quarkus.kafka.devservices.enabled" to "false",
            // The dispatcher only runs when the scheduler does; the trace contract IT shows the
            // fleet habit of disabling it in tests. Dispatch stays on — main's
            // openbank.outbox.dispatch-enabled=true is inherited — but the tick is shortened so
            // the wire assertion does not wait five seconds per event.
            "quarkus.scheduler.enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
            // The traceparent header assertion needs the SDK active to instrument the render
            // request; keep it on even if a future default switches it off in tests.
            "quarkus.otel.sdk.disabled" to "false",
        )
    }

    companion object {
        private const val TOPIC = "openbank.documents.document.event"
        private val TRACEPARENT_PATTERN = Regex("""\p{XDigit}{2}-[0-9a-f]{32}-[0-9a-f]{16}-\p{XDigit}{2}""")

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic()
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "document-wire-read-${UUID.randomUUID()}",
                "auto.offset.reset" to "earliest",
                "enable.auto.commit" to "true",
                "key.deserializer" to StringDeserializer::class.qualifiedName,
                "value.deserializer" to StringDeserializer::class.qualifiedName,
            )
            val consumer = KafkaConsumer<String, String>(props)
            consumer.subscribe(listOf(TOPIC))
            Thread {
                while (true) {
                    consumer.poll(Duration.ofMillis(500)).forEach { OBSERVED.add(it) }
                }
            }.apply {
                isDaemon = true
                name = "document-wire-tap"
                start()
            }
        }

        private fun ensureTopic() {
            Admin.create(mapOf("bootstrap.servers" to bootstrapServers())).use { admin ->
                try {
                    admin.createTopics(listOf(NewTopic(TOPIC, 3, 1))).all().get(30, TimeUnit.SECONDS)
                } catch (e: ExecutionException) {
                    if (e.cause !is TopicExistsException) throw e
                }
            }
        }

        private fun bootstrapServers(): String =
            ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
    }
}

/**
 * Localhost stubs for everything outside this JVM in the ceremony journey — exactly the boundary
 * `BusinessAgreementIT` stands in for: the weasyprint renderer sidecar, the product catalog, the
 * sca-service challenge-consume endpoint (which promotes the challenge and enforces RTS Art. 5
 * dynamic linking server-side), and the OIDC token endpoint the SCA REST client authenticates
 * against. The production PAdES client-signature and seal adapters run for real against the
 * stub-rendered PDFs.
 */
class WireRemoteStubs : QuarkusTestResourceLifecycleManager {

    private lateinit var http: com.sun.net.httpserver.HttpServer

    override fun start(): Map<String, String> {
        http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/render") { ex ->
            val html = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            respond(ex, 200, "application/pdf", pdf(html))
        }
        http.createContext("/api/v1/products") { ex ->
            respond(ex, 200, "application/json", CATALOG_PRODUCT.toByteArray())
        }
        http.createContext("/api/v1/sca/challenges") { ex ->
            val challengeId = ex.requestURI.path.split('/')[5]
            val request = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val partyId = Regex("\"partyId\"\\s*:\\s*\"([^\"]+)\"").find(request)!!.groupValues[1]
            respond(
                ex,
                200,
                "application/json",
                """{"id":"$challengeId","partyId":"$partyId","challengeType":"DOCUMENT_SIGNING","status":"CONSUMED"}"""
                    .toByteArray(),
            )
        }
        http.createContext("/token") { ex ->
            respond(
                ex,
                200,
                "application/json",
                """{"access_token":"t","token_type":"Bearer","expires_in":300}""".toByteArray(),
            )
        }
        http.executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        http.start()
        val base = "http://127.0.0.1:${http.address.port}"
        return mapOf(
            "openbank.render.profile" to "weasyprint",
            "openbank.render.weasyprint-url" to base,
            "quarkus.rest-client.product-catalog-api.url" to base,
            "quarkus.rest-client.sca-service.url" to base,
            "quarkus.oidc-client.auth-server-url" to base,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to "/token",
        )
    }

    override fun stop() = http.stop(0)

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun pdf(html: String): ByteArray = org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
        doc.addPage(org.apache.pdfbox.pdmodel.PDPage())
        doc.documentInformation.title = java.security.MessageDigest.getInstance("SHA-256")
            .digest(html.toByteArray()).joinToString("") { "%02x".format(it) }
        java.io.ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private companion object {
        val CATALOG_PRODUCT = """
            {"id":"prod-004","code":"CURRENT_BUSINESS","name":"Business Current Account","currency":"EUR",
             "termsAndConditions":[],
             "fees":[{"name":"Monthly Fee","type":"MONTHLY","amount":19.99,"currency":"EUR","frequency":"MONTHLY"}]}
        """.trimIndent()
    }
}

/**
 * Isolated Redpanda (Kafka API) for the wire suite: the outgoing `document-events-out` channel
 * runs the real smallrye-kafka producer against it, and the test reads the topic back with a
 * plain consumer. Returning the bootstrap servers as config properties (not YAML) is the
 * #686-safe idiom the fleet's other broker resources use.
 */
class DocumentWireRedpandaResource : QuarkusTestResourceLifecycleManager {

    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val rp = RedpandaContainer(
            DockerImageName.parse(REDPANDA_IMAGE)
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        try {
            rp.start()
        } catch (e: Exception) {
            throw TestAbortedException("Redpanda failed to start — skipping IT: ${e.message}", e)
        }
        redpanda = rp
        TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started")
        return mapOf(
            "kafka.bootstrap.servers" to rp.bootstrapServers,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to rp.bootstrapServers,
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        redpanda?.let {
            it.stop()
            TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped")
        }
    }

    private companion object {
        const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
    }
}
