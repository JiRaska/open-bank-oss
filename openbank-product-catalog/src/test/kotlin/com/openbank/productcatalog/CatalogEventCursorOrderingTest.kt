// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_cursor_order")],
)
class CatalogEventCursorOrderingTest {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `outbox insert order cannot be overtaken by a later commit`() {
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        dataSource.connection.use { first ->
            first.autoCommit = false
            insertEvent(first, firstId, OffsetDateTime.parse("2100-01-01T00:00:00Z"))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val secondCommit = executor.submit {
                    dataSource.connection.use { second ->
                        second.autoCommit = false
                        insertEvent(second, secondId, OffsetDateTime.parse("1900-01-01T00:00:00Z"))
                        second.commit()
                    }
                }
                Thread.sleep(BLOCKING_PROOF_MILLIS)
                assertThat(secondCommit.isDone).isFalse()
                first.commit()
                secondCommit.get(10, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id, cursor_position FROM catalog_outbox WHERE id IN (?, ?) ORDER BY cursor_position",
            ).use { statement ->
                statement.setObject(1, firstId)
                statement.setObject(2, secondId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1, UUID::class.java)).isEqualTo(firstId)
                    val firstPosition = rows.getLong(2)
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1, UUID::class.java)).isEqualTo(secondId)
                    assertThat(rows.getLong(2)).isGreaterThan(firstPosition)
                }
            }
        }
    }

    @Test
    fun `durable cursor position cannot be rewritten`() {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection -> insertEvent(connection, id, OffsetDateTime.now()) }

        assertThatThrownBy {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE catalog_outbox SET cursor_position = cursor_position + 1000000 WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.executeUpdate()
                }
            }
        }.hasMessageContaining("payload is immutable")
    }

    private fun insertEvent(connection: Connection, id: UUID, occurredAt: OffsetDateTime) {
        connection.prepareStatement(
            "INSERT INTO catalog_outbox " +
                "(id, aggregate_type, aggregate_id, event_type, schema_version, occurred_at, payload) " +
                "VALUES (?, 'TEST', ?, 'CursorOrderingTest', 1, ?, '{}'::jsonb)",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, UUID.randomUUID())
            statement.setObject(3, occurredAt)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
    }

    private companion object {
        const val BLOCKING_PROOF_MILLIS = 250L
    }
}
