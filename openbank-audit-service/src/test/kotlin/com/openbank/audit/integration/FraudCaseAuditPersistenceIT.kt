// SPDX-License-Identifier: Apache-2.0
package com.openbank.audit.integration

import com.openbank.audit.application.FraudCaseAuditConsumer
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/** A Fraud case lifecycle event reaches the append-only chain and a redelivery stays idempotent. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class FraudCaseAuditPersistenceIT {
    @Inject lateinit var consumer: FraudCaseAuditConsumer

    @Test
    fun `case event is stored once with source actor and event time`() {
        val eventId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-09-18T06:00:00Z")
        val payload = """{"eventId":"$eventId","eventType":"fraud.case_opened.audit", """ +
            """"aggregateId":"$caseId","actorId":"fraud-analyst","revision":1,""" +
            """"occurredAt":"$occurredAt","aggregateType":"FRAUD_CASE", """ +
            """"sourceService":"fraud-service"}"""

        onEventLoop {
            consumer.consume(Message.of(payload))
            consumer.consume(Message.of(payload))
        }
        assertThatThrownBy {
            onEventLoop { consumer.consume(Message.of(payload.replace("\"revision\":1", "\"revision\":2"))) }
        }.isInstanceOf(IllegalStateException::class.java)

        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                """SELECT event_type, aggregate_type, aggregate_id, actor_id, source_service,
                   source_service_source, occurred_at, occurred_at_source, record_hash
                   FROM audit_entries WHERE entry_id = ?""",
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("event_type")).isEqualTo("fraud.case_opened.audit")
                    assertThat(rows.getString("aggregate_type")).isEqualTo("FRAUD_CASE")
                    assertThat(rows.getString("aggregate_id")).isEqualTo(caseId.toString())
                    assertThat(rows.getString("actor_id")).isEqualTo("fraud-analyst")
                    assertThat(rows.getString("source_service")).isEqualTo("fraud-service")
                    assertThat(rows.getString("source_service_source")).isEqualTo("EVENT")
                    assertThat(rows.getTimestamp("occurred_at").toInstant()).isEqualTo(occurredAt)
                    assertThat(rows.getString("occurred_at_source")).isEqualTo("EVENT")
                    assertThat(rows.getString("record_hash")).isNotBlank()
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
