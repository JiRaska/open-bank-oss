// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager

/**
 * Provider replay of the risk engine's loan-book pact (ADR-0314 D4) — lending's FIRST provider
 * contract. Reads `pacts/` (`@PactFolder`, git-pact, ADR-0063) and replays every interaction whose
 * provider is `openbank-lending-service` against the running Quarkus test instance and a real
 * Postgres. Always runs on a PR: no broker, no gate. This is the half that can catch a wrong PATH —
 * the consumer's mock answers any path it is asked for (CLAUDE.md, Contract tests).
 *
 * Authentication: the endpoint admits ROLE_API (the risk engine's M2M token), so Pact replays as
 * that role; OPA is not enforced in tests, the rego rule is held by `lending_rest_ext_test.rego`.
 *
 * This is the git-pact half of the sanctioned pair; [LendingLoanBookPactBrokerProviderTest] is the
 * broker half (main-push only), which publishes the verification `can-i-deploy` reads. Both use
 * [LoanBookPactState], so an added `@State` cannot land in one and not the other.
 */
@QuarkusTest
@QuarkusTestResource(LendingLoanBookPactProviderTest.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
@Provider("openbank-lending-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class LendingLoanBookPactProviderTest {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

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

    @State(LoanBookPactState.NAME)
    fun oneFixedLoan() = LoanBookPactState.seed()
}

/**
 * The provider state both loan-book verification classes share ([LendingLoanBookPactProviderTest]
 * and [LendingLoanBookPactBrokerProviderTest]) — one seed, so the broker replay cannot drift from
 * the folder replay.
 *
 * Plain JDBC: the reactive repositories cannot be driven from a JUnit callback without a Vert.x
 * context (HR000068). The loan is disbursed in 2020, the pact's as-of year, so it is the whole
 * book at that date regardless of what other tests in the JVM booked today. The application is
 * DISBURSED, a canonical origination state: the Testcontainer is shared by every test class in
 * the JVM, and the column's legacy default, PROPOSED, is a value the domain enum cannot read,
 * which 500s any test that lists applications. Idempotent: a re-run
 * against a warm container finds the rows and changes nothing.
 */
object LoanBookPactState {
    const val NAME = "the loan book holds one active FIXED CZK loan with two remaining installments"

    private const val APP = "0190a4c0-0000-7000-8000-0000000a0001"
    private const val LOAN = "0190a4c0-0000-7000-8000-0000000b0001"
    private const val PARTY = "0190a4c0-0000-7000-8000-0000000c0001"

    fun seed() {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            c.createStatement().use { s ->
                statements().forEach(s::addBatch)
                s.executeBatch()
            }
        }
    }

    private fun statements() = listOf(
        "INSERT INTO loan_application (id, party_id, requested_amount, currency, nominal_annual_rate, " +
            "term_periods, first_due_date, status, proposed_by) VALUES ('$APP', '$PARTY', 3000.00, 'CZK', " +
            "0.060000, 3, DATE '2020-06-15', 'DISBURSED', 'pact-seed') ON CONFLICT (id) DO NOTHING",
        "INSERT INTO loan (id, application_id, party_id, principal, currency, nominal_annual_rate, term_periods, " +
            "method, first_due_date, disbursed_at) VALUES ('$LOAN', '$APP', '$PARTY', 3000.00, 'CZK', " +
            "0.060000, 3, 'ANNUITY', DATE '2020-06-15', TIMESTAMPTZ '2020-05-15 09:00:00+00') " +
            "ON CONFLICT (id) DO NOTHING",
        installment(1, "2020-06-15", "990.05", "15.00", "3000.00", "2009.95", paidAt = "2020-06-15 09:00:00+00"),
        installment(2, "2020-07-15", "995.00", "10.05", "2009.95", "1014.95"),
        installment(3, "2020-08-15", "1014.95", "5.07", "1014.95", "0.00"),
    )

    @Suppress("LongParameterList")
    private fun installment(
        n: Int,
        due: String,
        principal: String,
        interest: String,
        opening: String,
        closing: String,
        paidAt: String? = null,
    ): String = "INSERT INTO installment (loan_id, number, due_date, currency, opening_balance, principal, " +
        "interest, payment, closing_balance, paid, paid_at) VALUES ('$LOAN', $n, DATE '$due', 'CZK', $opening, " +
        "$principal, $interest, $principal + $interest, $closing, ${paidAt != null}, " +
        "${paidAt?.let { "TIMESTAMPTZ '$it'" } ?: "NULL"}) ON CONFLICT (loan_id, number) DO NOTHING"
}
