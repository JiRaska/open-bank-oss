// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Real-broker wire E2E for the account producer side. The Redpanda broker already runs for every
 * account-service test via [PostgresRedpandaRedisTestResource] — but until this suite nobody read
 * the producer topics back: `AccountLifecycleE2E` states the boundary explicitly ("the
 * account-opened event's DISPATCH to a broker is out of scope — no consumer runs in this
 * process"). Meanwhile `openbank.accounts.account.created` is consumed by balance-service
 * (BalanceInitConsumer — ADR-0267 balance init), document-service (AccountCreatedConsumer),
 * statement-service and campaign catalogs, and `openbank.notification.requests` is drained by
 * notification-service (ADR-0070). Here both topics are read back with a fresh consumer group.
 *
 * What the wire contract actually is, measured: these are DIRECT emitter publishes
 * ([com.openbank.account.infrastructure.messaging.KafkaAccountEventPublisher],
 * [com.openbank.account.infrastructure.messaging.KafkaNotificationRequestPublisher]), not the
 * outbox relay — so the record carries the aggregate id as its key and the serialised event as
 * its value, but NO CloudEvents headers (`ce-type`/`ce-id`/`idempotency-key`). Consumers
 * discriminate by the payload's `eventType` field, which AccountCreatedEvent's KDoc flags as a
 * load-bearing discriminator read verbatim by three downstream services. The payload assertions
 * below pin exactly that contract. One measured surprise worth recording: the publisher's
 * `topic` parameter is DECORATIVE — KafkaAccountEventPublisher sends through the single
 * `account-events-out` emitter, so close's "status-changed" event lands on
 * `openbank.accounts.account.created` too, and consumers separate the two only by `eventType`.
 *
 * Scenarios:
 *  - Open puts `AccountCreated` on `openbank.accounts.account.created` keyed by the account id
 *    AND the `ACCOUNT_OPENED` notification request on `openbank.notification.requests` keyed by
 *    the party id; replaying the same Idempotency-Key returns the same account and emits nothing
 *    further (exactly one created record on the wire).
 *  - Close puts `AccountClosed` on the wire with the reason, and the `ACCOUNT_CLOSED`
 *    notification carrying exactly {accountNumber} — the close reason is NOT in the notification
 *    variables (measured; only ACCOUNT_FROZEN carries a reason), and notification-service renders
 *    `${vars.v("x")}`, so the variables map is asserted exactly.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_it")],
)
class AccountEventsWireE2E {

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `opening an account puts the created event and the opened notification on the wire`() {
        val partyId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID().toString()

        val opened = openAccount(partyId, idempotencyKey)
        assertThat(opened.statusCode)
            .describedAs("POST /api/v1/accounts: %s", opened.body.asString())
            .isEqualTo(201)
        val accountId = opened.jsonPath().getString("id")
        val accountNumber = opened.jsonPath().getString("accountNumber")

        // The event balance-service's BalanceInitConsumer builds the zero balance from (ADR-0267),
        // read back off the real broker.
        val created = awaitWireRecord(ACCOUNT_CREATED_TOPIC, accountId)
        assertThat(created.key())
            .describedAs("the record key IS the account id — consumers key idempotently by it")
            .isEqualTo(accountId)
        val createdBody = mapper.readTree(created.value())
        assertThat(createdBody["eventType"].asText())
            .describedAs("the load-bearing discriminator three downstream services read verbatim")
            .isEqualTo("AccountCreated")
        assertThat(createdBody["aggregateType"].asText()).isEqualTo("Account")
        assertThat(createdBody["aggregateId"].asText()).isEqualTo(accountId)
        assertThat(createdBody["version"].asLong()).isEqualTo(0)
        assertThat(createdBody["accountNumber"].asText()).isEqualTo(accountNumber)
        assertThat(createdBody["accountType"].asText()).isEqualTo("CURRENT")
        assertThat(createdBody["partyId"].asText()).isEqualTo(partyId.toString())
        assertThat(createdBody["productId"].asText()).isEqualTo(PRODUCT_ID)
        assertThat(createdBody["currency"].asText()).isEqualTo("CZK")
        assertThat(createdBody["sourceService"].asText()).isEqualTo("account-service")
        // A DateTimeParseException here fails the test — that IS the assertion that occurredAt
        // is ISO-8601 on the wire (audit-service and analytics-sink read business time from it).
        Instant.parse(createdBody["occurredAt"].asText())

        // The customer-facing notification notification-service drains (ADR-0070), keyed by party.
        val notification = awaitNotification(partyId.toString(), "ACCOUNT_OPENED")
        val request = mapper.readTree(notification.value())
        assertThat(request["channel"].asText()).isEqualTo("PUSH")
        assertThat(request["recipient"].asText()).isEqualTo(partyId.toString())
        assertThat(request["variables"]["accountNumber"].asText()).isEqualTo(accountNumber)

        // Idempotency sentinel: replaying the same Idempotency-Key converges on the winner's
        // account and publishes nothing further — the wire must still carry exactly one
        // AccountCreated record for this aggregate.
        val replayed = openAccount(partyId, idempotencyKey)
        assertThat(replayed.statusCode).isEqualTo(opened.statusCode)
        assertThat(replayed.jsonPath().getString("id")).isEqualTo(accountId)
        Thread.sleep(2_000)
        val createdRecords = observed().filter {
            it.topic() == ACCOUNT_CREATED_TOPIC && it.key() == accountId && eventType(it) == "AccountCreated"
        }
        assertThat(createdRecords)
            .describedAs("an idempotent replay must not emit a second AccountCreated")
            .hasSize(1)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `closing an account puts the closed event and the closed notification on the wire`() {
        val partyId = UUID.randomUUID()
        val opened = openAccount(partyId, UUID.randomUUID().toString())
        assertThat(opened.statusCode).isEqualTo(201)
        val accountId = opened.jsonPath().getString("id")

        val closed = RestAssured.given()
            .contentType("application/json")
            .body("""{"reason": "Customer request"}""")
            .post("/api/v1/accounts/$accountId/close")
        assertThat(closed.statusCode).isEqualTo(200)

        val record = awaitWireRecord(ACCOUNT_CREATED_TOPIC, accountId, "AccountClosed")
        assertThat(record.key()).isEqualTo(accountId)
        val body = mapper.readTree(record.value())
        assertThat(body["eventType"].asText()).isEqualTo("AccountClosed")
        assertThat(body["aggregateId"].asText()).isEqualTo(accountId)
        assertThat(body["reason"].asText()).isEqualTo("Customer request")
        assertThat(body["version"].asLong()).isGreaterThan(0)
        assertThat(body["sourceService"].asText()).isEqualTo("account-service")
        Instant.parse(body["occurredAt"].asText())

        // The publisher's `topic` parameter is decorative — KafkaAccountEventPublisher sends
        // through the one `account-events-out` emitter, so the closure lands on the SAME topic as
        // the creation. The sentinel counts: after the close has settled, this account id owns
        // exactly two records on the wire — one AccountCreated, one AccountClosed — and a
        // consumer discriminating by the payload's eventType never reads the closure as a
        // second creation.
        Thread.sleep(2_000)
        val recordsForAccount = observed().filter { it.topic() == ACCOUNT_CREATED_TOPIC && it.key() == accountId }
        assertThat(recordsForAccount.map { eventType(it) })
            .containsExactlyInAnyOrder("AccountCreated", "AccountClosed")

        val notification = awaitNotification(partyId.toString(), "ACCOUNT_CLOSED")
        val request = mapper.readTree(notification.value())
        assertThat(request["template"].asText()).isEqualTo("ACCOUNT_CLOSED")
        assertThat(request["channel"].asText()).isEqualTo("PUSH")
        // Measured contract: notifyAccountClosed sends exactly {accountNumber} — the close reason
        // is NOT carried into the notification (only ACCOUNT_FROZEN carries a reason variable).
        // notification-service renders ${vars.v("x")} and a missing key is a hole in the customer's
        // message, so if the ACCOUNT_CLOSED template ever declares a reason variable this exact
        // assertion is where the gap surfaces.
        assertThat(request["variables"].fieldNames().asSequence().toList())
            .containsExactly("accountNumber")
        assertThat(request["variables"]["accountNumber"].asText())
            .isEqualTo(opened.jsonPath().getString("accountNumber"))
    }

    // ── REST flows ───────────────────────────────────────────────────────────────

    private fun openAccount(partyId: UUID, idempotencyKey: String): io.restassured.response.Response =
        RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(
                """
                {
                  "partyId": "$partyId",
                  "productId": "$PRODUCT_ID",
                  "accountType": "CURRENT",
                  "currencyCode": "CZK",
                  "legalName": "Wire E2E Customer"
                }
                """.trimIndent(),
            )
            .post("/api/v1/accounts")

    // ── wire read-back ───────────────────────────────────────────────────────────

    private fun eventType(record: ConsumerRecord<String, String>): String? =
        runCatching { mapper.readTree(record.value())["eventType"]?.asText() }.getOrNull()

    private fun template(record: ConsumerRecord<String, String>): String? =
        runCatching { mapper.readTree(record.value())["template"]?.asText() }.getOrNull()

    private fun awaitNotification(key: String, template: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull {
                it.topic() == NOTIFICATION_TOPIC && it.key() == key && template(it) == template
            }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.topic()}/${it.key()}/${template(it)}" }
        throw AssertionError("no $template notification for key $key within 45s; observed: [$seen]")
    }

