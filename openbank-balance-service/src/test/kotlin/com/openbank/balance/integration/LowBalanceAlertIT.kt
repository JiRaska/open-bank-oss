// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.infrastructure.client.AccountServiceClient
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Real HTTP, PostgreSQL and Kafka: a threshold crossing commits one durable inbox intent. */
@QuarkusTest
@TestProfile(LowBalanceAlertIT.EnabledProfile::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
class LowBalanceAlertIT {
    class EnabledProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(
                com.openbank.balance.it.PostgresRedpandaTestResource::class.java,
            ),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.balance.low-alerts-enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
            "mp.messaging.incoming.balance-alerts-in.auto.offset.reset" to "earliest",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var mapper: ObjectMapper

    @Test
    fun `owned opt in produces one deduplicated inbox intent on downward crossing`() {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val owner = mockk<AccountServiceClient>()
        coEvery { owner.getPartyId(accountId) } returns partyId
        QuarkusMock.installMockForType(owner, AccountServiceClient::class.java)

        Given {
            contentType("application/json")
            body("""{"currency":"CZK","initialAmount":200}""")
        } When {
            post("/api/v1/balances/$accountId/initialize")
        } Then {
            statusCode(201)
        }

        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", partyId.toString())
            body("""{"enabled":true,"threshold":100,"rearmMargin":20}""")
        } When {
            put("/api/v1/balances/$accountId/CZK/low-balance-alert")
        } Then {
            statusCode(200)
        }

        Given {
            contentType("application/json")
            body("""{"amount":150,"currency":"CZK","referenceId":"alert-first"}""")
        } When {
            post("/api/v1/balances/$accountId/debit")
        } Then {
            statusCode(200)
        }

        wakeAlertConsumer(accountId)
        waitForIntent(accountId)
        val payload = alertPayloads(accountId).single()
        val request = mapper.readTree(payload)
        assertThat(request.path("partyId").asText()).isEqualTo(partyId.toString())
        assertThat(request.path("channel").asText()).isEqualTo("INBOX")
        assertThat(request.path("template").asText()).isEqualTo("LOW_BALANCE_ALERT")
        assertThat(request.path("variables").size()).isZero()
        assertThat(request.path("deduplicationKey").asText()).isNotBlank()

        Given {
            contentType("application/json")
            body("""{"amount":1,"currency":"CZK","referenceId":"alert-second"}""")
        } When {
            post("/api/v1/balances/$accountId/debit")
        } Then {
            statusCode(200)
        }
        wakeAlertConsumer(accountId)
        Thread.sleep(500)
        assertThat(alertPayloads(accountId)).hasSize(1)
    }

    private fun wakeAlertConsumer(accountId: UUID) {
        val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
        val config = mapOf(
            "bootstrap.servers" to bootstrap,
            "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "acks" to "all",
        )
        val payload = mapper.writeValueAsString(
            mapOf("eventType" to "BALANCE_UPDATED", "accountId" to accountId, "currency" to "CZK"),
        )
        KafkaProducer<String, String>(config).use { producer ->
            producer.send(ProducerRecord("openbank.balance.events", accountId.toString(), payload))
                .get(10, TimeUnit.SECONDS)
        }
    }

    private fun alertPayloads(accountId: UUID): List<String> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT payload FROM balance_outbox WHERE aggregate_id = ? AND event_type = 'LOW_BALANCE_ALERT_REQUEST'",
        ).use { statement ->
            statement.setObject(1, accountId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    private fun waitForIntent(accountId: UUID) {
        val deadline = Instant.now().plus(Duration.ofSeconds(15))
        while (Instant.now().isBefore(deadline)) {
            if (alertPayloads(accountId).isNotEmpty()) return
            Thread.sleep(100)
        }
        error("low-balance notification intent was not written: ${diagnostics(accountId)}")
    }

    @Suppress("NestedBlockDepth")
    private fun diagnostics(accountId: UUID): String = dataSource.connection.use { conn ->
        val outbox = conn.prepareStatement(
            "SELECT event_type, status FROM balance_outbox WHERE aggregate_id = ? ORDER BY created_at",
        ).use { statement ->
            statement.setObject(1, accountId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add("${rows.getString(1)}:${rows.getString(2)}") }
            }
        }
        val preference = conn.prepareStatement(
            "SELECT enabled, armed, generation FROM balance_low_alert_preferences WHERE account_id = ?",
        ).use { statement ->
            statement.setObject(1, accountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) "${rows.getBoolean(1)}:${rows.getBoolean(2)}:${rows.getLong(3)}" else "missing"
            }
        }
        "outbox=$outbox preference=$preference"
    }
}
