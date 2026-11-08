// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "pension_fund_accounting_it")],
)
@TestProfile(PensionFundAccountingIT.FundProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestSecurity(user = "service-account-openbank-pension-fund", roles = ["ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "pension-fund-test-subject"),
        Claim(key = "azp", value = "openbank-pension-fund"),
        Claim(key = "preferred_username", value = "service-account-openbank-pension-fund"),
    ],
)
class PensionFundAccountingIT {
    class FundProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = PensionAccountingFixtures.profile(
            "PENSION_FUND",
            PensionAccountingFixtures.FUND,
            listOf("pension-fund", "pension-cash", "pension-settlement", "pension-custody"),
        ) + ("quarkus.flyway.locations" to "db/migration,db/pension-fund")
    }

    @Inject
    lateinit var clock: AccountingClock

    @BeforeEach
    fun resetOwnBook(info: TestInfo) {
        if (info.testMethod.orElseThrow().getAnnotation(Order::class.java)?.value == CHECKER_ORDER) return
        PensionAccountingFixtures.resetChart("PF", "5110", "2110", "EXPENSE", "LIABILITY")
    }

    @Test
    @Order(1)
    fun `fee replay is idempotent and changed payload or destination is refused`() {
        val nav = UUID.randomUUID()
        val first = fee(nav).statusCode(200).body("outcome", equalTo("POSTED"))
            .body("synthetic", equalTo(false)).extract().path<String>("journalId")
        fee(nav).statusCode(200).body("journalId", equalTo(first))
        assertThat(PensionAccountingFixtures.scalar("SELECT count(*) FROM journal_entries")).isEqualTo("1")
        fee(nav, "11.00").statusCode(409)
        PensionAccountingFixtures.fee(
            PensionAccountingFixtures.COMPANY,
            PensionAccountingFixtures.FUND,
            UUID.randomUUID(),
            clock.today(),
        ).statusCode(409)
        PensionAccountingFixtures.fee(
            PensionAccountingFixtures.FUND,
            PensionAccountingFixtures.COMPANY,
            UUID.randomUUID(),
            clock.today(),
        ).statusCode(409)
        assertThat(PensionAccountingFixtures.net("PF5110")).isEqualByComparingTo("10.00")
        assertThat(PensionAccountingFixtures.net("PF2110")).isEqualByComparingTo("-10.00")
    }

    @Test
    @Order(2)
    fun `a correction changes only the fee delta and needs a completed original`() {
        val original = UUID.randomUUID()
        fee(original).statusCode(200)
        fee(UUID.randomUUID(), "-2.00", original).statusCode(200).body("outcome", equalTo("POSTED"))
        assertThat(PensionAccountingFixtures.net("PF5110")).isEqualByComparingTo("8.00")
        assertThat(PensionAccountingFixtures.net("PF2110")).isEqualByComparingTo("-8.00")
        fee(UUID.randomUUID(), "-1.00", UUID.randomUUID()).statusCode(409)
        assertThat(PensionAccountingFixtures.scalar("SELECT count(*) FROM journal_entries")).isEqualTo("2")
    }

    @Test
    @Order(3)
    fun `zero accrual is retained without a fictitious journal`() {
        val nav = UUID.randomUUID()
        fee(nav, "0.00").statusCode(200).body("outcome", equalTo("NO_POSTING"))
            .body("journalId", equalTo(null))
        fee(nav, "0.00").statusCode(200).body("outcome", equalTo("NO_POSTING"))
        assertThat(PensionAccountingFixtures.scalar("SELECT count(*) FROM journal_entries")).isEqualTo("0")
        assertThat(PensionAccountingFixtures.scalar("SELECT count(*) FROM ledger_pension_fee_receipts"))
            .isEqualTo("1")
    }

    @Test
    @Order(4)
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "spoofed-pension-source"),
            Claim(key = "azp", value = "unrelated-client"),
            Claim(key = "preferred_username", value = "service-account-openbank-pension-fund"),
        ],
    )
    fun `a matching username from another client cannot post`() {
        fee(UUID.randomUUID()).statusCode(403)
        assertThat(PensionAccountingFixtures.scalar("SELECT count(*) FROM journal_entries")).isEqualTo("0")
    }

    @Test
    @Order(5)
    fun `synthetic accounting is acknowledged and excluded from the real trial balance`() {
        val response = PensionAccountingFixtures.fee(
            PensionAccountingFixtures.FUND,
            PensionAccountingFixtures.FUND,
            UUID.randomUUID(),
            clock.today(),
            synthetic = true,
        ).statusCode(200).body("synthetic", equalTo(true)).extract().path<String>("journalId")
        assertThat(PensionAccountingFixtures.scalar("SELECT count(*) FROM journal_entries WHERE synthetic"))
            .isEqualTo("1")
        assertThat(response).isNotBlank()
        val real = given().get("/api/v1/journals/trial-balance").then().statusCode(200)
            .body("scope", equalTo("REAL_ONLY")).extract().jsonPath()
        val synthetic = given().queryParam("scope", "SYNTHETIC_ONLY").get("/api/v1/journals/trial-balance")
            .then().statusCode(200).extract().jsonPath()
        assertThat(BigDecimal(real.getString("totalDebit"))).isZero()
        assertThat(BigDecimal(synthetic.getString("totalDebit"))).isEqualByComparingTo("10.00")
    }

    @Test
    @Order(MAKER_ORDER)
    @TestSecurity(user = "pension-reconciliation-maker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "pension-maker-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "pension-reconciliation-maker"),
        ],
    )
    fun `maker proposes complete independently sourced balances but cannot approve them`() {
        proposal = given().contentType("application/json").body(
            PensionAccountingFixtures.proposal(
                PensionAccountingFixtures.FUND,
                clock.today(),
                listOf("cash", "units", "custody", "nav", "fees", "settlements"),
                listOf("PF5110", "PF2110"),
            ),
        ).post(RECONCILIATIONS).then().statusCode(200).extract().path("id")
        given().post("$RECONCILIATIONS/$proposal/approve").then().statusCode(409)
        assertThat(PensionAccountingFixtures.scalar("SELECT count(*) FROM ledger_tieout_runs")).isEqualTo("0")
    }

    @Test
    @Order(CHECKER_ORDER)
    @TestSecurity(user = "pension-reconciliation-checker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "pension-checker-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "pension-reconciliation-checker"),
        ],
    )
    fun `independent checker records a fund control with immutable read proof once`() {
        given().post("$RECONCILIATIONS/$proposal/approve").then().statusCode(200)
        assertThat(PensionAccountingFixtures.scalar("SELECT control_kind FROM ledger_tieout_runs"))
            .isEqualTo("PENSION_FUND")
        assertThat(
            PensionAccountingFixtures.scalar(
                "SELECT count(*) FROM ledger_tieout_runs WHERE started_at IS NOT NULL AND journal_count = 0",
            ),
        ).isEqualTo("1")
        given().post("$RECONCILIATIONS/$proposal/approve").then().statusCode(409)
    }

    private fun fee(nav: UUID, amount: String = "10.00", original: UUID? = null): ValidatableResponse =
        PensionAccountingFixtures.fee(
            PensionAccountingFixtures.FUND,
            PensionAccountingFixtures.FUND,
            nav,
            clock.today(),
            amount,
            original,
        )

    private companion object {
        const val RECONCILIATIONS = "/api/v1/ledger/pension-accounting/reconciliations"
        const val MAKER_ORDER = 100
        const val CHECKER_ORDER = 101
        lateinit var proposal: String
    }
}

