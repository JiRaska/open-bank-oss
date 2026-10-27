// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedisTestResource
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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-broker wire E2E for the lending outbox relay (`lending-events-out` →
 * `openbank.lending.events`). Until this suite the producer side was proven only up to the
 * `lending_outbox` TABLE (LendingOutboxWriteIT switches the channel to the in-memory connector and
 * asserts the row) or up to the InMemoryConnector sink. Here the real smallrye-kafka producer runs
 * against an isolated Redpanda broker and the topic is read back with a fresh consumer group —
 * the first time the credit evidence envelope (ADR-0214 D1/D2) and the `loan.disbursed` event that
 * audit-service consumes are observed on an actual wire.
 *
 * What is real and what is stood in for, stated out loud: the full origination state machine,
 * the transactional outbox write, the dispatcher tick, Postgres and the smallrye-kafka producer
 * all run for real. The two remote money wires — the GL journal and the borrower credit — are the
 * module's test-scope recording stubs (`TestRecordingLedgerPostingPort` /
 * `TestBorrowerCreditStubs`), @Priority(200) alternatives active in every test boot.
 *
 * The disbursement journey is split into ordered test methods exactly like
 * [LendingOriginationE2E], on purpose: @TestSecurity pins one identity per method, and the
 * segregation-of-duties rule (maker, checker and disburser must be three different principals)
 * is part of what the wire evidence proves — collapsing the actors would weaken the very
 * boundary under test.
 *
 * Transport contract, measured against the shared OutboxKafkaHeaders: every record is keyed by
 * the aggregate id, carries `ce-type` == the outbox event type (`credit.application.transition`,
 * `credit.application.transition.refused`, `loan.disbursed`), the `ce-id` CloudEvents id, and
 * `idempotency-key` == ce-id. The lending outbox is still kernel v1 (PanacheOutboxEntity — no
 * `trace_context` column, unlike document-service's v2), so records carry NO traceparent header
 * today; that is stated as the measured contract, not asserted. The `sourceService` on every
 * payload is "lending" — deliberately NOT "lending-service"; the value predates the audit
 * convention and is preserved self-consistently (#3994/#5256).
 */
@QuarkusTest
@TestProfile(LendingEventsWireE2E.WireProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LendingEventsWireE2E {

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    private val partyId: UUID = UUID.randomUUID()
    private lateinit var refusalApplicationId: String
    private lateinit var applicationId: String
    private lateinit var loanId: String

    // ── scenario A: transitions + refusal, single actor ─────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_CREDIT_RISK"])
    fun `origination transitions and a maker self-approval refusal land on the wire`() {
        refusalApplicationId = applyFor(partyId)
        repeat(4) { advance(refusalApplicationId) }

        // The maker attempts their own four-eyes decision — refused (the journey proof from
        // LendingOriginationE2E) — and the refusal is itself durable credit evidence.
        Given {
            contentType("application/json")
            body("""{"approve":true,"reason":"self-approval attempt"}""")
        } When {
            post("/api/v1/lending/applications/$refusalApplicationId/decision")
        } Then {
            statusCode(409)
        }

        // Wait until the lifecycle is complete on the wire. A send that hits the publish
        // timeout is marked FAILED and re-claimed on a later dispatcher tick, so on a slow
        // (emulated) environment envelopes can land OUT of commit order — a fixed settle
        // sleep cannot know when the retry tick has caught up. The settled condition is
        // defined the way a reader rebuilds the lifecycle (ADR-0214 D1): the occurredAt-
        // latest transition is the four-eyes gate and the refusal has landed. Only the
        // endpoints are asserted, not pairwise chain contiguity.
        val settled = awaitSettled(refusalApplicationId)
        val transitions = settled.transitions
        val refusals = settled.refusals
        assertThat(transitions.size)
            .describedAs("apply + four advances must each co-commit one transition")
            .isGreaterThanOrEqualTo(4)
        assertThat(refusals).hasSize(1)

        // The reader's view: endpoints are picked by occurredAt, never by arrival order —
        // a retried envelope can legally land after later ones (see awaitSettled).
        val first = mapper.readTree(earliestByOccurredAt(transitions).value())
        val last = latestByOccurredAt(transitions)
        assertThat(first["eventType"].asText()).isEqualTo(TRANSITION)
        assertThat(first["aggregateType"].asText()).isEqualTo("LOAN_APPLICATION")
        assertThat(first["aggregateId"].asText()).isEqualTo(refusalApplicationId)
        assertThat(first["loanApplicationId"].asText()).isEqualTo(refusalApplicationId)
        assertThat(first["partyId"].asText()).isEqualTo(partyId.toString())
        assertThat(first["fromState"].asText()).isEqualTo("NONE")
        assertThat(first["toState"].asText()).isEqualTo("SUBMITTED")
        assertThat(first["actorId"].asText()).isEqualTo(PROPOSER)
        assertThat(first["actorKind"].asText()).isEqualTo("HUMAN")
        // correlationId == applicationId is what lets a reader rebuild the whole lifecycle from
        // one key (ADR-0214 D1).
        assertThat(first["correlationId"].asText()).isEqualTo(refusalApplicationId)
        assertThat(first["sourceService"].asText()).isEqualTo("lending")
        // PII-minimised envelope: identifiers and codes, never application data.
        assertThat(first.fieldNames().asSequence().toList())
            .doesNotContain("requestedAmount", "legalName", "customerName")
        // A DateTimeParseException here fails the test — that IS the assertion that occurredAt
        // is ISO-8601 on the wire (#3914).
        Instant.parse(first["occurredAt"].asText())

        // The chain reaches the four-eyes gate on the wire — the refusal below records the state
        // the application is actually in, so a reader rebuilding the lifecycle sees the gate
        // step that happened (note: the state machine has intermediate states not every writer
        // evidences, so pairwise from/to contiguity is NOT asserted — only the endpoints).
        assertThat(last["toState"].asText()).isEqualTo("FOUR_EYES")
        val refusal = mapper.readTree(refusals.single().value())
        assertThat(refusal["outcome"].asText()).isEqualTo("REFUSED")
        assertThat(refusal["attemptedAction"].asText()).isNotBlank()
        assertThat(refusal["code"].asText()).isNotBlank()
        assertThat(refusal["state"].asText()).isEqualTo(last["toState"].asText())
        assertThat(refusal["correlationId"].asText()).isEqualTo(refusalApplicationId)
        assertThat(refusal["sourceService"].asText()).isEqualTo("lending")
        Instant.parse(refusal["occurredAt"].asText())

        for (record in transitions + refusals) {
            assertTransport(record, refusalApplicationId)
        }
    }

    // ── scenario B: full spine to disbursement, three principals across methods ──

    @Test
    @Order(2)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `2 - desk apply as the maker`() {
        applicationId = applyFor(partyId)
        assertThat(applicationId).isNotBlank()
    }

    @Test
    @Order(3)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `3 - four advances reach the four-eyes gate`() {
        repeat(4) { advance(applicationId) }
    }

    @Test
    @Order(4)
    @TestSecurity(user = CHECKER, roles = ["ROLE_CREDIT_RISK"])
    fun `4 - a different checker approves`() {
        Given {
            contentType("application/json")
            body("""{"approve":true,"reason":"wire e2e decision"}""")
        } When {
            post("/api/v1/lending/applications/$applicationId/decision")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `5 - sign and reach ready to disburse`() {
        advance(applicationId)
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body("""{"artefactHash":"${"cd".repeat(32)}"}""")
        } When {
            post("/api/v1/lending/applications/$applicationId/sign")
        } Then {
            statusCode(200)
        }
        advance(applicationId)
    }

    @Test
    @Order(6)
    @TestSecurity(user = DISBURSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `6 - a third principal disburses and the wire carries loan disbursed exactly once`() {
        val booked = Given {
            contentType("application/json")
        } When {
            post("/api/v1/lending/applications/$applicationId/disburse")
        } Extract {
            this
        }
        assertThat(booked.statusCode()).isEqualTo(201)
        loanId = booked.jsonPath().getString("id")
        assertThat(loanId).isNotBlank()
        val contractNumber = booked.jsonPath().getString("contractNumber")
        assertThat(contractNumber).matches("UV-\\d{4}-\\d{6,}")

        val record = awaitWireRecord("loan.disbursed", loanId)
        assertTransport(record, loanId)
        val body = mapper.readTree(record.value())
        assertThat(body["aggregateType"].asText()).isEqualTo("LOAN")
        assertThat(body["loanId"].asText()).isEqualTo(loanId)
        assertThat(body["partyId"].asText()).isEqualTo(partyId.toString())
        assertThat(body["contractNumber"].asText()).isEqualTo(contractNumber)
        assertThat(body["principal"].asText()).isEqualTo("10000.00 EUR")
        // occurredAt is the loan's own disbursedAt — the recognition instant, not serialisation.
        Instant.parse(body["occurredAt"].asText())
        assertThat(body["sourceService"].asText()).isEqualTo("lending")

        // The relay marked the row SENT — the durable hand-off boundary the dispatcher owns.
        assertOutboxSent(loanId, "loan.disbursed")

        // Terminal sentinel: a second disbursement refuses, and after the dispatcher settles the
        // wire still carries exactly one loan.disbursed for this loan.
        val second = Given {
            contentType("application/json")
        } When {
            post("/api/v1/lending/applications/$applicationId/disburse")
        } Extract {
            this
        }
        assertThat(second.statusCode()).isEqualTo(409)
        Thread.sleep(2_000)
        assertThat(observed().filter { it.key() == loanId && ceType(it) == "loan.disbursed" }).hasSize(1)
    }

    // ── REST flows ───────────────────────────────────────────────────────────────

    private fun applyFor(party: UUID): String = Given {
        contentType("application/json")
        header("Idempotency-Key", UUID.randomUUID().toString())
        body(
            """
            {"partyId":"$party","requestedAmount":{"amount":"10000.00","currency":{"code":"EUR"}},
            "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}
            """.trimIndent(),
        )
    } When {
        post("/api/v1/lending/applications")
    } Then {
        statusCode(201)
    } Extract {
        jsonPath().getString("id")
    }

    private fun advance(id: String) {
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/lending/applications/$id/advance")
        } Then {
            statusCode(200)
        }
    }

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun awaitWireRecord(type: String, key: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { it.key() == key && ceType(it) == type }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.key()}/${ceType(it)}" }
        throw AssertionError("no $type on the wire for key $key within 45s; observed: [$seen]")
    }

    private fun assertTransport(record: ConsumerRecord<String, String>, key: String) {
        assertThat(record.topic()).isEqualTo(TOPIC)
        assertThat(record.key())
            .describedAs("the outbox partition key IS the aggregate id — consumers key idempotently by it")
            .isEqualTo(key)
        val eventId = header(record, "ce-id")
        assertThat(eventId)
            .describedAs("the CloudEvents id header must be present on every outbox-relayed record")
            .isNotBlank()
        assertThat(header(record, "idempotency-key"))
            .describedAs("the idempotency key is the event id, so at-least-once consumers can dedupe")
            .isEqualTo(eventId)
        // ce-type presence is implied: every caller found this record by matching it.
    }

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    private fun occurredAt(record: ConsumerRecord<String, String>): Instant =
        Instant.parse(mapper.readTree(record.value())["occurredAt"].asText())

    private fun earliestByOccurredAt(records: List<ConsumerRecord<String, String>>): ConsumerRecord<String, String> =
        records.minBy { occurredAt(it) }

    private fun latestByOccurredAt(records: List<ConsumerRecord<String, String>>): JsonNode =
        mapper.readTree(records.maxBy { occurredAt(it) }.value())

    private class SettledWindow(
        val transitions: List<ConsumerRecord<String, String>>,
        val refusals: List<ConsumerRecord<String, String>>,
    )

    /**
     * Poll the wire tap until the refusal window is complete — occurredAt-latest transition is
     * the four-eyes gate and exactly one refusal has landed — or return whatever accumulated
     * after [SETTLE_TIMEOUT] so the assertions render the real mismatch. A publish that hits
     * the 3 s outbox timeout is marked FAILED and re-claimed on a later tick, so envelopes can
     * legally land out of commit order on a slow environment; a fixed sleep cannot know when
     * the retry tick has caught up.
     */
    private fun awaitSettled(applicationId: String): SettledWindow {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            val window = snapshot(applicationId)
            if (isSettled(window)) return window
            Thread.sleep(250)
        }
        return snapshot(applicationId)
    }

    private fun isSettled(window: SettledWindow): Boolean {
        if (window.transitions.size < 4) return false
        if (window.refusals.size != 1) return false
        val latest = window.transitions.maxByOrNull { occurredAt(it) } ?: return false
        return mapper.readTree(latest.value())["toState"].asText() == "FOUR_EYES"
    }

    private fun snapshot(applicationId: String): SettledWindow = SettledWindow(
        observed().filter { it.key() == applicationId && ceType(it) == TRANSITION }.toList(),
        observed().filter { it.key() == applicationId && ceType(it) == REFUSED }.toList(),
    )

    private fun ceType(record: ConsumerRecord<String, String>): String? = header(record, "ce-type")

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
        throw AssertionError("lending_outbox row for $aggregateId/$eventType never reached SENT")
    }

    private fun outboxStatus(aggregateId: String, eventType: String): String? =
        dataSource.connection.use { queryStatus(it, aggregateId, eventType) }

    private fun queryStatus(c: java.sql.Connection, aggregateId: String, eventType: String): String? {
        val ps = c.prepareStatement("select status from lending_outbox where aggregate_id = ? and event_type = ?")
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
        // Kafka Dev Services launched a second broker and the wire stayed empty (measured in
        // consent-service; this module's LendingOriginationE2E does not read the topic back at
        // all). Profile test resources are visible when the profile's application is built.
        override fun testResources() = listOf(
            TestResourceEntry(PostgresRedisTestResource::class.java, mapOf("db" to "openbank_lending_it")),
            TestResourceEntry(LendingWireRedpandaResource::class.java),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // A profile forces this class its own Quarkus boot; pin an ephemeral port so the
            // restart cannot die on QuarkusBindException against the still-bound 8081.
            "quarkus.http.test-port" to "0",
            // Belt and braces on top of the resource's global kill switch: never let Dev Services
            // stand up a second broker for this boot.
            "quarkus.kafka.devservices.enabled" to "false",
            // The dispatcher only runs when the scheduler does; fleet tests disable the scheduler
            // by default. Dispatch stays on (main's openbank.outbox.dispatch-enabled=true is
            // inherited) but the tick is shortened so the wire assertion does not wait five
            // seconds per event.
            "quarkus.scheduler.enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
        )
    }

    companion object {
        private const val PROPOSER = "e2e-wire-lending-proposer"
        private const val CHECKER = "e2e-wire-lending-checker"
        private const val DISBURSER = "e2e-wire-lending-disburser"
        private const val TOPIC = "openbank.lending.events"
        private const val TRANSITION = "credit.application.transition"
        private const val REFUSED = "credit.application.transition.refused"
        private val SETTLE_TIMEOUT: Duration = Duration.ofSeconds(30)

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic()
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "lending-wire-read-${UUID.randomUUID()}",
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
                name = "lending-wire-tap"
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
 * Isolated Redpanda (Kafka API) for the wire suite: the outgoing `lending-events-out` channel
 * runs the real smallrye-kafka producer against it, and the test reads the topic back with a
 * plain consumer. Returning the bootstrap servers as config properties (not YAML) is the
 * #686-safe idiom the fleet's other broker resources use.
 */
class LendingWireRedpandaResource : QuarkusTestResourceLifecycleManager {

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
