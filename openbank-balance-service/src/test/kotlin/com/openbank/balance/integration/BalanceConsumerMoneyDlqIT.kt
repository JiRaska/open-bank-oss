// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.it.PostgresRedpandaTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * #11604: both Kafka consumers build kernel Money / CurrencyCode before anything is persisted, and
 * an event Money cannot hold is PARKED on the channel's configured dead-letter topic — the channel
 * does not stop, nothing about the record is written, and the next valid record on the same
 * partition is consumed normally.
 *
 * On main before this change: a `-40.005 CZK` delta was booked as a sub-haléř figure, a `XYZ` delta
 * created a phantom `XYZ` pocket, and an `AccountCreated` in `XYZ` initialised a `XYZ` balance.
 * Real Kafka -> production consumers -> PostgreSQL; HTTP identity is a test fixture.
 */
@QuarkusTest
@TestProfile(BalanceConsumerMoneyDlqIT.Profile::class)
class BalanceConsumerMoneyDlqIT {
    class Profile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresRedpandaTestResource::class.java))
        override fun getConfigOverrides() = mapOf(
            "openbank.balance.projection.enabled" to "true",
            "mp.messaging.incoming.ledger-events-in.group.id" to LEDGER_GROUP,
            "mp.messaging.incoming.ledger-events-in.auto.offset.reset" to "earliest",
            "mp.messaging.incoming.balance-init-in.group.id" to INIT_GROUP,
            "mp.messaging.incoming.balance-init-in.auto.offset.reset" to "earliest",
        )
    }

    @Inject lateinit var mapper: ObjectMapper

    @Inject lateinit var dataSource: javax.sql.DataSource

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `a ledger delta Money cannot hold is dead-lettered and moves nothing`() {
        val account = UUID.randomUUID()
        given().contentType("application/json").body(mapOf("currency" to "CZK", "initialAmount" to "100.00"))
            .post("/api/v1/balances/$account/initialize").then().statusCode(201)
        val poison = listOf(
            booked(account, "CZK", "-40.005"),
            booked(account, "XYZ", "-40.00"),
            booked(account, "CZK", "1E+19"),
        )
        val valid = booked(account, "CZK", "-40.00")
        withKafka(DLQ_LEDGER) { producer, admin, deadLetters ->
            send(producer, admin, LEDGER_TOPIC, LEDGER_GROUP, "$account", poison + valid)
            assertDeadLetters(deadLetters, "$account", poison)
        }
        val pocket = given().get("/api/v1/balances/$account/CZK").then().statusCode(200).extract().jsonPath()
        assertThat(BigDecimal(pocket.getString("bookedAmount"))).isEqualByComparingTo("60.00")
        assertThat(BigDecimal(pocket.getString("availableAmount"))).isEqualByComparingTo("60.00")
        given().get("/api/v1/balances/$account/XYZ").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `an AccountCreated in an unsupported currency is dead-lettered and creates no balance`() {
        val poisoned = UUID.randomUUID()
        val valid = UUID.randomUUID()
        val poison = listOf(created(poisoned, "XYZ"), created(poisoned, "XAU"))
        withKafka(DLQ_INIT) { producer, admin, deadLetters ->
            send(producer, admin, INIT_TOPIC, INIT_GROUP, "k-$poisoned", poison + created(valid, " eur"))
            assertDeadLetters(deadLetters, "k-$poisoned", poison)
        }
        given().get("/api/v1/balances/$poisoned").then().statusCode(200)
            .extract().jsonPath().getList<Any>("balances").let { assertThat(it).isEmpty() }
        given().get("/api/v1/balances/$valid/EUR").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `a wrong branch is quarantined before money moves including a changed duplicate`() {
        val account = UUID.randomUUID()
        val book = UUID.randomUUID()
        val other = UUID.randomUUID()
        withKafka(DLQ_INIT) { producer, admin, deadLetters ->
            send(producer, admin, INIT_TOPIC, INIT_GROUP, "$account", listOf(createdInBook(account, book)))
            val changed = createdInBook(account, other)
            send(producer, admin, INIT_TOPIC, INIT_GROUP, "$account", listOf(changed))
            assertDeadLetters(deadLetters, "$account", listOf(changed))
        }
        val correct = bookedInBook(account, book)
        val wrong = mapper.readTree(correct).also {
            (it as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("branchBookId", other.toString())
        }.toString()
        withKafka(DLQ_LEDGER) { producer, admin, deadLetters ->
            send(producer, admin, LEDGER_TOPIC, LEDGER_GROUP, "$account", listOf(wrong, correct, correct, wrong))
            assertDeadLetters(deadLetters, "$account", listOf(wrong, wrong))
        }
        assertBranchPosting(account, book)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `a branch projection retries a database outage automatically and commits its event exactly once`() {
        val account = UUID.randomUUID()
        val book = UUID.randomUUID()
        val suffix = account.toString().replace("-", "")
        val sequence = "attempt_$suffix"
        val trigger = "outage_$suffix"
        withKafka(DLQ_LEDGER) { producer, admin, _ ->
            send(producer, admin, INIT_TOPIC, INIT_GROUP, "$account", listOf(createdInBook(account, book)))
            dataSource.connection.use { observer ->
                installOutageHook(observer, account, sequence, trigger)
                val sent = producer.send(ProducerRecord(LEDGER_TOPIC, "$account", bookedInBook(account, book)))
                    .get(10, TimeUnit.SECONDS)
                val role = observer.metaData.userName
                require(role.matches(Regex("^[a-z_]+$")))
                try {
                    awaitCondition {
                        observer.createStatement().use { q ->
                            q.executeQuery("SELECT is_called FROM $sequence").use { rows ->
                                rows.next()
                                rows.getBoolean(1)
                            }
                        }
                    }
                    observer.createStatement().use { sql ->
                        sql.executeQuery(
                            "SELECT count(*) FROM ledger_projection_event WHERE account_id='$account'",
                        ).use { rows ->
                            rows.next()
                            assertThat(rows.getInt(1)).isZero()
                        }
                        sql.execute("ALTER ROLE $role NOLOGIN")
                        sql.execute(
                            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                                "WHERE usename=current_user AND pid<>pg_backend_pid()",
                        )
                        sql.execute("DROP TRIGGER $trigger ON ledger_projection_event")
                    }
                    Thread.sleep(1500)
                } finally {
                    observer.createStatement().use { sql ->
                        sql.execute("ALTER ROLE $role LOGIN")
                        sql.execute("DROP TRIGGER IF EXISTS $trigger ON ledger_projection_event")
                        sql.execute("DROP FUNCTION $trigger()")
                        sql.execute("DROP SEQUENCE $sequence")
                    }
                }
                // Only the original Kafka send exists: restoring PostgreSQL must resume it.
                awaitCommitted(admin, LEDGER_GROUP, TopicPartition(sent.topic(), sent.partition()), sent.offset() + 1)
            }
        }
        assertBranchPosting(account, book)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `a branch movement waits for account context from another topic without a client retry`() {
        val account = UUID.randomUUID()
        val book = UUID.randomUUID()
        withKafka(DLQ_LEDGER) { producer, admin, _ ->
            val sent = producer.send(ProducerRecord(LEDGER_TOPIC, "$account", bookedInBook(account, book)))
                .get(10, TimeUnit.SECONDS)
            send(producer, admin, INIT_TOPIC, INIT_GROUP, "$account", listOf(createdInBook(account, book)))
            awaitCommitted(admin, LEDGER_GROUP, TopicPartition(sent.topic(), sent.partition()), sent.offset() + 1)
        }
        assertBranchPosting(account, book)
    }

    private fun installOutageHook(observer: java.sql.Connection, account: UUID, sequence: String, trigger: String) {
        observer.createStatement().use { sql ->
            sql.execute("CREATE SEQUENCE $sequence")
            sql.execute(
                "CREATE FUNCTION $trigger() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN " +
                    "IF NEW.account_id='$account'::uuid THEN PERFORM nextval('$sequence'); " +
                    "RAISE EXCEPTION 'synthetic interrupted projection'; END IF; RETURN NEW; END; \$\$",
            )
            sql.execute(
                "CREATE TRIGGER $trigger BEFORE INSERT ON ledger_projection_event " +
                    "FOR EACH ROW EXECUTE FUNCTION $trigger()",
            )
        }
    }

    private fun createdInBook(account: UUID, book: UUID) = mapper.writeValueAsString(
        mapOf(
            "eventType" to "AccountCreated",
            "aggregateId" to account,
            "currency" to "EUR",
            "bookingContext" to
                mapOf("bankId" to BANK, "establishmentId" to book, "hostCountry" to "AT", "mode" to "BRANCH"),
        ),
    )

    private fun bookedInBook(account: UUID, book: UUID) = mapper.readTree(booked(account, "EUR", "10.00")).also {
        (it as com.fasterxml.jackson.databind.node.ObjectNode).put("branchBookId", book.toString())
    }.toString()

    private fun assertBranchPosting(account: UUID, book: UUID) {
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT booked_amount FROM balances WHERE account_id=? AND currency='EUR'").use {
                it.setObject(1, account)
                it.executeQuery().use { rows ->
                    rows.next()
                    assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("10")
                }
            }
            c.prepareStatement("SELECT branch_book_id FROM ledger_projection_event WHERE account_id=?").use {
                it.setObject(1, account)
                it.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1)).isEqualTo(book)
                    assertThat(rows.next()).isFalse()
                }
            }
            c.prepareStatement(
                "SELECT payload::jsonb->>'branchBookId' FROM balance_outbox WHERE aggregate_id=? " +
                    "AND event_type='BALANCE_UPDATED'",
            ).use {
                it.setObject(1, account)
                it.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo(book.toString())
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < until) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Expected database condition did not converge")
    }

    private fun booked(account: UUID, currency: String, delta: String) = mapper.writeValueAsString(
        mapOf(
            "eventType" to "AccountBookedChanged",
            "aggregateId" to "$account",
            "currency" to currency,
            "delta" to delta,
            "journalEntryId" to "${UUID.randomUUID()}",
            "transactionId" to "${UUID.randomUUID()}",
            "entryDate" to LocalDate.now().toString(),
            "version" to 1,
        ),
    )

    private fun created(account: UUID, currency: String) = mapper.writeValueAsString(
        mapOf("eventType" to "AccountCreated", "aggregateId" to "$account", "currency" to currency),
    )

    private fun withKafka(
        dlq: String,
        block: (KafkaProducer<String, String>, Admin, KafkaConsumer<String, String>) -> Unit,
    ) {
        val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
        val connection = mapOf("bootstrap.servers" to bootstrap)
        KafkaProducer<String, String>(
            connection + mapOf("key.serializer" to SER, "value.serializer" to SER, "acks" to "all"),
        ).use { producer ->
            Admin.create(connection).use { admin ->
                KafkaConsumer<String, String>(
                    connection + mapOf(
                        "group.id" to "money-dlq-proof-${UUID.randomUUID()}",
                        "auto.offset.reset" to "earliest",
                        "key.deserializer" to DESER,
                        "value.deserializer" to DESER,
                    ),
                ).use { deadLetters ->
                    deadLetters.subscribe(listOf(dlq))
                    block(producer, admin, deadLetters)
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun send(
        producer: KafkaProducer<String, String>,
        admin: Admin,
        topic: String,
        group: String,
        key: String,
        payloads: List<String>,
    ) {
        for (payload in payloads) {
            // One key: poison and valid records share a partition, so a wedged channel would show.
            val sent = producer.send(ProducerRecord(topic, key, payload)).get(10, TimeUnit.SECONDS)
            awaitCommitted(admin, group, TopicPartition(sent.topic(), sent.partition()), sent.offset() + 1)
        }
    }

    private fun awaitCommitted(admin: Admin, group: String, partition: TopicPartition, offset: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS)[partition]
            if (committed != null && committed.offset() >= offset) return
            Thread.sleep(100)
        }
        error("Consumer group $group did not acknowledge the record")
    }

    private fun assertDeadLetters(consumer: KafkaConsumer<String, String>, key: String, expected: List<String>) {
        val received = mutableListOf<String>()
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (received.size < expected.size && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).filter { it.key() == key }.forEach { record ->
                assertThat(record.headers().lastHeader("dead-letter-reason")).isNotNull()
                received += record.value()
            }
        }
        assertThat(received).containsExactlyElementsOf(expected)
    }

    companion object {
        private val BANK = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private const val LEDGER_GROUP = "balance-money-ledger-dlq-it"
        private const val INIT_GROUP = "balance-money-init-dlq-it"
        private const val LEDGER_TOPIC = "openbank.ledger.journal.posted"
        private const val INIT_TOPIC = "openbank.accounts.account.created"
        private const val DLQ_LEDGER = "openbank.dlq.balance.ledger-events-in"
        private const val DLQ_INIT = "openbank.dlq.balance.balance-init-in"
        private const val SER = "org.apache.kafka.common.serialization.StringSerializer"
        private const val DESER = "org.apache.kafka.common.serialization.StringDeserializer"
    }
}
