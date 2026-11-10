// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.integration

import com.openbank.ledger.application.usecase.LedgerBookKind
import com.openbank.ledger.application.usecase.PensionBookIdentity
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** The default bank database must never acquire pension identity, journals or reconciliation proof. */
@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    restrictToAnnotatedClass = true,
    initArgs = [ResourceArg(name = "db", value = "pension_bank_isolation_it")],
)
class PensionBankIsolationIT {
    @Inject
    lateinit var book: PensionBookIdentity

    @Inject
    lateinit var clock: AccountingClock

    @Test
    @TestSecurity(user = "service-account-openbank-pension-fund", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "pension-fund-test-subject"),
            Claim(key = "azp", value = "openbank-pension-fund"),
            Claim(key = "preferred_username", value = "service-account-openbank-pension-fund"),
        ],
    )
    fun `verified pension source cannot book a fee or bind the bank database`() {
        assertThat(book.kind).isEqualTo(LedgerBookKind.BANK)
        val before = counts()

        PensionAccountingFixtures.fee(
            PensionAccountingFixtures.FUND,
            PensionAccountingFixtures.FUND,
            UUID.randomUUID(),
            clock.today(),
        ).statusCode(409)

        assertThat(counts()).isEqualTo(before)
    }

    @Test
    @TestSecurity(user = "pension-reconciliation-maker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "pension-maker-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "pension-reconciliation-maker"),
        ],
    )
    fun `verified interactive accountant cannot create pension reconciliation in the bank`() {
        assertThat(book.kind).isEqualTo(LedgerBookKind.BANK)
        val before = counts()

        given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString()).body(
            PensionAccountingFixtures.proposal(
                PensionAccountingFixtures.FUND,
                clock.today(),
                listOf("cash", "units", "custody", "nav", "fees", "settlements"),
                listOf("PF5210", "PF3210"),
            ),
        ).post("/api/v1/ledger/pension-accounting/reconciliations").then().statusCode(409)

        assertThat(counts()).isEqualTo(before)
    }

    private fun counts(): Map<String, String> = listOf(
        "ledger_book_identity",
        "ledger_pension_fee_receipts",
        "ledger_pension_reconciliations",
        "ledger_tieout_runs",
        "journal_entries",
        "journal_lines",
    ).associateWith { PensionAccountingFixtures.scalar("SELECT count(*) FROM $it") }
}
