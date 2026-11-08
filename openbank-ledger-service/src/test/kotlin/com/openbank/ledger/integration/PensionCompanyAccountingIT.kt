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
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    restrictToAnnotatedClass = true,
    initArgs = [ResourceArg(name = "db", value = "pension_company_accounting_it")],
)
@TestProfile(PensionCompanyAccountingIT.CompanyProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestSecurity(user = "service-account-openbank-pension-fund", roles = ["ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "pension-fund-test-subject"),
        Claim(key = "azp", value = "openbank-pension-fund"),
        Claim(key = "preferred_username", value = "service-account-openbank-pension-fund"),
    ],
)
class PensionCompanyAccountingIT {
    class CompanyProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = PensionAccountingFixtures.profile(
            "PENSION_COMPANY",
            PensionAccountingFixtures.COMPANY,
            listOf("pension-fund", "pension-company-cash", "pension-company-operations"),
        ) + ("quarkus.flyway.locations" to "db/migration")
    }

    @Inject
    lateinit var clock: AccountingClock

    @BeforeEach
    fun resetOwnBook(info: TestInfo) {
        if (info.testMethod.orElseThrow().getAnnotation(Order::class.java)?.value == CHECKER_ORDER) return
        PensionAccountingFixtures.resetChart("PS", "1310", "6110", "ASSET", "INCOME")
    }

    @Test
    @Order(1)
    fun `company books the receivable and income once and preserves correction economics`() {
        val original = UUID.randomUUID()
        val first = fee(original).statusCode(200).body("outcome", equalTo("POSTED"))
            .extract().path<String>("journalId")
        fee(original).statusCode(200).body("journalId", equalTo(first))
        fee(original, "99.00").statusCode(409)
        fee(UUID.randomUUID(), "-2.00", original).statusCode(200)
        assertThat(PensionAccountingFixtures.scalar("SELECT count(*) FROM journal_entries")).isEqualTo("2")
        assertThat(PensionAccountingFixtures.net("PS1310")).isEqualByComparingTo("8.00")
        assertThat(PensionAccountingFixtures.net("PS6110")).isEqualByComparingTo("-8.00")
        assertThat(
            PensionAccountingFixtures.scalar(
                "SELECT count(*) FROM journal_lines l JOIN gl_accounts g ON g.id = l.gl_account_id " +
                    "WHERE g.code NOT IN ('PS1310', 'PS6110')",
            ),
        ).isEqualTo("0")
    }

    @Test
    @Order(2)
    fun `company refuses another destination and a correction without its original`() {
        PensionAccountingFixtures.fee(
            PensionAccountingFixtures.FUND,
            PensionAccountingFixtures.FUND,
            UUID.randomUUID(),
            clock.today(),
        ).statusCode(409)
        fee(UUID.randomUUID(), "-2.00", UUID.randomUUID()).statusCode(409)
        assertThat(PensionAccountingFixtures.scalar("SELECT count(*) FROM journal_entries")).isEqualTo("0")
    }

    @Test
    @Order(MAKER_ORDER)
    @TestSecurity(user = "company-reconciliation-maker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "company-maker-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "company-reconciliation-maker"),
        ],
    )
    fun `company reconciliation requires its own sources and a separate checker`() {
        proposal = given().contentType("application/json").body(
            PensionAccountingFixtures.proposal(
                PensionAccountingFixtures.COMPANY,
                clock.today(),
                listOf("bank", "receivables", "portfolio", "expenses", "tax"),
                listOf("PS1310", "PS6110"),
            ),
        ).post(RECONCILIATIONS).then().statusCode(200).extract().path("id")
        given().contentType("application/json").post("$RECONCILIATIONS/$proposal/approve").then().statusCode(409)
    }

    @Test
    @Order(CHECKER_ORDER)
    @TestSecurity(user = "company-reconciliation-checker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "company-checker-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "company-reconciliation-checker"),
        ],
    )
    fun `company checker records its own control rather than a bank tieout`() {
        given().contentType("application/json").post("$RECONCILIATIONS/$proposal/approve").then().statusCode(200)
        assertThat(PensionAccountingFixtures.scalar("SELECT control_kind FROM ledger_tieout_runs"))
            .isEqualTo("PENSION_COMPANY")
        assertThat(
            PensionAccountingFixtures.scalar(
                "SELECT count(*) FROM ledger_book_identity WHERE book_kind = 'PENSION_COMPANY' " +
                    "AND book_id = '${PensionAccountingFixtures.COMPANY}'",
            ),
        ).isEqualTo("1")
        given().contentType("application/json").post("$RECONCILIATIONS/$proposal/approve").then().statusCode(409)
    }

    private fun fee(nav: UUID, amount: String = "10.00", original: UUID? = null) = PensionAccountingFixtures.fee(
        PensionAccountingFixtures.COMPANY,
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
