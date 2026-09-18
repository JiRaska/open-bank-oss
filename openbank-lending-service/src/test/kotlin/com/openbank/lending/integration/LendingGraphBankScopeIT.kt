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

/** A shared source reference cannot turn into an edge across bank scopes. */
@QuarkusTest
@QuarkusTestResource(LendingBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class LendingGraphBankScopeIT {
    @Inject lateinit var dataSource: DataSource

    @Test
    fun `asset identity is local to a bank and cross bank valuation is rejected`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val sourceRef = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            listOf("bank-a" to first, "bank-b" to second).forEach { (bank, id) ->
                connection.prepareStatement(
                    """INSERT INTO lending_graph_asset
                       (bank_scope, asset_id, canonical_asset_id, revision, asset_type,
                        identity_jurisdiction, source_register, source_record_ref,
                        source_document_id, source_sha256, proposed_by, proposed_at)
                       VALUES (?, ?, ?, 1, 'REAL_ESTATE', 'GB', 'TEST_REGISTRY',
                               ?, ?, ?, 'maker', now())""",
                ).use { statement ->
                    statement.setString(1, bank)
                    statement.setObject(2, id)
                    statement.setObject(3, id)
                    statement.setString(4, sourceRef)
                    statement.setObject(5, UUID.randomUUID())
                    statement.setString(6, "a".repeat(64))
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
            }
            fun insertValuation(bank: String, asset: UUID) {
                connection.prepareStatement(
                    """INSERT INTO lending_graph_valuation
                       (bank_scope, valuation_id, asset_id, amount, currency, basis,
                        effective_at, source_document_id, source_sha256, proposed_by, proposed_at)
                       VALUES (?, ?, ?, 100, 'EUR', 'MARKET', now(), ?, ?, 'maker', now())""",
                ).use { statement ->
                    statement.setString(1, bank)
                    statement.setObject(2, UUID.randomUUID())
                    statement.setObject(3, asset)
                    statement.setObject(4, UUID.randomUUID())
                    statement.setString(5, "b".repeat(64))
                    statement.executeUpdate()
                }
            }
            assertThatThrownBy { insertValuation("bank-b", first) }
                .hasMessageContaining("lending_graph_valuation_asset_fk")
            insertValuation("bank-a", first)
        }
    }
}
