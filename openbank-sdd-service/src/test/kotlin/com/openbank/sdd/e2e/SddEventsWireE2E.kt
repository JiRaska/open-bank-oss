// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker wire E2E for the SDD outbox relay (`sdd-events-out` → `openbank.sdd.event`).
 * The sibling suites stop at durable local state: SddMandateLifecycleE2E and the outbox ITs
 * switch BOTH the outgoing channel and the self-consumption channel to the in-memory
 * connector, so the `sdd_outbox` rows were asserted in Postgres but nothing ever reached a
 * broker. Here the whole loop is real: the mandate is registered and the collection
 * authorised over the real HTTP surface, the use case appends the outbox rows in the same
 * transaction, the real `@Scheduled` dispatcher drains them to a Redpanda container, and a
 * fresh consumer group reads the records back.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: key == mandateId,
 * `ce-type` = the dotted outbox event type column (`sdd.mandate.registered.v1` /
 * `sdd.collection.authorised.v1`, also duplicated into the JSON body, which is the
 * consumer-side discriminator the self-consumer filters on), `ce-id` present and
 * `idempotency-key` == ce-id, so an at-least-once consumer can dedupe (ADR-0050 N3).
 *
 * Payload contract: the hand-built envelopes `SddMandateService` writes. The application layer
 * does NOT put `sourceService` in the payload — the publisher stamps it on at dispatch time
 * (`KafkaSddOutboxEventPublisher`), which is exactly what upgrades the row to
 * `AttributionSource.EVENT` for audit-service; the suite pins the stamped value on the wire.
 *
 * The second test closes the loop producer-only suites cannot: the channel
 * `sdd-collection-authorised-in` self-consumes the authorised collection off this service's
 * own topic and books the debtor-side debit through transaction-service. With no
 * transaction-service in the test JVM the debit fails after `EventRetry`'s attempts and the
 * record must land on the EXPLICIT DLQ topic `openbank.dlq.sdd.sdd-collection-authorised-in`
 * — the dead-letter wiring #5745 demands (nested `dead-letter-queue.topic`, a dotted leaf
 * key would be quoted by SmallRye's YAML source and never read, #686). SmallRye's
 * dead-letter producer re-serializes the payload as a JSON string, so the DLQ value is
 * double-encoded (measured on the clearing wire suite) — the test unwraps the textual layer
 * before reading fields.
 *
 * Boot hazards pinned in the profile, each measured on the earlier wire suites: sdd ships
 * `authz.enforce: true` (fail-closed), so the profile reuses the `OpaBundleMirrorResource`
 * localhost OPA stand-in; its `%test` block disables the @Scheduled tick (ADR-0050), so the
 * profile re-enables the scheduler; the hibernate-orm/jdbc switches for @Scheduled Panache
 * work; a 1 s outbox tick; and BOTH connectors pinned back to smallrye-kafka because the
 * lifecycle E2E switches outgoing AND incoming to in-memory and only clear()s messages, not
 * the switch, on stop.
 */
