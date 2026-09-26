// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Lost update between the two halves of the KYC+AML activation gate.
 *
 * The KYC and AML outcomes arrive on two independent channels (`kyc-events-in`,
 * `aml-events-in`) and are applied concurrently. `updateKycStatus`/`updateAmlStatus` used to
 * read the party in one session and write the whole row back in another, so when both landed at
 * once each overwrote the other's column: a party with KYC APPROVED and AML CLEARED was left
 * PENDING_KYC with one outcome silently gone — and since nothing replays either event, it stayed
 * there. `PartyRepository.modify` now does the read and the write in one transaction under a row
 * lock.
 *
 * Only real concurrency against a real Postgres can show that, so this goes through the real
 * entry points: a party created over REST, then both compliance events published to the real
 * topics at the same instant (a [CyclicBarrier]) and consumed by the real channels. It repeats
 * the race [ITERATIONS] times and asserts EVERY party ends ACTIVE with both keys recorded.
 * Removing the lock in `PartyRepositoryImpl.modify` makes this fail — see the PR for the rate.
 *
 * The dispatcher is off so the outbox rows stay put: two KYC_STATUS_CHANGED rows for a party are
 * the signal that BOTH handlers have committed, after which the row is final and can be judged.
 */
@QuarkusTest
@QuarkusTestResource(PartyKycAmlConcurrencyIT.ConsumersFromEarliestResource::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class PartyKycAmlConcurrencyIT {

    class ConsumersFromEarliestResource : QuarkusTestResourceLifecycleManager {
        // Topics are auto-created on first use, after the consumers subscribed: `latest` (the
        // connector default) could skip the very first records this test publishes.
        override fun start(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "mp.messaging.incoming.kyc-events-in.auto.offset.reset" to "earliest",
            "mp.messaging.incoming.aml-events-in.auto.offset.reset" to "earliest",
        )
        override fun stop() = Unit
    }

    @Inject
    lateinit var dataSource: DataSource

    private data class Outcome(val status: String, val kyc: String, val aml: String)

    @Test
    @TestSecurity(user = "concurrency-it", roles = ["ROLE_ADMIN"])
    fun `concurrent KYC APPROVED and AML CLEARED always activate the party`() {
        val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
        val pool = Executors.newFixedThreadPool(2)
        val failures = mutableListOf<String>()
        try {
            producer(bootstrap).use { kycProducer ->
                producer(bootstrap).use { amlProducer ->
                    repeat(ITERATIONS) { i ->
                        val id = createParty("race-$i-${UUID.randomUUID()}@example.cz")
                        val barrier = CyclicBarrier(2)
                        val kyc = pool.submit {
                            barrier.await()
                            kycProducer.send(
                                ProducerRecord(
                                    KYC_TOPIC,
                                    id.toString(),
                                    """{"eventType":"KYC_CASE_APPROVED","partyId":"$id"}""",
                                ),
                            ).get()
                        }
                        val aml = pool.submit {
                            barrier.await()
                            amlProducer.send(
                                ProducerRecord(AML_TOPIC, id.toString(), """{"newStatus":"CLEARED","partyId":"$id"}"""),
                            ).get()
                        }
                        kyc.get(SEND_TIMEOUT_S, TimeUnit.SECONDS)
                        aml.get(SEND_TIMEOUT_S, TimeUnit.SECONDS)

                        val outcome = awaitBothApplied(id)
                        if (outcome != Outcome("ACTIVE", "APPROVED", "CLEARED")) failures += "iteration $i: $outcome"
                    }
                }
            }
        } finally {
            pool.shutdownNow()
        }
        println("PartyKycAmlConcurrencyIT: ${failures.size}/$ITERATIONS iterations lost an update")
        assertThat(failures)
            .describedAs("iterations where one half of the activation gate was lost (%d/%d)", failures.size, ITERATIONS)
            .isEmpty()
    }

    /** Waits for both handlers to commit (one KYC_STATUS_CHANGED outbox row each), then reads the row. */
    private fun awaitBothApplied(id: UUID): Outcome? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(APPLY_TIMEOUT_S)
        while (System.nanoTime() < deadline) {
            if (statusEvents(id) >= 2) return outcome(id)
            Thread.sleep(POLL_MS)
        }
        return outcome(id)
    }

    private fun statusEvents(id: UUID): Int = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement(
            "SELECT count(*) FROM party_outbox WHERE aggregate_id = ? AND event_type = 'KYC_STATUS_CHANGED'",
        )
        ps.setObject(1, id)
        val rs = ps.executeQuery()
        rs.next()
        rs.getInt(1)
    }

    private fun outcome(id: UUID): Outcome? = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT status, kyc_status, aml_status FROM parties WHERE party_id = ?")
        ps.setObject(1, id)
        val rs = ps.executeQuery()
        if (rs.next()) Outcome(rs.getString(1), rs.getString(2), rs.getString(3)) else null
    }

    private fun producer(bootstrap: String) = KafkaProducer<String, String>(
        Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.LINGER_MS_CONFIG, 0)
        },
    )

    private fun createParty(email: String): UUID {
        val body = """
            {"partyType":"INDIVIDUAL","legalName":"Race Probe","tradingName":null,
             "dateOfBirth":"1990-01-01","nationality":"CZ","taxId":null,"registrationNumber":null,
             "email":"$email","phone":null,"address":null}
        """.trimIndent()
        val id = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(body)
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        return UUID.fromString(id)
    }

    private companion object {
        const val ITERATIONS = 50
        const val KYC_TOPIC = "openbank.kyc.events"
        const val AML_TOPIC = "openbank.aml.events"
        const val SEND_TIMEOUT_S = 30L
        const val APPLY_TIMEOUT_S = 20L
        const val POLL_MS = 25L
    }
}
