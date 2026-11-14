// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import jakarta.enterprise.inject.Any as AnyQualifier

/**
 * ADR-0374: a domestic payment parked by payment admission reports HELD. The projection's status
 * vocabulary is closed and an event outside it is refused, so the stage has to be known here before
 * the rail emits it. Driven through the real channel into a real Postgres: the rows are the proof.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class HeldDomesticPaymentProjectionIT {
    @Inject
    @AnyQualifier
    lateinit var connector: InMemoryConnector

    @Test
    fun `a held domestic payment is projected as a stage and its case request is not a lifecycle event`() {
        val paymentId = UUID.randomUUID()
        val payments = connector.source<String>("domestic-payment-events-in")
        payments.runOnVertxContext(true)

        payments.send(lifecycle(paymentId, 1, "RECEIVED"))
        payments.send(
            """{"eventType":"PAYMENT_HELD","sourceService":"domestic-payment","paymentId":"$paymentId",""" +
                """"aggregateRevision":2,"occurredAt":"${NOW.plusSeconds(2)}"}""",
        )
        payments.send(lifecycle(paymentId, 2, "HELD"))
        payments.send(lifecycle(paymentId, 3, "VALIDATED"))

        await { projected("event_key = ?", "domestic-payment:$paymentId:3") == 1 }
        assertThat(projected("event_key = ?", "domestic-payment:$paymentId:2"))
            .describedAs("the HELD stage is projected, not refused")
            .isEqualTo(1)
        assertThat(projected("aggregate_ref = ?", "transaction:$paymentId"))
            .describedAs("three lifecycle events and nothing for the case request")
            .isEqualTo(3)
    }

    private fun lifecycle(paymentId: UUID, revision: Long, status: String): String {
        val eventType = if (revision == 1L) "DOMESTIC_PAYMENT_CREATED" else "DOMESTIC_PAYMENT_STATUS_CHANGED"
        val statusField = if (revision == 1L) "\"status\":\"$status\"" else "\"newStatus\":\"$status\""
        return """{"eventType":"$eventType","sourceService":"domestic-payment",""" +
            """"paymentId":"$paymentId","aggregateRevision":$revision,$statusField,""" +
            """"occurredAt":"${NOW.plusSeconds(revision)}"}"""
    }

    private fun projected(predicate: String, value: String): Int = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM context_projection_events WHERE $predicate",
        ).use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun await(ready: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline && !ready()) Thread.sleep(POLL_MILLIS)
    }

    private companion object {
        const val WAIT_NANOS = 10_000_000_000L
        const val POLL_MILLIS = 25L
        val NOW: Instant = Instant.now().minusSeconds(60)
    }
}
