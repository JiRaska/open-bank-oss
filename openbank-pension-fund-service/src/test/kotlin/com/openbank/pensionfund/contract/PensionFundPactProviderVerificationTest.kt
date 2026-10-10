// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.
package com.openbank.pensionfund.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager

/** Real HTTP and PostgreSQL contract proof; source OPA tests separately prove principal grants. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@Provider("openbank-pension-fund-service")
@PactFolder("../pacts")
@PactFilter("^(?!no valid M2M identity is presented$).*")
@TestSecurity(user = "service-account-openbank-pension", roles = ["ROLE_API"])
class PensionFundPactProviderVerificationTest {
    @BeforeEach
    fun target(context: PactVerificationContext) {
        val port = ConfigProvider.getConfig().getValue("quarkus.http.test-port", Int::class.java)
        context.target = HttpTestTarget("localhost", port)
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verify(context: PactVerificationContext) {
        context.verifyInteraction()
    }

    @State("pension fund has an active fund and strategy for contract")
    fun seed() {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM unit_orders WHERE contract_id = '$CONTRACT'")
                statement.executeUpdate(
                    """INSERT INTO funds (id, name, isin, lei, depositary_reference, custody_account_reference,
                        currency, risk_class, mandatory_conservative, management_fee_rate, launch_nav_per_unit,
                        status, created_at, updated_at)
                        VALUES ('$FUND', 'Pact fund', 'CZ0000000001', '315700ABCDEF12345678', 'PACT', 'PACT',
                        'CZK', 3, false, 0.008, 1, 'ACTIVE', now(), now()) ON CONFLICT (id) DO NOTHING""",
                )
                statement.executeUpdate(
                    """INSERT INTO unit_holdings (id, contract_id, fund_id, units, version)
                        VALUES ('10000000-0000-4000-8000-000000000004', '$CONTRACT', '$FUND', 1000, 0)
                        ON CONFLICT (contract_id, fund_id) DO UPDATE SET units = 1000""",
                )
                statement.executeUpdate(
                    """INSERT INTO fund_navs (id, fund_id, valuation_date, gross_assets, accrued_management_fee,
                        other_liabilities, net_assets, units_outstanding, nav_per_unit, status,
                        calculated_by, calculated_at, approved_by, published_at)
                        VALUES ('10000000-0000-4000-8000-000000000005', '$FUND', '2000-06-30',
                        1000, 0, 0, 1000, 1000, 1, 'PUBLISHED', 'pact-maker', now(), 'pact-checker', now())
                        ON CONFLICT (id) DO NOTHING""",
                )
                statement.executeUpdate(
                    """INSERT INTO fund_strategies (id, name, allocations, glide_path, status, version,
                        created_at, updated_at) VALUES ('$STRATEGY', 'Pact strategy',
                        '[{"fundId":"$FUND","weight":1,"lowerBand":0,"upperBand":1}]',
                        '[]', 'ACTIVE', 1, now(), now()) ON CONFLICT (id) DO NOTHING""",
                )
            }
            connection.commit()
        }
    }

    private companion object {
        const val FUND = "10000000-0000-4000-8000-000000000001"
        const val CONTRACT = "10000000-0000-4000-8000-000000000002"
        const val STRATEGY = "10000000-0000-4000-8000-000000000003"
    }
}
