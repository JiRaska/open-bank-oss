// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.consent.application.port.out.ScaChallengeSnapshot
import com.openbank.consent.it.ConsentPostgresRedisTestResource
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
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker wire E2E for the consent outbox relay (`consent-events-out` →
 * `openbank.consent.events`). Until this suite the producer side was proven only up to the
 * outbox TABLE: both outbox ITs switch the channel to the in-memory connector and assert the
 * `consent_outbox` row, so the actual wire — the record key being the aggregate id, the
 * `ce-type` / event-id / idempotency-key headers, the serialised event body with
 * `sourceService` and the ADR-0205 `accountAccess` flag, and the relay marking the row SENT —
 * had never been observed end to end. Here the dispatcher ticks for real against a real
 * Redpanda broker and every scenario reads the topic back with a fresh consumer group.
 *
 * What is real and what is stood in for: Postgres, Valkey, the state machine, the outbox row,
 * the dispatcher and the smallrye-kafka producer all run for real; the SCA challenge plane is
 * the same deterministic `@Alternative` [ConsentLifecycleE2E.E2EScaChallengeClient] the
 * lifecycle suite uses (the challenge plane belongs to sca-service, pinned by its own contract
 * suite). The broker is Redpanda over the Kafka API, per the fleet's other real-broker E2Es.
 */
