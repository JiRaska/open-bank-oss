// SPDX-License-Identifier: Apache-2.0
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

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ContextNodeConcurrencyIT {
    @Inject
    @AnyQualifier
    lateinit var connector: InMemoryConnector

    @Test
    fun `concurrent ledger placeholders and transaction evidence retain both projections`() {
        val transactions = connector.source<String>("transaction-events-in")
        val ledger = connector.source<String>("ledger-events-in")
        transactions.runOnVertxContext(true)
        ledger.runOnVertxContext(true)

        repeat(32) { index ->
            val transactionId = UUID.randomUUID()
            val paymentId = UUID.randomUUID()
            val journalId = UUID.randomUUID()
            // Separate channels run concurrently and insert the same natural-key node.
            if (index % 2 == 0) {
                ledger.send(ledgerPostedEvent(journalId, transactionId))
                transactions.send(transactionInitiatedEvent(transactionId, paymentId))
            } else {
                transactions.send(transactionInitiatedEvent(transactionId, paymentId))
                ledger.send(ledgerPostedEvent(journalId, transactionId))
            }

            val key = "booking-transaction:$transactionId"
            awaitCount("context_projection_events", "aggregate_ref", key, 1)
            awaitCount("context_projection_events", "aggregate_ref", "ledger-booking:$journalId", 1)
            assertThat(count("context_nodes", "node_key = ?", key)).isEqualTo(1)
            assertThat(stringValue("context_nodes", "source_system", "node_key", key))
                .isEqualTo("transaction-service")
            val rowId = stringValue("context_nodes", "node_row_id", "node_key", key)
            transactions.send(transactionInitiatedEvent(transactionId, paymentId, version = 1))
            awaitCount("context_projection_events", "aggregate_ref", key, 2)
            assertThat(stringValue("context_nodes", "node_row_id", "node_key", key)).isEqualTo(rowId)
            assertThat(count("context_nodes", "node_key = ? AND source_version = 1", key)).isEqualTo(1)
        }
    }

    private fun transactionInitiatedEvent(transactionId: UUID, paymentId: UUID, version: Int = 0): String =
        """{"eventType":"TransactionInitiated","sourceService":"transaction-service",""" +
            """"aggregateId":"$transactionId","version":$version,"originatingPaymentId":"$paymentId",""" +
            """"occurredAt":"${Instant.now().minusSeconds(2)}"}"""

    private fun ledgerPostedEvent(journalId: UUID, transactionId: UUID): String =
        """{"eventType":"JournalPosted","sourceService":"ledger-service","aggregateId":"$journalId",""" +
            """"version":0,"transactionId":"$transactionId","entryDate":"2026-09-13",""" +
            """"occurredAt":"${Instant.now().minusSeconds(1)}"}"""

    private fun count(table: String, predicate: String, value: Any): Int = connection().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM $table WHERE $predicate").use { statement ->
            statement.setObject(1, value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun stringValue(table: String, column: String, keyColumn: String, key: Any): String =
        connection().use { connection ->
            connection.prepareStatement("SELECT $column FROM $table WHERE $keyColumn = ?").use { statement ->
                statement.setObject(1, key)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }

    private fun awaitCount(table: String, column: String, value: Any, expected: Int) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (count(table, "$column = ?", value) == expected) return
            Thread.sleep(25)
        }
        assertThat(count(table, "$column = ?", value)).isEqualTo(expected)
    }

    private fun connection() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    )
}
