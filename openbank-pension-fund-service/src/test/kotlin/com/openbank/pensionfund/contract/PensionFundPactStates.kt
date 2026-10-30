// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.contract

import org.eclipse.microprofile.config.ConfigProvider
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** The state name every consumer of openbank-pension-fund-service uses for its missing-identity interaction. */
const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

/** pension-service's priced state: a published NAV, a holding, a priced transaction, a pending order, a strategy. */
const val PRICED_STATE = "a pension contract holds units priced at a published NAV"

/** pension-service's unpriced state: a holding in a fund that has never published a NAV. */
const val UNPUBLISHED_STATE = "a pension contract holds units in a fund with no published NAV"

/**
 * Seeds the provider states pension-service's consumer pact names, by plain JDBC against the test
 * Postgres. Not through the API: publishing a NAV is four-eyes (two DIFFERENT principals), and a
 * Pact replay carries exactly one `@TestSecurity` identity. Not through the reactive store either:
 * a state-change callback has no Vert.x context (the HR000068 trap). Every insert is
 * `ON CONFLICT DO NOTHING`, so a re-run against a warm container, and the several interactions
 * sharing one state, see the same rows.
 *
 * Ids are the literals in pension-service's `PensionFundPactConsumerTest`. Public: the
 * `providerPactTest` source set compiles the verification classes as a separate Kotlin module.
 */
object PensionFundPactStates {
    const val PRICED_CONTRACT = "7a1e0000-0000-4000-8000-0000000000c1"
    const val UNPUBLISHED_CONTRACT = "7a1e0000-0000-4000-8000-0000000000c2"
    const val PRICED_FUND = "7a1e0000-0000-4000-8000-0000000000f1"
    const val UNPUBLISHED_FUND = "7a1e0000-0000-4000-8000-0000000000f2"
    private const val NAV = "7a1e0000-0000-4000-8000-0000000000a1"
    private const val SEED_ORDER = "7a1e0000-0000-4000-8000-0000000000d1"
    private const val SEED_TX = "7a1e0000-0000-4000-8000-0000000000e1"
    private const val STRATEGY = "7a1e0000-0000-4000-8000-0000000000b1"

    fun pricedHoldings(): Unit = jdbc { c ->
        fund(c, PRICED_FUND, "CZ000PACT0F1")
        c.exec(
            """
            INSERT INTO fund_navs (id, fund_id, valuation_date, gross_assets, accrued_management_fee,
                other_liabilities, net_assets, units_outstanding, nav_per_unit, status, calculated_by,
                calculated_at, approved_by, published_at)
            VALUES ('$NAV', '$PRICED_FUND', DATE '2026-10-01', 125.00, 0, 0, 125.00, 100, 1.250000,
                'PUBLISHED', 'pact-maker', now(), 'pact-checker', now())
            ON CONFLICT DO NOTHING
            """,
        )
        holding(c, PRICED_CONTRACT, PRICED_FUND, "100")
        c.exec(
            """
            INSERT INTO unit_orders (id, contract_id, fund_id, order_type, amount, status, placed_at, idempotency_key)
            VALUES ('$SEED_ORDER', '$PRICED_CONTRACT', '$PRICED_FUND', 'SUBSCRIBE', 500.00, 'PENDING', now(),
                'pact-seed-pending')
            ON CONFLICT DO NOTHING
            """,
        )
        c.exec(
            """
            INSERT INTO unit_transactions (id, contract_id, fund_id, transaction_type, units, amount, nav_id,
                nav_per_unit, priced_at)
            VALUES ('$SEED_TX', '$PRICED_CONTRACT', '$PRICED_FUND', 'SUBSCRIBE', 100, 125.00, '$NAV', 1.250000, now())
            ON CONFLICT DO NOTHING
            """,
        )
        c.exec(
            """
            INSERT INTO fund_strategies (id, name, allocations, status, version, created_at, updated_at)
            VALUES ('$STRATEGY', 'BALANCED',
                '[{"fundId":"$PRICED_FUND","weight":1,"lowerBand":0,"upperBand":1}]', 'ACTIVE', 1, now(), now())
            ON CONFLICT DO NOTHING
            """,
        )
    }

    fun unpublishedNavHoldings(): Unit = jdbc { c ->
        fund(c, UNPUBLISHED_FUND, "CZ000PACT0F2")
        holding(c, UNPUBLISHED_CONTRACT, UNPUBLISHED_FUND, "40")
    }

    private fun fund(c: Connection, id: String, isin: String) = c.exec(
        """
        INSERT INTO funds (id, name, isin, lei, depositary_reference, custody_account_reference, currency,
            risk_class, mandatory_conservative, management_fee_rate, launch_nav_per_unit, status, created_at, updated_at)
        VALUES ('$id', 'Pact fund $isin', '$isin', '315700PACT0000000001', 'DEP-PACT', 'CUST-$isin', 'CZK',
            3, false, 0.008, 1, 'ACTIVE', now(), now())
        ON CONFLICT DO NOTHING
        """,
    )

    private fun holding(c: Connection, contract: String, fund: String, units: String) = c.exec(
        """
        INSERT INTO unit_holdings (id, contract_id, fund_id, units)
        VALUES ('${holdingId(contract, fund)}', '$contract', '$fund', $units)
        ON CONFLICT (contract_id, fund_id) DO NOTHING
        """,
    )

    /**
     * The store keys a holding by a name-based UUID (`PanachePensionFundStore.holdingId`, private);
     * a random id is invisible to its `holding()` lookup and every REDEEM then sees 0 units.
     */
    private fun holdingId(contract: String, fund: String) =
        UUID.nameUUIDFromBytes("holding:$contract:$fund".toByteArray(Charsets.UTF_8))

    private fun Connection.exec(sql: String) {
        createStatement().use { it.executeUpdate(sql.trimIndent()) }
    }

    private fun <T> jdbc(block: (Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }
}
