// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.PeriodTrialBalance
import com.openbank.ledger.domain.model.PeriodType
import com.openbank.ledger.domain.model.TrialBalanceLine
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Broker-side provider verification for ledger-service, published-result counterpart to
 * [LedgerPactProviderVerificationTest] (issue #1009).
 *
 * `_service-ci.yml`'s "Publish consumer pacts to broker" step runs unconditionally for every
 * consumer service on a main push, including billing-service's `postJournal` contract
 * (`BillingLedgerPostJournalPactConsumerTest`). But ledger-service's only provider verification
 * was git-pact (`@PactFolder`, ADR-0063 pilot for balance-service) — nothing ever pulled
 * billing-service's pact BACK OUT of the broker to verify it and publish a result, so
 * `can-i-deploy` permanently saw "no verified pact" for billing-service <-> ledger-service and
 * blocked every ledger-service deploy touching that pair (confirmed live: #945 merged clean,
 * built green, but sat undeployed on this gate).
 *
 * A second `@Provider("openbank-ledger-service")` class is safe here (unlike the collision
 * CLAUDE.md warns about): that footgun is HTTP vs MESSAGE target dispatch fighting over the same
 * `@BeforeEach`; ledger-service has no message-consumer contracts, both classes here use
 * [HttpTestTarget] exclusively, so verifying the same interaction from two pact sources is at
 * worst redundant, never colliding.
 *
 * Gated on `pactbroker.url`: skipped locally and on PR-lane CI (no broker configured there,
 * matching every other broker-based provider test in the fleet) — the git-pact class keeps
 * running unconditionally regardless, so balance-service coverage (ADR-0063's whole point:
 * zero-infra-dependency verification) is unaffected by this addition.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-ledger-service")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class LedgerPactBrokerProviderVerificationTest {

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    @Inject
    lateinit var dataSource: DataSource

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
        // The missing-identity interaction expects 401. Class-level @TestSecurity otherwise
        // authenticates every broker replay, including this one, and would produce a 200.
        if (context != null && context.interaction.providerStates.any { it.name == NEGATIVE_AUTH_STATE }) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(NEGATIVE_AUTH_STATE)
    fun stateNoValidM2mIdentity() {
        // verifyPacts clears the authenticated test identity for this interaction.
    }

    @State("ledger has frozen monthly trial balance for the reporting date")
    fun stateWithFrozenMonthlyTrialBalance() {
        val period = PeriodType.MONTH.of(LocalDate.of(2026, 6, 30))
        val lines = listOf(
            TrialBalanceLine(
                ASSET_ID,
                "1100",
                "Cash",
                GlAccountType.ASSET,
                "CZK",
                BigDecimal("150000"),
                BigDecimal.ZERO,
            ),
            TrialBalanceLine(
                LIABILITY_ID,
                "2100",
                "Deposits",
                GlAccountType.LIABILITY,
                "CZK",
                BigDecimal.ZERO,
                BigDecimal("150000"),
            ),
        )
        val balance = PeriodTrialBalance(period, lines)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into ledger_closed_period (id, period_type, period_from, period_to, status, evidence_state, computed_at, total_debits, total_credits, account_count, content_hash, drafted_by, frozen_by, frozen_at, created_at, updated_at) values (?, 'MONTH', '2026-06-01', '2026-06-30', 'FROZEN', 'LINES_V1', ?, 150000, 150000, 2, ?, 'maker', 'checker', ?, ?, ?) on conflict (period_type, period_from) do nothing",
            ).use { s ->
                s.setObject(1, PERIOD_ID)
                s.setTimestamp(2, Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")))
                s.setString(3, balance.contentHash())
                s.setTimestamp(4, Timestamp.from(Instant.parse("2026-07-02T00:00:00Z")))
                s.setTimestamp(5, Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")))
                s.setTimestamp(6, Timestamp.from(Instant.parse("2026-07-02T00:00:00Z")))
                s.executeUpdate()
            }
            connection.prepareStatement(
                "insert into ledger_closed_period_trial_balance_line (period_id, gl_account_id, currency, code, name, account_type, total_debit, total_credit) values (?, ?, 'CZK', ?, ?, ?, ?, ?) on conflict do nothing",
            ).use { s ->
                lines.forEach { l ->
                    s.setObject(1, PERIOD_ID)
                    s.setObject(2, l.glAccountId)
                    s.setString(3, l.code)
                    s.setString(4, l.name)
                    s.setString(5, l.type.name)
                    s.setBigDecimal(6, l.totalDebit)
                    s.setBigDecimal(7, l.totalCredit)
                    s.addBatch()
                }
                s.executeBatch()
            }
        }
    }

    /**
     * Same state as [LedgerPactProviderVerificationTest.stateWithSeededChartOfAccounts] — no
     * setup needed, the V3/V5 Flyway migrations seed the standard chart into the fresh
     * Testcontainer DB (billing-service's postJournal contract posts against real, enabled leaf
     * GL accounts a0000000-...-002 and a0000000-...-004003).
     */
    @State("the standard chart of accounts is seeded")
    fun stateWithSeededChartOfAccounts() {
        // No-op — see docstring.
    }

    /**
     * The broker serves EVERY consumer's pact for this provider, not just billing-service's, so
     * this class must handle every state its git-pact counterpart does: a state this class lacks
     * fails verification with MissingStateChangeMethod, the result publishes as a failure, and
     * `can-i-deploy` then blocks ledger-service deploys on a pair that is otherwise healthy —
     * which is exactly what happened to balance-service's two trial-balance interactions and kept
     * the #945 reversal fix out of the sandbox.
     *
     * Bodies mirror [LedgerPactProviderVerificationTest] verbatim (no-op by design): the pact uses
     * type matchers, so any valid trial-balance response satisfies the contract shape, and seeding
     * real double-entry data here would couple the provider test to the internal posting API — the
     * anti-pattern Pact exists to avoid. LedgerApiIT covers the seeded-data path.
     */
    @State("ledger has journal entries for the reporting date")
    fun stateWithJournalEntries() {
        // No-op — see docstring.
    }

    @State("ledger has no journal entries")
    fun stateWithNoJournalEntries() {
        // No setup needed — a fresh Testcontainer DB has no journals by default.
    }

    /**
     * Mirrors [LedgerPactProviderVerificationTest.stateWithNostroJournalLine] verbatim
     * (treasury-service's nostro-reconciliation reads, ADR-0315 D5, #10896) — the broker serves
     * every consumer's pact for this provider, so a state this class lacks fails verification with
     * MissingStateChangeMethod and blocks treasury-service deploys on can-i-deploy, the same
     * failure mode documented above for balance-service's trial-balance pact.
     */
    @State("ledger has a nostro journal line on 1001 for the statement date")
    fun stateWithNostroJournalLine() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into journal_entries (id, transaction_id, entry_date, value_date, description, status, created_by)
                   values (?, ?, ?, ?, 'MM placement settlement', 'POSTED', ?)
                   on conflict (id, entry_date) do nothing""",
            ).use { statement ->
                statement.setObject(1, NOSTRO_JOURNAL_ID)
                statement.setObject(2, NOSTRO_TRANSACTION_ID)
                statement.setObject(3, java.sql.Date.valueOf(NOSTRO_STATEMENT_DATE))
                statement.setObject(4, java.sql.Date.valueOf(NOSTRO_STATEMENT_DATE))
                statement.setObject(5, NOSTRO_SYSTEM_ACTOR_ID)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """insert into journal_lines (id, journal_id, gl_account_id, side, amount, currency_code, base_amount, base_currency, sequence)
                   values (?, ?, ?, ?, ?, 'CZK', ?, 'CZK', ?)
                   on conflict (id) do nothing""",
            ).use { statement ->
                statement.setObject(1, NOSTRO_LINE_ID)
                statement.setObject(2, NOSTRO_JOURNAL_ID)
                statement.setObject(3, NOSTRO_GL_ID)
                statement.setString(4, "C")
                statement.setBigDecimal(5, NOSTRO_STATEMENT_AMOUNT)
                statement.setBigDecimal(6, NOSTRO_STATEMENT_AMOUNT)
                statement.setInt(7, 1)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """insert into journal_lines (id, journal_id, gl_account_id, side, amount, currency_code, base_amount, base_currency, sequence)
                   values (?, ?, ?, ?, ?, 'CZK', ?, 'CZK', ?)
                   on conflict (id) do nothing""",
            ).use { statement ->
                statement.setObject(1, NOSTRO_PLACEMENT_LINE_ID)
                statement.setObject(2, NOSTRO_JOURNAL_ID)
                statement.setObject(3, NOSTRO_PLACEMENT_GL_ID)
                statement.setString(4, "D")
                statement.setBigDecimal(5, NOSTRO_STATEMENT_AMOUNT)
                statement.setBigDecimal(6, NOSTRO_STATEMENT_AMOUNT)
                statement.setInt(7, 2)
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        val PERIOD_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000009601")
        val ASSET_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000009602")
        val LIABILITY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000009603")

        const val NOSTRO_STATEMENT_DATE = "2026-03-15"
        val NOSTRO_STATEMENT_AMOUNT: BigDecimal = BigDecimal("250000.00")
        val NOSTRO_JOURNAL_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000010896")
        val NOSTRO_TRANSACTION_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000010897")
        val NOSTRO_LINE_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000010898")
        val NOSTRO_PLACEMENT_LINE_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000010899")

        // TreasuryChart.glAccountId("1001") / ("1500") — seeded by V29.
        val NOSTRO_GL_ID: UUID = UUID.fromString("a0000000-0000-0000-0000-000000001001")
        val NOSTRO_PLACEMENT_GL_ID: UUID = UUID.fromString("a0000000-0000-0000-0000-000000001500")
        val NOSTRO_SYSTEM_ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
    }
}
