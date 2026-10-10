// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.pension.application.port.out.FundHolding
import com.openbank.pension.application.port.out.FundHoldings
import com.openbank.pension.application.port.out.FundUnitTransaction
import com.openbank.pension.infrastructure.fund.InMemoryFundAdministrationAdapter
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Provider-side replay of customer-edge's consumer pact against pension-service (ADR-0334 S8,
 * ADR-0063 git-pact). Reads `pacts/` at the monorepo root (`@PactFolder("../pacts")`) and replays
 * every interaction whose provider is `openbank-pension-service` against the running Quarkus test
 * instance and a real Postgres — always, with no broker, so a wrong request path in the edge's
 * client is red at PR time (a consumer pact alone cannot catch that).
 *
 * The pact is produced by customer-edge's `CustomerEdgePensionPactConsumerTest` and lands in the
 * same PR as this class (ADR-0334 S6, #12359): `check-pact-provider-replay.py` refuses a provider
 * class no committed pact names, and a committed pact no always-running class replays.
 *
 * Callers are authenticated as the edge relay (`ContractAccessGuard` trusts the party header only
 * from it; the test profile names it `edge`). The missing-identity (401) interaction is replayed by
 * [PensionNegativeAuthProviderVerificationTest], which boots without `@TestSecurity`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "edge", roles = ["ROLE_API"])
@Provider("openbank-pension-service")
@PactFolder("../pacts")
@PactFilter("^(?!" + NEGATIVE_AUTH_STATE + "\$).*\$")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class PensionPactProviderVerificationTest {
    @Inject
    lateinit var register: InMemoryFundAdministrationAdapter

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /** Reset before EVERY interaction: the suspend interaction leaves it SUSPENDED otherwise. */
    @State("the customer party holds an active pension contract")
    fun activeContract() {
        PactContractSeed.reset()
        PactContractSeed.insertActive()
    }

    @State("the customer party holds no pension contract")
    fun noContract() {
        PactContractSeed.reset()
    }

    /** The overview's valuation and the transactions page read the in-memory unit register (%test). */
    @State(PactContractSeed.UNITS_STATE)
    fun contractWithUnits() {
        PactContractSeed.reset()
        PactContractSeed.insertActive()
        PactContractSeed.seedUnits(register)
    }
}

/**
 * Seeds the pact's literal ids with plain JDBC. Row ids come from far above anything the app's
 * pooled sequences allocate (the S3/S8 IT idiom), so a seeded row can never collide with one the
 * app inserts later.
 */
internal object PactContractSeed {
    val CONTRACT: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
    val PARTY: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    const val UNITS_STATE = "the customer party's active pension contract holds priced fund units"
    private val FUND: UUID = UUID.fromString("66666666-6666-4666-8666-666666666666")

    /** One priced holding and one priced subscription, no pending order: status VALUED. */
    fun seedUnits(register: InMemoryFundAdministrationAdapter) {
        val holding = FundHolding(
            FUND,
            BigDecimal("100"),
            BigDecimal("1.25"),
            LocalDate.parse("2026-10-01"),
            BigDecimal("125.00"),
            "CZK",
        )
        val subscription = FundUnitTransaction(
            UUID.randomUUID(),
            FUND,
            "SUBSCRIBE",
            BigDecimal("100"),
            BigDecimal("125.00"),
            BigDecimal("1.25"),
            UUID.randomUUID(),
            Instant.parse("2026-10-01T16:00:00Z"),
        )
        register.setHoldings(CONTRACT, FundHoldings(listOf(holding), emptyList()), listOf(subscription))
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        return DriverManager.getConnection(url, "openbank", "openbank_secret").use(block)
    }

    fun reset() = jdbc { conn ->
        conn.prepareStatement("delete from pension_strategy_elections where contract_id = ?").use {
            it.setObject(1, CONTRACT)
            it.executeUpdate()
        }
        conn.prepareStatement("delete from pension_contracts where contract_id = ?").use {
            it.setObject(1, CONTRACT)
            it.executeUpdate()
        }
    }

    fun insertActive() = jdbc { conn ->
        conn.prepareStatement(
            "insert into pension_contracts (id, contract_id, participant_party_id, product_line, jurisdiction, " +
                "pack_version, provider_entity_id, provider_type, participant_birth_date, status, start_date, " +
                "contribution_amount, contribution_currency, contribution_frequency, created_at, updated_at) " +
                "values (1000000000000 + (random() * 1000000000)::bigint, ?, ?, 'DPS', 'CZ', 1, ?, " +
                "'PENSION_COMPANY', date '1985-05-05', 'ACTIVE', current_date, 1000, 'CZK', 'MONTHLY', now(), now())",
        ).use {
            it.setObject(1, CONTRACT)
            it.setObject(2, PARTY)
            it.setObject(3, UUID.randomUUID())
            it.executeUpdate()
        }
        conn.prepareStatement(
            "insert into pension_strategy_elections (id, contract_id, strategy_code, effective_from, elected_at) " +
                "values (1000000000000 + (random() * 1000000000)::bigint, ?, 'BALANCED', current_date, now())",
        ).use {
            it.setObject(1, CONTRACT)
            it.executeUpdate()
        }
    }
}

/** The state name every consumer of pension-service uses for its missing-identity interaction. */
const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
