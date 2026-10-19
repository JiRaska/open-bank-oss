// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * Hardening of ADR-0315 D4 over real Postgres: every limit-consuming write takes the SAME
 * (counterparty, limit currency) line lock and the SAME exposure query. An FX spot (whose CZK
 * counter-amount consumes the CZK line) and a CZK placement with the same 500 M CZK counterparty,
 * 300 M each, submitted at the same instant: each fits alone, together they do not — exactly one
 * may consume the line as fitting; the other is refused (422 LIMIT_BREACHED) or goes to review
 * as a breach. Never both.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(LimitLineRaceIT.OwnDatabase::class)
class LimitLineRaceIT {
    class OwnDatabase : QuarkusTestProfile

    private val today: LocalDate = LocalDate.now(ZoneId.of("Europe/Prague"))

    private fun post(path: String, body: String? = null) = given()
        .contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    @Test
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `an FX spot and a CZK placement racing for one CZK line - exactly one fits`() {
        val fx: String = post(
            "/deals",
            """{"product":"FX_SPOT","counterpartyId":"SIMBK-C","buyCurrency":"EUR","sellCurrency":"CZK",
               "principal":12000000.00,"rate":25.0}""",
        ).then().statusCode(201).extract().path("dealId")
        val placement: String = post(
            "/deals",
            """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-C","currency":"CZK","principal":300000000.00,
               "rate":4.25,"valueDate":"$today","maturityDate":"${today.plusDays(30)}","bookId":"BANKING-BOOK"}""",
        ).then().statusCode(201).extract().path("dealId")

        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val outcomes = try {
            listOf(fx, placement).map { id ->
                pool.submit(
                    Callable {
                        barrier.await()
                        val r = post("/deals/$id/submit").then().extract()
                        when (r.statusCode()) {
                            200 -> if (r.path<Boolean>("limitCheck.breached") == true) "BREACH" else "FIT"
                            422 -> "REFUSED:" + r.path<String>("error")
                            else -> "HTTP ${r.statusCode()}: ${r.asString()}"
                        }
                    },
                )
            }.map { it.get() }
        } finally {
            pool.shutdown()
        }
        println("LIMIT-RACE outcomes=$outcomes")
        assertThat(outcomes.count { it == "FIT" }).describedAs(outcomes.toString()).isEqualTo(1)
        assertThat(outcomes.filter { it != "FIT" }.single()).isIn("BREACH", "REFUSED:LIMIT_BREACHED")
    }
}
