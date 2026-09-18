// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
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
    fun `reviewed graph facts cannot be inserted without a pending proposal`() {
        val id = UUID.randomUUID()
        val relatedId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val hash = "a".repeat(64)
        val attemptedApprovals = listOf(
            """INSERT INTO lending_graph_asset
               (asset_id, canonical_asset_id, revision, asset_type, identity_jurisdiction,
                source_register, source_record_ref, source_document_id, source_sha256,
                proposed_by, proposed_at, status, decided_by, decided_at)
               VALUES ('$id', '$id', 1, 'REAL_ESTATE', 'GB', 'TEST_REGISTRY', '$id', '$documentId', '$hash',
                       'maker', now(), 'APPROVED', 'checker', now())""",
            """INSERT INTO lending_graph_allocation
               (allocation_id, asset_id, collateral_id, revision, secured_amount, currency,
                priority, valid_from, source_document_id, source_sha256, proposed_by, proposed_at,
                status, decided_by, decided_at)
               VALUES ('$id', '$relatedId', '$relatedId', 1, 100, 'EUR',
                       1, now(), '$documentId', '$hash', 'maker', now(),
                       'APPROVED', 'checker', now())""",
            """INSERT INTO lending_graph_valuation
               (valuation_id, asset_id, amount, currency, basis, effective_at,
                source_document_id, source_sha256, proposed_by, proposed_at,
                status, decided_by, decided_at)
               VALUES ('$id', '$relatedId', 100, 'EUR', 'MARKET', now(),
                       '$documentId', '$hash', 'maker', now(),
                       'APPROVED', 'checker', now())""",
            """INSERT INTO lending_graph_guarantee
               (guarantee_id, contract_id, revision, loan_id, guarantor_party_id,
                cap_amount, currency, coverage_fraction, seniority, valid_from,
                source_document_id, source_sha256, proposed_by, proposed_at,
                status, decided_by, decided_at)
               VALUES ('$id', '$relatedId', 1, '$relatedId', '$relatedId',
                       100, 'EUR', 1, 1, now(), '$documentId', '$hash', 'maker', now(),
                       'APPROVED', 'checker', now())""",
        )
        dataSource.connection.use { connection ->
            attemptedApprovals.forEach { sql ->
                assertThatThrownBy { connection.createStatement().use { it.executeUpdate(sql) } }
                    .hasMessageContaining("lending graph evidence must start pending")
            }
        }
    }

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
    fun `asset identity rejects an invalid register jurisdiction`() {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            assertThatThrownBy {
                connection.prepareStatement(
                    """INSERT INTO lending_graph_asset
                       (asset_id, canonical_asset_id, revision, asset_type, identity_jurisdiction,
                        source_register, source_record_ref, source_document_id,
                        source_sha256, proposed_by, proposed_at)
                       VALUES (?, ?, 1, 'REAL_ESTATE', 'g1', 'TEST_REGISTRY', ?, ?, ?, 'maker', now())""",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, id)
                    statement.setString(3, id.toString())
                    statement.setObject(4, UUID.randomUUID())
                    statement.setString(5, "a".repeat(64))
                    statement.executeUpdate()
                }
            }.hasMessageContaining("lending_graph_asset_identity_jurisdiction_check")
        }
    }

    @Test
    fun `one register record cannot create two canonical asset roots`() {
        val firstId = createPendingAsset()
        val secondId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            assertThatThrownBy {
                connection.prepareStatement(
                    """INSERT INTO lending_graph_asset
                       (asset_id, canonical_asset_id, revision, asset_type, identity_jurisdiction,
                        source_register, source_record_ref, source_document_id, source_sha256,
                        proposed_by, proposed_at)
                       VALUES (?, ?, 1, 'REAL_ESTATE', 'GB', 'TEST_REGISTRY', ?, ?, ?, 'other-maker', now())""",
                ).use { statement ->
                    statement.setObject(1, secondId)
                    statement.setObject(2, secondId)
                    statement.setString(3, firstId.toString())
                    statement.setObject(4, UUID.randomUUID())
                    statement.setString(5, "b".repeat(64))
                    statement.executeUpdate()
                }
            }.hasMessageContaining("uq_lending_graph_asset_source_root")
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
    fun `released collateral cannot be approved as a graph allocation`() {
        val assetId = createPendingAsset()
        val allocationId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val collateralId = createApprovedCollateral(connection)
                connection.prepareStatement(
                    """UPDATE lending_graph_asset SET status = 'APPROVED',
                   decided_by = 'asset-checker', decided_at = now() WHERE asset_id = ?""",
                ).use { statement ->
                    statement.setObject(1, assetId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
                connection.prepareStatement(
                    """INSERT INTO lending_graph_allocation
                   (allocation_id, asset_id, collateral_id, revision, secured_amount, currency,
                    priority, valid_from, source_document_id, source_sha256, proposed_by, proposed_at)
                   VALUES (?, ?, ?, 1, 100, 'EUR', 1, now(), ?, ?, 'allocation-maker', now())""",
                ).use { statement ->
                    statement.setObject(1, allocationId)
                    statement.setObject(2, assetId)
                    statement.setObject(3, collateralId)
                    statement.setObject(4, UUID.randomUUID())
                    statement.setString(5, "c".repeat(64))
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
                connection.prepareStatement("UPDATE collateral SET released_at = now() WHERE id = ?").use { statement ->
                    statement.setObject(1, collateralId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
                val beforeRejectedApproval = connection.setSavepoint()
                assertThatThrownBy {
                    connection.prepareStatement(
                        """UPDATE lending_graph_allocation SET status = 'APPROVED',
                       decided_by = 'allocation-checker', decided_at = now() WHERE allocation_id = ?""",
                    ).use { statement ->
                        statement.setObject(1, allocationId)
                        statement.executeUpdate()
                    }
                }.hasMessageContaining("approved matching collateral evidence is required at decision")
                connection.rollback(beforeRejectedApproval)
                connection.prepareStatement(
                    """UPDATE lending_graph_allocation SET status = 'REJECTED',
                   decided_by = 'allocation-checker', decided_at = now() WHERE allocation_id = ?""",
                ).use { statement ->
                    statement.setObject(1, allocationId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
            } finally {
                connection.rollback()
            }
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
                       (asset_id, canonical_asset_id, revision, asset_type, identity_jurisdiction,
                        source_register, source_record_ref, source_document_id, source_sha256,
                        supersedes_asset_id, proposed_by, proposed_at)
                       VALUES (?, ?, 2, 'REAL_ESTATE', 'GB', 'TEST_REGISTRY', ?, ?, ?, ?, 'second-maker', now())""",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, pendingId)
                    statement.setString(3, pendingId.toString())
                    statement.setObject(4, UUID.randomUUID())
                    statement.setString(5, "b".repeat(64))
                    statement.setObject(6, pendingId)
                    statement.executeUpdate()
                }
            }.hasMessageContaining("must supersede the approved prior revision")
        }
    }

    @Test
    fun `asset correction keeps one canonical identity and cannot become an allocation root`() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val rootId = createPendingAsset(connection)
                val correctionId = UUID.randomUUID()
                val collateralId = createApprovedCollateral(connection)
                connection.prepareStatement(
                    """UPDATE lending_graph_asset SET status = 'APPROVED',
                       decided_by = 'asset-checker', decided_at = now() WHERE asset_id = ?""",
                ).use { statement ->
                    statement.setObject(1, rootId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
                connection.prepareStatement(
                    """INSERT INTO lending_graph_asset
                       (asset_id, canonical_asset_id, revision, asset_type, identity_jurisdiction,
                        source_register, source_record_ref, source_document_id, source_sha256,
                        supersedes_asset_id, proposed_by, proposed_at)
                       VALUES (?, ?, 2, 'REAL_ESTATE', 'GB', 'TEST_REGISTRY', ?, ?, ?, ?, 'second-maker', now())""",
                ).use { statement ->
                    statement.setObject(1, correctionId)
                    statement.setObject(2, rootId)
                    statement.setString(3, rootId.toString())
                    statement.setObject(4, UUID.randomUUID())
                    statement.setString(5, "b".repeat(64))
                    statement.setObject(6, rootId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
                connection.prepareStatement(
                    """UPDATE lending_graph_asset SET status = 'APPROVED',
                       decided_by = 'second-checker', decided_at = now() WHERE asset_id = ?""",
                ).use { statement ->
                    statement.setObject(1, correctionId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
                connection.prepareStatement(
                    "SELECT canonical_asset_id, revision FROM lending_graph_asset WHERE asset_id = ?",
                ).use { statement ->
                    statement.setObject(1, correctionId)
                    statement.executeQuery().use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getObject(1, UUID::class.java)).isEqualTo(rootId)
                        assertThat(rows.getLong(2)).isEqualTo(2)
                    }
                }

                val beforeRejectedAllocation = connection.setSavepoint()
                assertThatThrownBy {
                    connection.prepareStatement(
                        """INSERT INTO lending_graph_allocation
                           (allocation_id, asset_id, collateral_id, revision, secured_amount,
                            currency, priority, valid_from, source_document_id, source_sha256,
                            proposed_by, proposed_at)
                           VALUES (?, ?, ?, 1, 100, 'EUR', 1, now(), ?, ?, 'maker', now())""",
                    ).use { statement ->
                        statement.setObject(1, UUID.randomUUID())
                        statement.setObject(2, correctionId)
                        statement.setObject(3, collateralId)
                        statement.setObject(4, UUID.randomUUID())
                        statement.setString(5, "c".repeat(64))
                        statement.executeUpdate()
                    }
                }.hasMessageContaining("approved asset identity is required")
                connection.rollback(beforeRejectedAllocation)
            } finally {
                connection.rollback()
            }
        }
    }

    private fun createPendingAsset(): UUID = dataSource.connection.use(::createPendingAsset)

    private fun createPendingAsset(connection: Connection): UUID {
        val id = UUID.randomUUID()
        connection.prepareStatement(
            """INSERT INTO lending_graph_asset
                   (asset_id, canonical_asset_id, revision, asset_type, identity_jurisdiction,
                    source_register, source_record_ref, source_document_id,
                    source_sha256, proposed_by, proposed_at)
                   VALUES (?, ?, 1, 'REAL_ESTATE', 'GB', 'TEST_REGISTRY', ?, ?, ?, 'maker', now())""",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, id)
            statement.setString(3, id.toString())
            statement.setObject(4, UUID.randomUUID())
            statement.setString(5, "a".repeat(64))
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
        return id
    }

    private fun createApprovedCollateral(connection: Connection): UUID {
        val applicationId = UUID.randomUUID()
        val loanId = UUID.randomUUID()
        val collateralId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        connection.prepareStatement(
            """INSERT INTO loan_application
                   (id, party_id, requested_amount, currency, nominal_annual_rate,
                    term_periods, first_due_date, proposed_by)
                   VALUES (?, ?, 1000, 'EUR', 0.05, 12, current_date + 30, 'loan-maker')""",
        ).use { statement ->
            statement.setObject(1, applicationId)
            statement.setObject(2, partyId)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
        connection.prepareStatement(
            """INSERT INTO loan
                   (id, application_id, party_id, principal, currency, nominal_annual_rate,
                    term_periods, method, first_due_date)
                   VALUES (?, ?, ?, 1000, 'EUR', 0.05, 12, 'ANNUITY', current_date + 30)""",
        ).use { statement ->
            statement.setObject(1, loanId)
            statement.setObject(2, applicationId)
            statement.setObject(3, partyId)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
        connection.prepareStatement(
            """INSERT INTO collateral
                   (id, loan_id, type, market_value, currency, status,
                    registered_by, decided_by, decided_at)
                   VALUES (?, ?, 'REAL_ESTATE', 1000, 'EUR', 'APPROVED',
                           'collateral-maker', 'collateral-checker', now())""",
        ).use { statement ->
            statement.setObject(1, collateralId)
            statement.setObject(2, loanId)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
        return collateralId
    }
}
