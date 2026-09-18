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

/** Real-Postgres proof that a graph fact cannot become an approved or rewritten identity by itself. */
@QuarkusTest
@QuarkusTestResource(LendingBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class LendingGraphSourceSchemaIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `an asset requires a different checker and approved evidence cannot be changed`() {
        val assetId = createPendingAsset()
        dataSource.connection.use { connection ->
            assertThatThrownBy {
                connection.prepareStatement(
                    """UPDATE lending_graph_asset SET status = 'APPROVED',
                       decided_by = 'maker', decided_at = now() WHERE asset_id = ?""",
                ).use { statement ->
                    statement.setObject(1, assetId)
                    statement.executeUpdate()
                }
            }.hasMessageContaining("lending_graph_asset_decision")

            connection.prepareStatement(
                """UPDATE lending_graph_asset SET status = 'APPROVED',
                   decided_by = 'checker', decided_at = now() WHERE asset_id = ?""",
            ).use { statement ->
                statement.setObject(1, assetId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }

            assertThatThrownBy {
                connection.prepareStatement(
                    "UPDATE lending_graph_asset SET source_sha256 = ? WHERE asset_id = ?",
                ).use { statement ->
                    statement.setString(1, "b".repeat(64))
                    statement.setObject(2, assetId)
                    statement.executeUpdate()
                }
            }.hasMessageContaining("immutable")

            assertThatThrownBy {
                connection.prepareStatement("DELETE FROM lending_graph_asset WHERE asset_id = ?").use { statement ->
                    statement.setObject(1, assetId)
                    statement.executeUpdate()
                }
            }.hasMessageContaining("cannot be deleted")
        }
    }

    @Test
    fun `an allocation cannot point to an invented collateral item`() {
        val assetId = createPendingAsset()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE lending_graph_asset SET status = 'APPROVED',
                   decided_by = 'checker', decided_at = now() WHERE asset_id = ?""",
            ).use { statement ->
                statement.setObject(1, assetId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
            assertThatThrownBy {
                connection.prepareStatement(
                    """INSERT INTO lending_graph_allocation
                       (allocation_id, asset_id, collateral_id, revision, secured_amount, currency,
                        priority, valid_from, source_document_id, source_sha256, proposed_by, proposed_at)
                       VALUES (?, ?, ?, 1, 100, 'EUR', 1, now(), ?, ?, 'maker', now())""",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, assetId)
                    statement.setObject(3, UUID.randomUUID())
                    statement.setObject(4, UUID.randomUUID())
                    statement.setString(5, "c".repeat(64))
                    statement.executeUpdate()
                }
            }.hasMessageContaining("approved matching collateral evidence is required")
        }
    }

    @Test
    fun `evidence tables reject truncate even with table ownership`() {
        dataSource.connection.use { connection ->
            assertThatThrownBy {
                connection.createStatement().use { it.execute("TRUNCATE lending_graph_guarantee") }
            }.hasMessageContaining("cannot be truncated")
        }
    }

    @Test
    fun `a correction cannot supersede an unapproved asset identity`() {
        val pendingId = createPendingAsset()
        dataSource.connection.use { connection ->
            assertThatThrownBy {
                connection.prepareStatement(
                    """INSERT INTO lending_graph_asset
                       (asset_id, asset_type, source_document_id, source_sha256,
                        supersedes_asset_id, proposed_by, proposed_at)
                       VALUES (?, 'REAL_ESTATE', ?, ?, ?, 'second-maker', now())""",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, UUID.randomUUID())
                    statement.setString(3, "b".repeat(64))
                    statement.setObject(4, pendingId)
                    statement.executeUpdate()
                }
            }.hasMessageContaining("must supersede an approved identity")
        }
    }

    private fun createPendingAsset(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO lending_graph_asset
                   (asset_id, asset_type, source_document_id, source_sha256, proposed_by, proposed_at)
                   VALUES (?, 'REAL_ESTATE', ?, ?, 'maker', now())""",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.randomUUID())
                statement.setString(3, "a".repeat(64))
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
        return id
    }
}
