// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/** ADR-0152: one bank per deployment; evidence references stay local to Lending's database. */
@QuarkusTest
@QuarkusTestResource(LendingBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class LendingGraphDeploymentBoundaryIT {
    @Inject lateinit var dataSource: DataSource

    @Test
    fun `graph source has no per-row bank dimension and rejects unknown asset references`() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT table_name FROM information_schema.columns
                   WHERE table_schema = current_schema()
                     AND table_name IN ('lending_graph_asset', 'lending_graph_allocation',
                                        'lending_graph_valuation', 'lending_graph_guarantee')
                     AND column_name = 'bank_scope'""",
            ).use { statement ->
                statement.executeQuery().use { rows -> assertThat(rows.next()).isFalse() }
            }
            assertThatThrownBy {
                connection.prepareStatement(
                    """INSERT INTO lending_graph_valuation
                       (valuation_id, asset_id, amount, currency, basis, effective_at,
                        source_document_id, source_sha256, proposed_by, proposed_at)
                       VALUES (?, ?, 100, 'EUR', 'MARKET', now(), ?, ?, 'maker', now())""",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, UUID.randomUUID())
                    statement.setObject(3, UUID.randomUUID())
                    statement.setString(4, "a".repeat(64))
                    statement.executeUpdate()
                }
            }.hasMessageContaining("lending_graph_valuation_asset_fk")
        }
    }
}
