// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * #12115 over real HTTP and Postgres: every funding-consuming booking takes the SAME (currency)
 * funding line lock and the SAME committed-outflows query. Two CZK placements of 300 M against a
 * 500 M contra balance, approved at the same instant: each fits alone, together they do not —
 * exactly one books; the other is refused (422 FUNDING_EXCEEDED). Never both.
 *
 * Ordered because four-eyes needs two different people, and `@TestSecurity` fixes one identity
 * per method (same shape as [TreasuryDealApiIT]). SIMBK-A carries a 2 B CZK line, so neither
 * placement trips the exposure guard and a refusal can only be the funding check.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(FundingLineRaceIT.FundingOn::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FundingLineRaceIT {

    /** The funding check is off in %test; this profile turns it on for its own boot. */
    class FundingOn : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.treasury.funding-check.enabled" to "true",
        )
    }

    @Inject
    lateinit var ledgerRead: FakeLedgerRead

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    @BeforeEach
    fun fund() {
        ledgerRead.reset()
        // The CZK contra (nostro 1001) holds 500 M, as the ledger's balance route answers it.
        ledgerRead.balances[Triple("1001", "CZK", today)] = BigDecimal("500000000.00")
    }

    private fun post(path: String, body: String? = null) = given()
        .contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    @Test
    @Order(1)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - a dealer drafts and submits both placements`() {
        ids = List(2) {
            val id: String = post(
                "/deals",
                """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK","principal":300000000.00,
                   "rate":4.25,"valueDate":"$today","maturityDate":"${today.plusDays(30)}","bookId":"BANKING-BOOK"}""",
            ).then().statusCode(201).extract().path("dealId")
            post("/deals/$id/submit").then().statusCode(200)
            id
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `2 - two approvals racing for one funded balance - exactly one books`() {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val outcomes = try {
            ids.map { id ->
                pool.submit(
                    Callable {
                        barrier.await()
                        val r = post("/deals/$id/approve").then().extract()
                        when (r.statusCode()) {
                            200 -> "BOOKED"
                            422 -> "REFUSED:" + r.path<String>("error")
                            else -> "HTTP ${r.statusCode()}: ${r.asString()}"
                        }
                    },
                )
            }.map { it.get() }
        } finally {
            pool.shutdown()
        }
        println("FUNDING-RACE outcomes=$outcomes")
        assertThat(outcomes.count { it == "BOOKED" }).describedAs(outcomes.toString()).isEqualTo(1)
        assertThat(outcomes.filter { it != "BOOKED" }.single()).isEqualTo("REFUSED:FUNDING_EXCEEDED")
    }

    companion object {
        private lateinit var ids: List<String>
    }
}
