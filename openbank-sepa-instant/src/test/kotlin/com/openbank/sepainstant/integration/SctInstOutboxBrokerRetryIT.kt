// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.integration

import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import com.openbank.sepainstant.infrastructure.outbox.SctInstOutboxDispatcher
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@QuarkusTest
@TestProfile(SctInstOutboxBrokerRetryIT.MissingTopicProfile::class)
class SctInstOutboxBrokerRetryIT {

    class MissingTopicProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources(): Boolean = true

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> = listOf(
            QuarkusTestProfile.TestResourceEntry(
                PostgresRedisTestResource::class.java,
                mapOf("db" to "openbank_sepa_instant_broker_retry_it"),
            ),
            QuarkusTestProfile.TestResourceEntry(BrokerWithoutAutoCreate::class.java),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.sct-inst.scheme-submission.enabled" to "false",
            "openbank.synthetic.trusted-principals" to SYNTHETIC_ACTOR,
            "openbank.outbox.dispatch-enabled" to "true",
            "openbank.outbox.initial-delay" to "1h",
            "mp.messaging.outgoing.sct-inst-events-out.topic" to TOPIC,
            // Let the real Kafka nack finish before the dispatcher’s outer timeout.
            "mp.messaging.outgoing.sct-inst-events-out.max.block.ms" to "500",
            "mp.messaging.outgoing.sct-inst-events-out.retries" to "0",
        )
    }

    class BrokerWithoutAutoCreate : QuarkusTestResourceLifecycleManager {
        private val broker = RedpandaContainer(
            DockerImageName.parse(REDPANDA_IMAGE)
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        private val resourceScope = UUID.randomUUID().toString()

        override fun start(): Map<String, String> {
            broker.start()
            val configured = broker.execInContainer("rpk", "cluster", "config", "set", AUTO_CREATE_TOPICS, "false")
            check(configured.exitCode == 0) { configured.stderr }
            val actual = broker.execInContainer("rpk", "cluster", "config", "get", AUTO_CREATE_TOPICS)
            check(actual.exitCode == 0 && actual.stdout.trim() == "false") {
                "Broker still allows automatic topic creation"
            }
            TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started", resourceScope)
            return mapOf(
                "kafka.bootstrap.servers" to broker.bootstrapServers,
                "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to broker.bootstrapServers,
            )
        }

        override fun stop() {
            broker.stop()
            TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped", resourceScope)
        }
    }

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var dispatcher: SctInstOutboxDispatcher

    @Inject
    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrapServers: String

    @Test
    @TestSecurity(user = SYNTHETIC_ACTOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-sepa-instant-broker-retry-fixture"),
            Claim(key = "preferred_username", value = SYNTHETIC_ACTOR),
            Claim(key = "sub", value = "isolated-sepa-instant-broker-retry-subject"),
        ],
    )
    fun `a missing broker topic leaves the committed synthetic event retryable until the same id is sent`() {
        Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers)).use { admin ->
            assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).doesNotContain(TOPIC)
            val paymentId = submitClaimingSyntheticSctInstPayment()
            val pending = row(paymentId)
            assertThat(pending.status).isEqualTo("PENDING")
            assertThat(pending.attempts).isZero()
            assertThat(pending.synthetic).isTrue()

            tick()
            val failed = row(paymentId)
            assertThat(failed.eventId).isEqualTo(pending.eventId)
            assertThat(failed.status).isEqualTo("FAILED")
            assertThat(failed.attempts).isOne()
            assertThat(failed.sentAt).isNull()
            assertThat(failed.nextAttemptAt).isNotNull()
            assertThat(failed.lastError).contains(TOPIC)
            assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).doesNotContain(TOPIC)

            admin.createTopics(listOf(NewTopic(TOPIC, 1, 1.toShort()))).all().get(10, TimeUnit.SECONDS)
            val sent = awaitRetry(paymentId)
            assertThat(sent.eventId).isEqualTo(pending.eventId)
            assertThat(sent.attempts).isEqualTo(2)
            assertThat(sent.sentAt).isNotNull()
            assertThat(sent.lastError).isNull()
            val record = receive()
            assertThat(record.key()).isEqualTo(paymentId.toString())
            assertThat(record.header(OutboxKafkaHeaders.HEADER_EVENT_ID)).isEqualTo(pending.eventId.toString())
            assertThat(record.header(OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY)).isEqualTo(pending.eventId.toString())
            assertThat(record.header(OutboxKafkaHeaders.HEADER_SYNTHETIC)).isEqualTo(SyntheticTaint.TRUE_VALUE)
            assertThat(record.offset()).isZero()
            val partition = TopicPartition(TOPIC, record.partition())
            val latest = admin.listOffsets(mapOf(partition to OffsetSpec.latest()))
                .partitionResult(partition).get(10, TimeUnit.SECONDS)
            assertThat(latest.offset()).isEqualTo(1L)
        }
    }

    private fun awaitRetry(paymentId: UUID): Delivery {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            tick()
            val delivery = row(paymentId)
            if (delivery.status == "SENT") return delivery
            Thread.sleep(50)
        }
        error("Retry did not acknowledge the committed outbox event")
    }

    private fun receive(): ConsumerRecord<String, String> = KafkaConsumer(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "sct-inst-broker-retry-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        StringDeserializer(),
    ).use { consumer ->
        consumer.subscribe(listOf(TOPIC))
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline) {
            val records = consumer.poll(Duration.ofMillis(250))
            if (!records.isEmpty) return@use records.single()
        }
        error("Broker did not return the retried event")
    }

    private fun ConsumerRecord<String, String>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.decodeToString()

    private data class Delivery(
        val eventId: UUID,
        val status: String,
        val attempts: Int,
        val synthetic: Boolean,
        val sentAt: OffsetDateTime?,
        val nextAttemptAt: OffsetDateTime?,
        val lastError: String?,
    )

    private fun row(paymentId: UUID): Delivery = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT event_id, status, attempt_count, synthetic, sent_at, next_attempt_at, last_error " +
                "FROM sct_inst_outbox WHERE aggregate_id = ?",
        ).use { statement ->
            statement.setObject(1, paymentId)
            statement.executeQuery().use { result ->
                check(result.next())
                val row = Delivery(
                    eventId = result.getObject(1, UUID::class.java),
                    status = result.getString(2),
                    attempts = result.getInt(3),
                    synthetic = result.getBoolean(4),
                    sentAt = result.getObject(5, OffsetDateTime::class.java),
                    nextAttemptAt = result.getObject(6, OffsetDateTime::class.java),
                    lastError = result.getString(7),
                )
                check(!result.next())
                row
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun tick() = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { dispatcher.dispatch() }
    }

    private companion object {
        const val SYNTHETIC_ACTOR = "service-account-openbank-sepa-instant-broker-retry-fixture"
        const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
        const val AUTO_CREATE_TOPICS = "auto_create_topics_enabled"
        const val TOPIC = "sct-inst-broker-retry-fixture"
    }
}
