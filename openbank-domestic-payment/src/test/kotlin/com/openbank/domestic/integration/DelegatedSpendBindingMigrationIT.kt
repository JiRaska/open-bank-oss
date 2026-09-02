// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.domestic.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Real-Postgres proof for V10/V13/V14's delegated-spend binding invariants. */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class DelegatedSpendBindingMigrationIT {

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun clearOwnRows() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.execute("SET CONSTRAINTS ALL DEFERRED") }
            connection.prepareStatement(
                "DELETE FROM domestic_payments WHERE idempotency_key LIKE 'delegation-bind-it-%'",
            ).use { statement -> statement.executeUpdate() }
            connection.prepareStatement(
                "DELETE FROM domestic_payments WHERE reservation_id IN " +
                    "(SELECT reservation_id FROM domestic_delegated_spend_bindings " +
                    "WHERE grantor_party_id = ?)",
            ).use { statement ->
                statement.setObject(1, TEST_GRANTOR_ID)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM domestic_delegated_spend_bindings WHERE grantor_party_id = ?",
            ).use { statement ->
                statement.setObject(1, TEST_GRANTOR_ID)
                statement.executeUpdate()
            }
            connection.commit()
        }
    }

    @AfterEach
    fun removeOwnRows() = clearOwnRows()

    @Test
    fun `migration persists the complete context and database refuses a half pair`() {
        val paymentId = UUID.randomUUID()
        val initiator = UUID.randomUUID()
        val delegation = UUID.randomUUID()
        val reservation = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            insertPendingBinding(connection, reservation, delegation, initiator)
            insert(connection, paymentId, "delegation-bind-it-complete", initiator, delegation, reservation)
            bind(connection, reservation, paymentId)
            connection.commit()

            connection.prepareStatement(
                "SELECT initiated_by_party_id, delegation_id, reservation_id " +
                    "FROM domestic_payments WHERE payment_id = ?",
            ).use { statement ->
                statement.setObject(1, paymentId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject("initiated_by_party_id", UUID::class.java)).isEqualTo(initiator)
                    assertThat(rows.getObject("delegation_id", UUID::class.java)).isEqualTo(delegation)
                    assertThat(rows.getObject("reservation_id", UUID::class.java)).isEqualTo(reservation)
                }
            }

            assertThatThrownBy {
                connection.prepareStatement(
                    "UPDATE domestic_payments SET reservation_id = NULL WHERE payment_id = ?",
                ).use { statement ->
                    statement.setObject(1, paymentId)
                    statement.executeUpdate()
                }
            }.isInstanceOf(SQLException::class.java)
            connection.rollback()
        }
    }

    @Test
    fun `one reservation cannot bind to two payments`() {
        val reservation = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val delegation = UUID.randomUUID()
            val initiator = UUID.randomUUID()
            val firstPaymentId = UUID.randomUUID()
            insertPendingBinding(connection, reservation, delegation, initiator)
            insert(
                connection,
                firstPaymentId,
                "delegation-bind-it-first",
                initiator,
                delegation,
                reservation,
            )
            bind(connection, reservation, firstPaymentId)
            connection.commit()

            assertThatThrownBy {
                insert(
                    connection,
                    UUID.randomUUID(),
                    "delegation-bind-it-second",
                    initiator,
                    delegation,
                    reservation,
                )
            }.isInstanceOf(SQLException::class.java)
            connection.rollback()
        }
    }

    @Test
    fun `all expand constraints are validated and both payment links are deferred`() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT conname, convalidated, condeferrable, condeferred
                FROM pg_constraint
                WHERE conname IN (
                    'chk_domestic_payments_delegation_binding',
                    'chk_domestic_delegated_spend_contract',
                    'chk_domestic_delegated_spend_revision',
                    'chk_domestic_delegated_spend_binding_state',
                    'uq_domestic_delegated_spend_reservation_payment',
                    'fk_domestic_delegated_spend_payment',
                    'fk_domestic_payment_delegated_spend_binding'
                )
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    val constraints = buildMap {
                        while (rows.next()) {
                            put(
                                rows.getString("conname"),
                                Triple(
                                    rows.getBoolean("convalidated"),
                                    rows.getBoolean("condeferrable"),
                                    rows.getBoolean("condeferred"),
                                ),
                            )
                        }
                    }
                    assertThat(constraints).hasSize(7)
                    assertThat(constraints.values).allSatisfy { assertThat(it.first).isTrue() }
                    assertThat(constraints.getValue("fk_domestic_delegated_spend_payment"))
                        .isEqualTo(Triple(true, true, true))
                    assertThat(constraints.getValue("fk_domestic_payment_delegated_spend_binding"))
                        .isEqualTo(Triple(true, true, true))
                }
            }
            connection.prepareStatement(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
                    "WHERE conname = 'fk_domestic_payment_delegated_spend_binding'",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).contains(
                        "FOREIGN KEY (reservation_id, payment_id)",
                        "REFERENCES domestic_delegated_spend_bindings(reservation_id, payment_id)",
                    )
                }
            }
        }
    }

    @Test
    fun `database refuses a standalone delegated payment against a PENDING binding at commit`() {
        val reservation = UUID.randomUUID()
        dataSource.connection.use { connection ->
            insertPendingBinding(connection, reservation, UUID.randomUUID(), UUID.randomUUID())
            connection.autoCommit = false
            insert(
                connection,
                UUID.randomUUID(),
                "delegation-bind-it-pending-bypass",
                UUID.randomUUID(),
                UUID.randomUUID(),
                reservation,
            )

            assertThatThrownBy { connection.commit() }.isInstanceOf(SQLException::class.java)
            connection.rollback()
        }
    }

    @Test
    fun `owner payment with null reservation bypasses the composite link by MATCH SIMPLE`() {
        val paymentId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            insertOwner(connection, paymentId, "delegation-bind-it-owner-control")
        }

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT delegation_id, reservation_id FROM domestic_payments WHERE payment_id = ?",
            ).use { statement ->
                statement.setObject(1, paymentId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject("delegation_id")).isNull()
                    assertThat(rows.getObject("reservation_id")).isNull()
                }
            }
        }
    }

    @Test
    fun `database refuses a delegated payment whose reservation projection does not exist`() {
        dataSource.connection.use { connection ->
            assertThatThrownBy {
                insert(
                    connection,
                    UUID.randomUUID(),
                    "delegation-bind-it-no-projection",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                )
            }.isInstanceOf(SQLException::class.java)
        }
    }

    private fun insertPendingBinding(
        connection: Connection,
        reservationId: UUID,
        delegationId: UUID,
        granteePartyId: UUID,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO domestic_delegated_spend_bindings (
                reservation_id, delegation_id, grantor_party_id, grantee_party_id, resource_type,
                resource_id, operation_type, amount, currency, idempotency_key_hash,
                reservation_state, reservation_version, schema_version, aggregate_type,
                source_service, source_created_at, source_settled_at, source_occurred_at,
                last_event_id, binding_state, payment_id, observed_at, bound_at, finalized_at,
                updated_at
            ) VALUES (?, ?, ?, ?, 'ACCOUNT', ?, 'DOMESTIC_PAYMENT', 10.00, 'CZK', ?, 'RESERVED',
                1, 1, 'DelegationSpendReservation', 'delegation-service', NOW(), NULL, NOW(), ?,
                'PENDING', NULL, NOW(), NULL, NULL, NOW())
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, reservationId)
            statement.setObject(2, delegationId)
            statement.setObject(3, TEST_GRANTOR_ID)
            statement.setObject(4, granteePartyId)
            statement.setObject(5, UUID.randomUUID())
            statement.setString(6, "a".repeat(64))
            statement.setObject(7, UUID.randomUUID())
            statement.executeUpdate()
        }
    }

    private fun bind(connection: Connection, reservationId: UUID, paymentId: UUID) {
        connection.prepareStatement(
            "UPDATE domestic_delegated_spend_bindings " +
                "SET binding_state = 'BOUND', payment_id = ?, bound_at = NOW(), updated_at = NOW() " +
                "WHERE reservation_id = ?",
        ).use { statement ->
            statement.setObject(1, paymentId)
            statement.setObject(2, reservationId)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
    }

    private fun insertOwner(connection: Connection, paymentId: UUID, idempotencyKey: String) {
        connection.prepareStatement(
            """
            INSERT INTO domestic_payments (
                payment_id, idempotency_key, status, debtor_account_id, debtor_account_number,
                debtor_bank_code, debtor_name, creditor_account_number, creditor_bank_code,
                creditor_name, amount, currency, priority, end_to_end_id, created_at, updated_at
            ) VALUES (?, ?, 'RECEIVED', ?, '1234567890', '0800', 'Owner binding control',
                '0987654321', '2010', 'Migration test payee', 10.00, 'CZK', 'STANDARD', ?, NOW(), NOW())
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, paymentId)
            statement.setString(2, idempotencyKey)
            statement.setObject(3, UUID.randomUUID())
            statement.setString(4, "DOM-${UUID.randomUUID()}")
            statement.executeUpdate()
        }
    }

    @Suppress("LongParameterList")
    private fun insert(
        connection: Connection,
        paymentId: UUID,
        idempotencyKey: String,
        initiatedByPartyId: UUID,
        delegationId: UUID,
        reservationId: UUID,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO domestic_payments (
                payment_id, idempotency_key, status, debtor_account_id, debtor_account_number,
                debtor_bank_code, debtor_name, creditor_account_number, creditor_bank_code,
                creditor_name, amount, currency, priority, end_to_end_id, created_at, updated_at,
                initiated_by_party_id, delegation_id, reservation_id
            ) VALUES (?, ?, 'RECEIVED', ?, '1234567890', '0800', 'Delegation binding test',
                '0987654321', '2010', 'Migration test payee', ?, 'CZK', 'STANDARD', ?, NOW(), NOW(),
                ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, paymentId)
            statement.setString(2, idempotencyKey)
            statement.setObject(3, UUID.randomUUID())
            statement.setBigDecimal(4, BigDecimal("10.00"))
            statement.setString(5, "DOM-${UUID.randomUUID()}")
            statement.setObject(6, initiatedByPartyId)
            statement.setObject(7, delegationId)
            statement.setObject(8, reservationId)
            statement.executeUpdate()
        }
    }

    private companion object {
        val TEST_GRANTOR_ID: UUID = UUID.fromString("50000000-0000-4000-8000-000000000001")
    }
}