/** HTTP bodies and plain JDBC fixture setup; no accounting resource is called directly. */
internal object PensionAccountingFixtures {
    const val FUND = "00000000-0000-0000-0000-000000000101"
    const val COMPANY = "00000000-0000-0000-0000-000000000201"

    fun profile(kind: String, bookId: String, participants: List<String>): Map<String, String> = buildMap {
        put("quarkus.scheduler.enabled", "false")
        put("authz.enforce", "false")
        put("openbank.ledger.day-lock.mode", "off")
        put("openbank.ledger.book.kind", kind)
        put("openbank.ledger.book.id", bookId)
        put("openbank.ledger.book.close.participants-mode", "shadow")
        put("openbank.synthetic.trusted-principals", "service-account-openbank-pension-fund")
        participants.forEach {
            put("openbank.ledger.book.close.participants.$it.principal", "service-account-openbank-pension-fund")
            put(
                "openbank.ledger.book.close.required-jobs.$it",
                if (it == "pension-fund") {
                    if (kind == "PENSION_FUND") "nav,management-fees" else "management-fees"
                } else {
                    "accounting"
                },
            )
        }
    }

    fun resetChart(prefix: String, debit: String, credit: String, debitType: String, creditType: String) {
        sql(
            "TRUNCATE ledger_pension_fee_receipts, ledger_pension_reconciliations, ledger_book_identity, " +
                "ledger_close_evidence, ledger_close_participation, ledger_accounting_day, ledger_tieout_runs, " +
                "ledger_outbox, ledger_idempotency, journal_lines, journal_entries CASCADE",
        )
        sql("UPDATE gl_accounts SET is_enabled = false")
        for ((suffix, type) in listOf(debit to debitType, credit to creditType)) {
            sql(
                "INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) " +
                    "VALUES (gen_random_uuid(), '$prefix$suffix', 'Pension accounting test', " +
                    "'$type', 'CZK', true, true) " +
                    "ON CONFLICT (code) DO UPDATE SET is_enabled = true",
            )
        }
        val classes = if (prefix == "PF") {
            listOf("PENSION_MANAGEMENT_FEE_EXPENSE" to "PRINCIPAL", "PENSION_MANAGEMENT_FEE_PAYABLE" to "PRINCIPAL")
        } else {
            listOf("PENSION_MANAGEMENT_FEE_RECEIVABLE" to "PRINCIPAL", "PENSION_MANAGEMENT_FEE_INCOME" to "FEE_INCOME")
        }
        for ((mapping, suffix) in classes.zip(listOf(debit, credit))) {
            sql(
                "INSERT INTO accounting_class_gl_map (id, accounting_class, role, currency_code, gl_account_id) " +
                    "SELECT gen_random_uuid(), '${mapping.first}', '${mapping.second}', 'CZK', id FROM gl_accounts " +
                    "WHERE code = '$prefix$suffix' ON CONFLICT (accounting_class, role, currency_code) " +
                    "DO UPDATE SET gl_account_id = EXCLUDED.gl_account_id",
            )
        }
    }