    private fun awaitWireRecord(topic: String, key: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull { it.topic() == topic && it.key() == key }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.topic()}/${it.key()}" }
        throw AssertionError("no record for key $key on $topic within 45s; observed: [$seen]")
    }

    private fun awaitWireRecord(topic: String, key: String, type: String): ConsumerRecord<String, String> {
        val deadline = Instant.now().plusSeconds(45)
        while (Instant.now().isBefore(deadline)) {
            val found = observed().firstOrNull {
                it.topic() == topic && it.key() == key && eventType(it) == type
            }
            if (found != null) return found
            Thread.sleep(200)
        }
        val seen = observed().joinToString(", ") { "${it.topic()}/${it.key()}/${eventType(it)}" }
        throw AssertionError("no $type for key $key on $topic within 45s; observed: [$seen]")
    }

    private fun observed(): List<ConsumerRecord<String, String>> = OBSERVED

    companion object {
        private const val ACTOR = "00000000-0000-0000-0000-000000000099"
        private const val ACCOUNT_CREATED_TOPIC = "openbank.accounts.account.created"
        private const val NOTIFICATION_TOPIC = "openbank.notification.requests"

        /** Seeded CZK current-account product (see AccountApiIT). */
        private const val PRODUCT_ID = "00000000-2222-0000-0000-000000000001"

        private val OBSERVED = CopyOnWriteArrayList<ConsumerRecord<String, String>>()

        @BeforeAll
        @JvmStatic
        fun wireTap() {
            OBSERVED.clear()
            ensureTopic(ACCOUNT_CREATED_TOPIC)
            ensureTopic(NOTIFICATION_TOPIC)
            val props = mapOf(
                "bootstrap.servers" to bootstrapServers(),
                "group.id" to "account-wire-read-${UUID.randomUUID()}",
                "auto.offset.reset" to "earliest",
                "enable.auto.commit" to "true",
                "key.deserializer" to StringDeserializer::class.qualifiedName,
                "value.deserializer" to StringDeserializer::class.qualifiedName,
            )
            val consumer = KafkaConsumer<String, String>(props)
            consumer.subscribe(listOf(ACCOUNT_CREATED_TOPIC, NOTIFICATION_TOPIC))
            Thread {
                while (true) {
                    consumer.poll(Duration.ofMillis(500)).forEach { OBSERVED.add(it) }
                }
            }.apply {
                isDaemon = true
                name = "account-wire-tap"
                start()
            }
        }

        private fun ensureTopic(topic: String) {
            Admin.create(mapOf("bootstrap.servers" to bootstrapServers())).use { admin ->
                try {
                    admin.createTopics(listOf(NewTopic(topic, 3, 1))).all().get(30, TimeUnit.SECONDS)
                } catch (e: ExecutionException) {
                    if (e.cause !is TopicExistsException) throw e
                }
            }
        }

        private fun bootstrapServers(): String =
            ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
    }
}
