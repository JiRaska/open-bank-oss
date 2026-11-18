// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of the account-registry projection write.
 *
 * statement-service is a pure projection over the account lifecycle: its HTTP surface is read-only
 * and every row of `account_registry` — the enumeration source the monthly close walks — arrives
 * as an `AccountCreated` record on the `account-events-in` stream. The spans asserted here are
 * the service's OWN — the Kafka connector's OpenTelemetry instrumentation produces the consume
 * span for the real broker record, and Quarkus' JDBC instrumentation produces the span for the
 * real Postgres INSERT; `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a
 * contract can read what an OTLP collector would receive. Nothing asserted here is created by
 * the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT into `account_registry`
 * happened *inside* the trace of the Kafka record that carried the event. A projection write
 * that escaped the consume context still updates the registry and still acks the record, is
 * invisible to every assertion this module already had, and lands in a trace of its own — and a
 * monthly close that walks a registry no telemetry can explain is exactly the kind of gap a
 * statement-period reconstruction cannot afford.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the `AccountCreated` projection is the service's only write path, so it is the right
 * first contract here.
 */
@QuarkusTest
@TestProfile(StatementTraceContractIT.StatementTraceProfile::class)
class StatementTraceContractIT {

    class StatementTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(StatementTraceKafkaResource::class.java),
        )

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // The deployed channel subscribes via the singular `topic` key, so the IT overrides
            // `topic` the same way (unlike audit-service's `topics` list — SRMSG18022 fires when
            // both are set).
            "mp.messaging.incoming.account-events-in.enabled" to "true",
            "mp.messaging.incoming.account-events-in.topic" to TRACE_TOPIC,
            "mp.messaging.incoming.account-events-in.group.id" to "statement-trace-contract-it",
            "mp.messaging.incoming.account-events-in.auto.offset.reset" to "earliest",
            // Pin the sampler: with a parent-based sampler the contract is a function of the
            // incoming record headers' sampled flag (the test producer sets no traceparent), and
            // an unsampled parent legitimately yields no spans. The contract asserts span
            // presence and context flow, not sampling policy.
            "quarkus.otel.traces.sampler" to "always_on",
        )
    }

    /** Real-broker resource for this contract: Postgres for the projection, Redpanda for the event. */
    class StatementTraceKafkaResource : QuarkusTestResourceLifecycleManager {
        private val postgres = PostgresTestResource()
        private var broker: RedpandaContainer? = null

        override fun start(): Map<String, String> {
            val settings = postgres.start()
            try {
                val kafka = RedpandaContainer(
                    DockerImageName.parse(REDPANDA_IMAGE)
                        .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
                )
                broker = kafka
                kafka.start()
                TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started")
                Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                    admin.createTopics(listOf(NewTopic(TRACE_TOPIC, 1, 1)))
                        .all().get(20, TimeUnit.SECONDS)
                }
                return settings + mapOf(
                    "kafka.bootstrap.servers" to kafka.bootstrapServers,
                    "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to kafka.bootstrapServers,
                    "quarkus.kafka.devservices.enabled" to "false",
                )
            } catch (failure: Exception) {
                stop()
                throw failure
            }
        }

        override fun stop() {
            broker?.let {
                it.stop()
                TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped")
            }
            broker = null
            postgres.stop()
        }

        companion object {
            const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
            const val TRACE_TOPIC = "openbank.statement.trace.contract.it"
        }
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `projecting an account keeps the consumed record and its registry INSERT in one trace`() {
        val accountId = UUID.randomUUID()

        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to config("kafka.bootstrap.servers"),
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            producer.send(
                ProducerRecord(
                    StatementTraceKafkaResource.TRACE_TOPIC,
                    "trace-$accountId",
                    """
                    {"eventType":"AccountCreated","aggregateId":"$accountId",
                     "partyId":"${UUID.randomUUID()}","currency":"CZK"}
                    """.trimIndent(),
                ),
            ).get(15, TimeUnit.SECONDS)
        }

        awaitRow(accountId)

        exporter.contract()
            .requiresSpan("$TRACE_TOPIC receive")
            .requiresAttribute("$TRACE_TOPIC receive", "messaging.operation")
            .requiresSpan("INSERT account_registry")
            .requiresAttribute("INSERT account_registry", "db.sql.table")
            .requiresSameTrace("$TRACE_TOPIC receive", "INSERT account_registry")
            .hasNoErrorSpan()
            .verifiedAs("statement-account-registry-project")
    }

    private fun awaitRow(accountId: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (count(accountId) == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(count(accountId)).describedAs("account_registry row for $accountId").isEqualTo(1)
    }

    private fun count(accountId: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM account_registry WHERE account_id = ?").use { query ->
            query.setObject(1, accountId)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun config(name: String): String = ConfigProvider.getConfig().getValue(name, String::class.java)

    private fun jdbc() = DriverManager.getConnection(
        config("quarkus.datasource.jdbc.url"),
        config("quarkus.datasource.username"),
        config("quarkus.datasource.password"),
    )

    companion object {
        private const val TRACE_TOPIC = StatementTraceKafkaResource.TRACE_TOPIC
    }
}
