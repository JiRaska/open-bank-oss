// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.contract

import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Provider states for the customer-edge contract, shared by the folder and the broker
 * verification classes. PUBLIC on purpose: those classes compile in the separate
 * `providerPactTest` source set, which reaches this one through the test output, and Kotlin
 * `internal` does not cross that module boundary.
 *
 * Each state RESETS its holding rather than assuming a fresh database, because an earlier
 * interaction in the same run (withdraw, revalue) changes the very row the next one reads.
 *
 * Rows are written with JDBC and explicit ids from a range Hibernate never reaches. The entities
 * draw ids from pooled sequences in blocks of 50, so an id taken from the table's own serial
 * default could collide with one the running service allocates later in the same run.
 */
object WealthPactStates {
    const val ACTIVE_STATE = "the customer party holds an active declared holding"
    const val PLEDGED_STATE = "the customer party holds a declared holding pledged as lending collateral"
    const val NO_HOLDING_STATE = "the customer party holds no declared holding"

    /** The fleet-wide state name for a consumer's missing-identity interaction (ADR-0279 #3). */
    const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

    /** Every state except [NEGATIVE_AUTH_STATE]: the authenticated twins replay exactly these. */
    const val AUTHENTICATED_STATES = "^(?!$NEGATIVE_AUTH_STATE\$).*\$"

    private val PARTY_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val HOLDING_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val PLEDGED_HOLDING_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val LOAN_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private const val ROW_ID = 900_000_001L
    private const val PLEDGED_ROW_ID = 900_000_002L
    private const val VALUATION_ROW_ID = 900_000_101L
    private const val PLEDGED_VALUATION_ROW_ID = 900_000_102L

    fun activeHolding(dataSource: DataSource) = reset(dataSource, ROW_ID, HOLDING_ID, VALUATION_ROW_ID, "ACTIVE", null)

    /**
     * Removes every holding the pact party owns, so a declare starts clean and the unknown id
     * cannot have been created by an earlier interaction.
     */
    fun noHolding(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "DELETE FROM declared_holding_valuations WHERE holding_id IN " +
                    "(SELECT holding_id FROM declared_holdings WHERE owner_party_id = ?)",
            ).use { s ->
                s.setObject(1, PARTY_ID)
                s.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM declared_holdings WHERE owner_party_id = ?").use { s ->
                s.setObject(1, PARTY_ID)
                s.executeUpdate()
            }
            connection.commit()
        }
    }

    fun pledgedHolding(dataSource: DataSource) =
        reset(dataSource, PLEDGED_ROW_ID, PLEDGED_HOLDING_ID, PLEDGED_VALUATION_ROW_ID, "PLEDGED", LOAN_ID)

    private fun reset(
        dataSource: DataSource,
        rowId: Long,
        holdingId: UUID,
        valuationRowId: Long,
        status: String,
        loanId: UUID?,
    ) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.delete("DELETE FROM declared_holding_valuations WHERE holding_id = ?", holdingId)
            connection.delete("DELETE FROM declared_holdings WHERE holding_id = ?", holdingId)
            val now = Timestamp.from(Instant.now())
            connection.prepareStatement(INSERT_HOLDING).use { s ->
                s.setLong(1, rowId)
                s.setObject(2, holdingId)
                s.setObject(3, PARTY_ID)
                s.setDate(4, Date.valueOf(VALUED_AT))
                s.setString(5, status)
                s.setObject(6, loanId)
                s.setTimestamp(7, now)
                s.setTimestamp(8, now)
                s.executeUpdate()
            }
            connection.prepareStatement(INSERT_VALUATION).use { s ->
                s.setLong(1, valuationRowId)
                s.setObject(2, holdingId)
                s.setDate(3, Date.valueOf(VALUED_AT))
                s.setTimestamp(4, now)
                s.executeUpdate()
            }
            connection.commit()
        }
    }

    private fun Connection.delete(sql: String, holdingId: UUID) = prepareStatement(sql).use { s ->
        s.setObject(1, holdingId)
        s.executeUpdate()
    }

    private val VALUED_AT: LocalDate = LocalDate.of(2026, 1, 15)

    private const val INSERT_HOLDING = """
        INSERT INTO declared_holdings (id, holding_id, owner_party_id, holding_type, label, valuation_amount,
            valuation_currency, valued_at, valuation_source, ownership_share, document_ids, status,
            pledged_to_loan_id, created_at, updated_at)
        VALUES (?, ?, ?, 'REAL_ESTATE', 'Flat in Brno', 6500000, 'CZK', ?, 'CUSTOMER_DECLARED', 0.5, '[]', ?, ?, ?, ?)
    """

    private const val INSERT_VALUATION = """
        INSERT INTO declared_holding_valuations (id, holding_id, valuation_amount, valuation_currency, valued_at,
            valuation_source, recorded_at)
        VALUES (?, ?, 6500000, 'CZK', ?, 'CUSTOMER_DECLARED', ?)
    """
}