    fun fee(
        bookId: String,
        fundId: String,
        navId: UUID,
        date: LocalDate,
        amount: String = "10.00",
        original: UUID? = null,
        synthetic: Boolean = false,
    ): ValidatableResponse {
        val request = given().contentType("application/json")
        if (synthetic) request.header("x-openbank-synthetic", "true")
        return request.body(
            mapOf(
                "bookId" to bookId,
                "fundId" to fundId,
                "navId" to navId,
                "valuationDate" to date.toString(),
                "amount" to amount,
                "currency" to "CZK",
                "correctsNavId" to original,
            ),
        ).post("/api/v1/ledger/pension-accounting/management-fees").then()
    }

    fun proposal(bookId: String, date: LocalDate, sources: List<String>, accounts: List<String>): Map<String, Any> =
        mapOf(
            "bookId" to bookId,
            "businessDate" to date.toString(),
            "sourceReferences" to sources.associateWith { "independent-$it-evidence" },
            "expectedBalances" to accounts.associateWith { "0.00" },
        )

    fun net(code: String): BigDecimal = BigDecimal(
        scalar(
            "SELECT COALESCE(sum(CASE WHEN l.side = 'D' THEN l.base_amount ELSE -l.base_amount END), 0) " +
                "FROM journal_lines l JOIN gl_accounts g ON g.id = l.gl_account_id WHERE g.code = '$code'",
        ),
    )

    fun scalar(query: String): String = connection().use { c ->
        c.createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                assertThat(rows.next()).isTrue()
                rows.getString(1)
            }
        }
    }

    private fun sql(query: String) {
        connection().use { c -> c.createStatement().use { it.execute(query) } }
    }

    private fun connection(): java.sql.Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }
}
