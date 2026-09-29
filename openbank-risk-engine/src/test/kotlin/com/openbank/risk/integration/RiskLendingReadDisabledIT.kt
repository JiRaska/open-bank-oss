// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.lendingLoan
import com.openbank.risk.domain.Fixtures.tiedOutWithLoans
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * `openbank.risk.lending.enabled=false` — the value the deployed sandbox runs with — must really
 * switch the loan-book read off, by EFFECT: lending is never asked, and Loans Receivable is carried
 * at GL level as before ADR-0314 D4. A book that would break the tie-out is loaded into the fake to
 * prove it is not read, not merely that it happens to agree.
 */
@QuarkusTest
@TestProfile(RiskLendingReadDisabledIT.LendingOff::class)
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskLendingReadDisabledIT {

    class LendingOff : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.risk.lending.enabled" to "false")
    }

    @Inject
    lateinit var ledger: FakeLedgerPort

    @Inject
    lateinit var lending: FakeLendingPort

    @AfterEach
    fun reset() {
        lending.loans = emptyList()
        ledger.inputs = Fixtures.tiedOut()
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `with the read off, lending is never asked and Loans Receivable stays a GL position`() {
        ledger.inputs = tiedOutWithLoans(BigDecimal("100.00"))
        lending.loans = listOf(lendingLoan()) // would be UNTIED against 100.00 if it were read
        val before = lending.reads.get()

        val id = given().contentType("application/json").body("""{"asOf":"2026-09-27"}""")
            .`when`().post("/api/v1/risk/snapshots").then().statusCode(201)
            .body("status", equalTo("TIED_OUT")).extract().path<String>("id")

        assertThat(lending.reads.get()).isEqualTo(before)
        given().`when`().get("/api/v1/risk/snapshots/$id/positions").then().statusCode(200)
            .body("positions.findAll { it.kind == 'LOAN' }", hasSize<Any>(0))
            .body("positions.find { it.glAccountCode == '1200' }.kind", equalTo("GL_ACCOUNT"))
    }
}