@QuarkusTest
@TestProfile(ConsentEventsWireE2E.WireProfile::class)
class ConsentEventsWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var sca: ConsentLifecycleE2E.E2EScaChallengeClient

    private val mapper = ObjectMapper()

    // ── scenarios ────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a GDPR-only marketing consent emits ConsentGranted born ACTIVE with accountAccess false`() {
        val partyId = UUID.randomUUID()
        val consentId = grantMarketing(partyId)

        val record = awaitWireRecord("ConsentGranted", consentId)
        assertTransport(record, consentId, "ConsentGranted")

        val body = mapper.readTree(record.value())
        assertThat(body["eventType"].asText()).isEqualTo("ConsentGranted")
        assertThat(body["aggregateType"].asText()).isEqualTo("Consent")
        assertThat(body["partyId"].asText()).isEqualTo(partyId.toString())
        assertThat(body["granteeId"].asText()).isEqualTo(MARKETING_GRANTEE)
        assertThat(body["sourceService"].asText()).isEqualTo("consent-service")
        assertThat(body["accountAccess"].asBoolean())
            .describedAs("ADR-0205 D1/#8432: a marketing preference grants no account access")
            .isFalse()
        assertThat(body["version"].asLong()).isEqualTo(1)
        // A DateTimeParseException here fails the test — that IS the assertion that occurredAt
        // is ISO-8601 on the wire.
        OffsetDateTime.parse(body["occurredAt"].asText())

        assertOutboxSent(consentId, "ConsentGranted")
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `an activated PSD2 grant revoked by the customer emits ConsentRevoked with accountAccess true`() {
        val partyId = UUID.randomUUID()
        val consentId = grantPsd2(partyId)
        activate(consentId, partyId)

        Given {
            contentType("application/json")
            body("""{"reason":"customer withdrew PSD2 access"}""")
        } When {
            delete("/api/v1/consents/$consentId?partyId=$partyId")
        } Then {
            statusCode(200)
        }

        val record = awaitWireRecord("ConsentRevoked", consentId)
        assertTransport(record, consentId, "ConsentRevoked")

        val body = mapper.readTree(record.value())
        assertThat(body["accountAccess"].asBoolean())
            .describedAs("a third party losing access to accounts IS a security event consumers must hear about")
            .isTrue()
        assertThat(body["reason"].asText()).isEqualTo("customer withdrew PSD2 access")
        assertThat(body["scopes"].map { it.asText() }).containsExactlyInAnyOrder("ACCOUNTS_READ", "BALANCES_READ")

        assertOutboxSent(consentId, "ConsentRevoked")
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `re-granting supersedes and the wire carries ConsentSuperseded with supersededBy`() {
        val partyId = UUID.randomUUID()
        val grantee = "tpp-wire-${UUID.randomUUID()}"
        val oldId = grantPsd2(partyId, grantee)
        activate(oldId, partyId)

        // Supersession happens at ACTIVATION of the replacement, not at creation (ConsentService
        // KDoc: the old grant must stand until the new one is genuinely ACTIVE — retiring it at
        // creation would leave the customer with no consent if SCA then failed). So a bare
        // re-grant emits nothing; completing SCA on the new consent is what publishes both the
        // replacement's ConsentGranted and the old one's ConsentSuperseded.
        val newId = grantPsd2(partyId, grantee)
        activate(newId, partyId)

        val superseded = awaitWireRecord("ConsentSuperseded", oldId)
        assertTransport(superseded, oldId, "ConsentSuperseded")
        val body = mapper.readTree(superseded.value())
        assertThat(body["supersededBy"].asText()).isEqualTo(newId)
        assertThat(body["granteeId"].asText()).isEqualTo(grantee)

        // The replacement grant is on the wire too, keyed by the NEW consent id.
        awaitWireRecord("ConsentGranted", newId)

        // Sentinel discipline for the negative: the Granted above was published AFTER the
        // supersession was processed, so by now a ConsentRevoked for the old id would have been
        // observable — supersession must not read as a withdrawal (#6487).
        Thread.sleep(2_000)
        assertThat(observed())
            .describedAs("supersession continues access under the replacement — never a revocation")
            .noneMatch { it.key() == oldId && header(it, "ce-type") == "ConsentRevoked" }
    }

    // ── REST flows ───────────────────────────────────────────────────────────────

    private fun grantMarketing(partyId: UUID): String = Given {
        contentType("application/json")
        body(grantBody(partyId, MARKETING_GRANTEE, listOf("MARKETING_COMMS_EMAIL"), emptyList()))
    } When {
        post("/api/v1/consents")
    } Then {
        statusCode(201)
        body("status", org.hamcrest.Matchers.equalTo("ACTIVE"))
    } Extract {
        jsonPath().getString("id")
    }

    private fun grantPsd2(partyId: UUID, grantee: String = "tpp-wire-aisp"): String = Given {
        contentType("application/json")
        body(grantBody(partyId, grantee, listOf("ACCOUNTS_READ", "BALANCES_READ"), listOf(IBAN)))
    } When {
        post("/api/v1/consents")
    } Then {
        statusCode(201)
        body("status", org.hamcrest.Matchers.equalTo("PENDING_SCA"))
    } Extract {
        jsonPath().getString("id")
    }

    private fun activate(consentId: String, partyId: UUID) {
        val challengeId = UUID.randomUUID()
        sca.challenges[challengeId] = ScaChallengeSnapshot(
            challengeId,
            partyId,
            "CONSENT_GRANT",
            "COMPLETED",
        )
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/consents/$consentId/activate?scaSessionId=$challengeId")
        } Then {
            statusCode(200)
            body("status", org.hamcrest.Matchers.equalTo("ACTIVE"))
        }
    }

    private fun grantBody(party: UUID, grantee: String, scopes: List<String>, ibans: List<String>): String {
        val ibanJson = ibans.joinToString(",") { "\"$it\"" }
        return """
        {"partyId":"$party","granteeId":"$grantee","granteeType":"TPP","granteeName":"Wire E2E TPP",
        "scopes":[${scopes.joinToString(",") { "\"$it\"" }}],"accountIbans":[$ibanJson],
        "validTo":"${OffsetDateTime.now().plusDays(30)}","redirectUri":null}
        """.trimIndent()
    }

    // ── wire read-back ───────────────────────────────────────────────────────────

    /**
     * Polls the topic with a fresh consumer group until a record with [expectedType] (`ce-type`
     * header) and key [consentId] arrives. Everything polled is appended to [observed] so later
     * negative assertions see the full window.
     */
    private fun awaitWireRecord(expectedType: String, consentId: String): ConsumerRecord<String, String> {
        val deadline = OffsetDateTime.now().plusSeconds(45)
        while (OffsetDateTime.now().isBefore(deadline)) {
            val found = observed().firstOrNull { it.key() == consentId && header(it, "ce-type") == expectedType }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${header(it, "ce-type")}" }
        throw AssertionError("no $expectedType on the wire for consent $consentId within 45s; observed: [$seen]")
    }

    private fun assertTransport(record: ConsumerRecord<String, String>, consentId: String, type: String) {
        assertThat(record.topic()).isEqualTo(TOPIC)
        assertThat(record.key())
            .describedAs("the outbox partition key IS the aggregate id — consumers key idempotently by it")
            .isEqualTo(consentId)
        assertThat(header(record, "ce-type")).isEqualTo(type)
        val eventId = header(record, "ce-id")
        assertThat(eventId)
            .describedAs("the CloudEvents id header must be present on every outbox-relayed record")
            .isNotBlank()
        assertThat(header(record, "idempotency-key"))
            .describedAs("N3: the idempotency key is the event id, so at-least-once consumers can dedupe")
            .isEqualTo(eventId)
    }

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    private fun header(record: ConsumerRecord<String, String>, name: String): String? =
        record.headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    // ── outbox table read-back ───────────────────────────────────────────────────

    private fun assertOutboxSent(consentId: String, eventType: String) {
        val deadline = OffsetDateTime.now().plusSeconds(15)
        while (OffsetDateTime.now().isBefore(deadline)) {
            val status = outboxStatus(consentId, eventType)
            if (status == "SENT") return
            Thread.sleep(200)
        }
        throw AssertionError("consent_outbox row for $consentId/$eventType never reached SENT")
    }

    private fun outboxStatus(consentId: String, eventType: String): String? =
        dataSource.connection.use { queryStatus(it, consentId, eventType) }

    private fun queryStatus(c: java.sql.Connection, consentId: String, eventType: String): String? {
        val ps = c.prepareStatement("select status from consent_outbox where aggregate_id = ? and event_type = ?")
        ps.use {
            it.setObject(1, UUID.fromString(consentId))
            it.setString(2, eventType)
            val rs = it.executeQuery()
            rs.use { r -> return if (r.next()) r.getString(1) else null }
        }
    }

    class WireProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
            ConsentLifecycleE2E.E2EScaChallengeClient::class.java,
        )

        // The infra comes through the PROFILE, not class-level @QuarkusTestResource, on purpose:
        // config returned by restrictToAnnotatedClass resources is not augmentation-visible, so
        // Kafka Dev Services launched anyway (measured: an apache/kafka-native container took
        // the producer while the tap consumer read the Redpanda bootstrap from the same map) and
        // the wire stayed empty. Profile test resources are visible when the profile's
        // application is built — the analytics/campaign real-broker suites rely on exactly this.
        override fun testResources() = listOf(
            TestResourceEntry(ConsentPostgresRedisTestResource::class.java),
            TestResourceEntry(ConsentWireRedpandaResource::class.java),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // A profile forces this class its own Quarkus boot; pin an ephemeral port so the
            // restart cannot die on QuarkusBindException against the still-bound 8081.
            "quarkus.http.test-port" to "0",
            // Belt and braces on top of the resource's global kill switch: never let Dev Services
            // stand up a second broker for this boot.
            "quarkus.kafka.devservices.enabled" to "false",
            // The dispatcher only runs when the scheduler does; fleet tests disable the scheduler
            // by default (see ConsentExpirationSweepVertxContextIT). Dispatch stays on — main's
            // openbank.outbox.dispatch-enabled=true is inherited — but the tick is shortened so
            // the wire assertion does not wait five seconds per event.
            "quarkus.scheduler.enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
        )
    }

    companion object {
        private const val OPERATOR = "e2e-consent-wire-operator"
        private const val MARKETING_GRANTEE = "party-service:marketing-comms"
        private const val TOPIC = "openbank.consent.events"
        private const val IBAN = "CZ6508000000192000145399"

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic()
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "consent-wire-read-${UUID.randomUUID()}",
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
                name = "consent-wire-tap"
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
 * Isolated Redpanda (Kafka API) for the wire suite: the outgoing `consent-events-out` channel
 * runs the real smallrye-kafka producer against it, and the test reads the topic back with a
 * plain consumer. Returning the bootstrap servers as config properties (not YAML) is the
 * #686-safe idiom the fleet's other broker resources use.
 */
class ConsentWireRedpandaResource : QuarkusTestResourceLifecycleManager {

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