@QuarkusTest
@TestProfile(SddEventsWireE2E.WireProfile::class)
class SddEventsWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = "wire-operator-1", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "wire-operator-1"),
            Claim(key = "preferred_username", value = "wire-operator-1"),
        ],
    )
    fun `mandate registration and collection authorisation land on the wire`() {
        val accountId = UUID.randomUUID()
        val umr = "WIRE-UMR-${UUID.randomUUID().toString().take(8)}"

        val registered = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(registerBody(accountId, umr))
            .post("/api/v1/sdd/mandates")
            .then()
            .extract()
            .response()
        assertThat(registered.statusCode)
            .describedAs("POST /api/v1/sdd/mandates: %s", registered.body.asString())
            .isEqualTo(201)
        val mandateId = registered.jsonPath().getString("id")

        // ── sdd.mandate.registered.v1 ──────────────────────────────────────────────
        val registeredRecord = awaitWire(MANDATE_REGISTERED_EVENT, mandateId)
        assertTransport(registeredRecord, mandateId, MANDATE_REGISTERED_EVENT)
        val registeredBody = mapper.readTree(registeredRecord.value())
        assertThat(registeredBody["eventType"].asText()).isEqualTo(MANDATE_REGISTERED_EVENT)
        assertThat(registeredBody["mandateId"].asText()).isEqualTo(mandateId)
        assertThat(registeredBody["accountId"].asText()).isEqualTo(accountId.toString())
        assertThat(registeredBody["umr"].asText()).isEqualTo(umr)
        assertThat(registeredBody["scheme"].asText()).isEqualTo("CORE")
        assertThat(registeredBody["status"].asText()).isEqualTo("ACTIVE")

        // ── sdd.collection.authorised.v1 ───────────────────────────────────────────
        val authorised = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(authoriseBody(umr))
            .post("/api/v1/sdd/collections/authorise")
            .then()
            .extract()
            .response()
        assertThat(authorised.statusCode)
            .describedAs("POST /api/v1/sdd/collections/authorise: %s", authorised.body.asString())
            .isEqualTo(200)
        assertThat(authorised.jsonPath().getString("decision")).isEqualTo("ACCEPT")

        val authorisedRecord = awaitWire(COLLECTION_AUTHORISED_EVENT, mandateId)
        assertTransport(authorisedRecord, mandateId, COLLECTION_AUTHORISED_EVENT)
        val authorisedBody = mapper.readTree(authorisedRecord.value())
        assertThat(authorisedBody["eventType"].asText()).isEqualTo(COLLECTION_AUTHORISED_EVENT)
        assertThat(authorisedBody["mandateId"].asText()).isEqualTo(mandateId)
        assertThat(authorisedBody["umr"].asText()).isEqualTo(umr)
        assertThat(authorisedBody["debtorIban"].asText()).isEqualTo(DEBTOR_IBAN)
        assertThat(authorisedBody["amount"].decimalValue()).isEqualByComparingTo("250.00")
        assertThat(authorisedBody["currency"].asText()).isEqualTo("EUR")
        assertThat(authorisedBody["dueDate"].asText()).isNotBlank()
        // The application-layer envelope does not carry sourceService — the publisher stamps it
        // onto the payload at dispatch time (KafkaSddOutboxEventPublisher), which is what makes
        // the row AttributionSource.EVENT for audit-service. Pinned on the wire.
        assertThat(authorisedBody["sourceService"].asText())
            .describedAs("producer attribution stamped by the publisher, read as the strongest claim (#3994/#5256)")
            .isEqualTo("sdd-service")

        // The relay marked both rows SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(mandateId, MANDATE_REGISTERED_EVENT)
        assertOutboxSent(mandateId, COLLECTION_AUTHORISED_EVENT)
    }

    @Test
    @TestSecurity(user = "wire-operator-2", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "wire-operator-2"),
            Claim(key = "preferred_username", value = "wire-operator-2"),
        ],
    )
    fun `the failed debit self consumption dead letters to the explicit dlq topic`() {
        val umr = "WIRE-UMR-${UUID.randomUUID().toString().take(8)}"
        val registered = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(registerBody(UUID.randomUUID(), umr))
            .post("/api/v1/sdd/mandates")
            .then()
            .extract()
            .response()
        assertThat(registered.statusCode).isEqualTo(201)
        val mandateId = registered.jsonPath().getString("id")

        val authorised = RestAssured.given()
            .contentType(ContentType.JSON)
            .body(authoriseBody(umr))
            .post("/api/v1/sdd/collections/authorise")
            .then()
            .extract()
            .response()
        assertThat(authorised.statusCode).isEqualTo(200)
        assertThat(authorised.jsonPath().getString("decision")).isEqualTo("ACCEPT")

        // The self-consumer read the authorised collection off this service's own topic, the
        // debit through transaction-service failed after EventRetry's attempts (no
        // transaction-service in the test JVM), and the connector must park the record on the
        // EXPLICIT DLQ topic (#5745).
        val deadline = Instant.now().plusSeconds(90)
        while (Instant.now().isBefore(deadline)) {
            val found = dlqObserved().firstOrNull { record ->
                header(record, "ce-type") == COLLECTION_AUTHORISED_EVENT && record.key() == mandateId
            }
            if (found != null) {
                // SmallRye's dead-letter producer re-serializes the payload as a JSON string,
                // so the DLQ value is double-encoded — unwrap one textual layer.
                val first = mapper.readTree(found.value())
                val body = if (first.isTextual) mapper.readTree(first.asText()) else first
                assertThat(body["umr"].asText()).isEqualTo(umr)
                assertThat(body["amount"].decimalValue()).isEqualByComparingTo("250.00")
                return
            }
            Thread.sleep(300)
        }
        throw AssertionError("collection.authorised for mandate $mandateId never reached the DLQ topic within 90s")
    }

    // ── HTTP driving ─────────────────────────────────────────────────────────────

    private fun registerBody(accountId: UUID, umr: String): String =
        """
        {
          "accountId": "$accountId",
          "debtorIban": "$DEBTOR_IBAN",
          "creditorIdentifier": "$CREDITOR_ID",
          "umr": "$umr",
          "scheme": "CORE",
          "sequenceType": "RCUR",
          "creditorName": "Wire Creditor GmbH",
          "debtorName": "Wire Debtor s.r.o.",
          "signatureDate": "${LocalDate.now().minusDays(30)}"
        }
        """.trimIndent()

    private fun authoriseBody(umr: String): String =
        """
        {
          "creditorIdentifier": "$CREDITOR_ID",
          "umr": "$umr",
          "scheme": "CORE",
          "sequenceType": "RCUR",
          "amount": 250.00,
          "currency": "EUR",
          "dueDate": "${LocalDate.now().plusDays(3)}",
          "controls": {
            "blockAll": false,
            "blockedCreditors": [],
            "maxAmountPerCollection": null
          }
        }
        """.trimIndent()

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitWire(eventType: String, key: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { record ->
                header(record, "ce-type") == eventType && record.key() == key
            }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${header(it, "ce-type")}" }
        throw AssertionError("no $eventType for $key on the wire within 45s; observed: [$seen]")
    }

    private fun assertTransport(record: ConsumerRecord<String, String>, key: String, eventType: String) {
        assertThat(record.topic()).isEqualTo(EVENTS_TOPIC)
        assertThat(record.key()).isEqualTo(key)
        assertThat(header(record, "ce-type")).isEqualTo(eventType)
        val eventId = header(record, "ce-id")
        assertThat(eventId).isNotBlank()
        assertThat(header(record, "idempotency-key"))
            .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe (ADR-0050 N3)")
            .isEqualTo(eventId)
    }

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    private fun dlqObserved(): List<ConsumerRecord<String, String>> = DLQ_OBSERVED

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
        throw AssertionError("sdd_outbox row for $aggregateId/$eventType never reached SENT")
    }

    private fun outboxStatus(aggregateId: String, eventType: String): String? =
        dataSource.connection.use { queryStatus(it, aggregateId, eventType) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String, eventType: String): String? {
        val ps = c.prepareStatement(
            "select status from sdd_outbox where aggregate_id = ? and event_type = ?",
        )
        ps.use {
            it.setObject(1, UUID.fromString(aggregateId))
            it.setString(2, eventType)
            val rs = it.executeQuery()
            rs.use { r -> return if (r.next()) r.getString(1) else null }
        }
    }

    class WireProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            TestResourceEntry(
                PostgresRedpandaRedisTestResource::class.java,
                mapOf("db" to "openbank_sdd_it"),
            ),
            // sdd ships authz.enforce=true (fail-closed) — the localhost OPA stand-in from the
            // lifecycle E2E, mirrored here so the real HTTP surface answers 200/403 the same way.
            TestResourceEntry(SddMandateLifecycleE2E.OpaBundleMirrorResource::class.java),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // Restart-safe ephemeral port for this profile's own Quarkus boot.
            "quarkus.http.test-port" to "0",
            // sdd's %test disables the @Scheduled outbox tick (ADR-0050) so the integration
            // tests drive dispatcher.dispatch() explicitly — the wire suite needs the real tick.
            "quarkus.scheduler.enabled" to "true",
            // Dispatch stays on so the rows reach the broker; the tick is shortened so the wire
            // assertion does not wait five seconds per event.
            "openbank.outbox.dispatch-enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
            // The two switches the earlier wire suites measured as required for @Scheduled
            // Panache work: without them the reactive SessionFactory lazy synthetic bean cannot
            // be created outside an HTTP request.
            "quarkus.hibernate-orm.enabled" to "true",
            "quarkus.datasource.jdbc.enabled" to "true",
            // Suite-order poisoning guard: SddMandateLifecycleE2E switches BOTH sdd-events-out
            // and sdd-collection-authorised-in to the in-memory connector and only clear()s
            // messages, not the switch, on stop. In a full-module Gradle run those switches
            // survive the profile restart into this suite, so both connectors are pinned back
            // to smallrye-kafka.
            "mp.messaging.outgoing.sdd-events-out.connector" to "smallrye-kafka",
            "mp.messaging.incoming.sdd-collection-authorised-in.connector" to "smallrye-kafka",
        )
    }

    companion object {
        private const val EVENTS_TOPIC = "openbank.sdd.event"
        private const val DLQ_TOPIC = "openbank.dlq.sdd.sdd-collection-authorised-in"
        private const val MANDATE_REGISTERED_EVENT = "sdd.mandate.registered.v1"
        private const val COLLECTION_AUTHORISED_EVENT = "sdd.collection.authorised.v1"
        private const val DEBTOR_IBAN = "DE89370400440532013000"
        private const val CREDITOR_ID = "DE98ZZZ01234567890"

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()
        private val DLQ_OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            DLQ_OBSERVED.clear()
            ensureTopic(EVENTS_TOPIC)
            ensureTopic(DLQ_TOPIC)
            tap(EVENTS_TOPIC, OBSERVED, "sdd-wire-tap")
            tap(DLQ_TOPIC, DLQ_OBSERVED, "sdd-dlq-tap")
        }

        private fun tap(
            topic: String,
            sink: CopyOnWriteArrayList<ConsumerRecord<String, String>>,
            threadName: String,
        ) {
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "sdd-wire-read-${UUID.randomUUID()}",
                "auto.offset.reset" to "earliest",
                "enable.auto.commit" to "true",
                "key.deserializer" to StringDeserializer::class.qualifiedName,
                "value.deserializer" to StringDeserializer::class.qualifiedName,
            )
            val consumer = KafkaConsumer<String, String>(props)
            consumer.subscribe(listOf(topic))
            Thread {
                while (true) {
                    consumer.poll(Duration.ofMillis(500)).forEach { sink.add(it) }
                }
            }.apply {
                isDaemon = true
                name = threadName
                start()
            }
        }

        private fun ensureTopic(topic: String) {
            // Under full-fork load the broker container reports "started" before its Kafka API
            // answers (measured on the sepa-payment suite: a bare 30 s createTopics timed out
            // against a live broker next to a 52 s Quarkus boot). Retry with fresh clients.
            val deadline = Instant.now().plusSeconds(120)
            while (true) {
                val outcome = runCatching { createTopicOnce(topic) }
                if (outcome.isSuccess) return
                if (Instant.now().isAfter(deadline)) {
                    throw AssertionError(
                        "broker never accepted topic creation within 120s",
                        outcome.exceptionOrNull(),
                    )
                }
                Thread.sleep(2_000)
            }
        }

        private fun createTopicOnce(topic: String) {
            Admin.create(mapOf("bootstrap.servers" to bootstrapServers())).use { admin ->
                try {
                    admin.createTopics(listOf(NewTopic(topic, 3, 1))).all().get(20, TimeUnit.SECONDS)
                } catch (e: ExecutionException) {
                    if (e.cause !is TopicExistsException) throw e
                }
            }
        }

        private fun bootstrapServers(): String =
            ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
    }
}
