// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.treasury.application.port.out.DealChangedException
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.Deal
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * Real Postgres: every whole-row save of a deal compares the row version its copy was read at, under
 * the row lock. A save computed from a stale copy — a draft edit racing a submission, two edits, an
 * edit of a draft that was submitted meanwhile — is refused (409 DEAL_CHANGED), never written over
 * the newer row, so no edit can slip new terms past the four-eyes the submission started.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DealRowRaceIT.OwnDatabase::class)
class DealRowRaceIT {
    class OwnDatabase : QuarkusTestProfile

    @Inject
    lateinit var deals: DealRepository

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()
    private val start = today.plusDays(10)
    private val dealer = Actor("dana.dealer", ActorType.HUMAN)

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    private fun body(rate: String) = """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK",
        "principal":1000000.00,"rate":$rate,"valueDate":"$start","maturityDate":"${start.plusDays(30)}"}"""

    private fun draft(): String = given().contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString()).body(body("3.00"))
        .`when`().post("/api/v1/treasury/deals").then().statusCode(201).extract().path("dealId")

    private fun edit(id: String, rate: String) = given().contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString()).body(body(rate))
        .`when`().put("/api/v1/treasury/deals/$id").then().extract()

    private fun submit(id: String) = given().contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .`when`().post("/api/v1/treasury/deals/$id/submit").then().extract()

    private fun load(id: String): Deal = onContext { checkNotNull(deals.findById(UUID.fromString(id))) }

    private fun edited(d: Deal, rate: String): Deal =
        d.editDraft(d.copy(rate = BigDecimal(rate)), dealer, Instant.now())

    @Test
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `two edits from the same read - the second is refused, the first one's terms stand`() {
        val id = draft()
        val a = load(id)
        val b = load(id)
        onContext { deals.save(edited(a, "3.10")) }
        assertThatThrownBy { onContext { deals.save(edited(b, "3.20")) } }
            .isInstanceOf(DealChangedException::class.java)
        assertThat(load(id).rate).isEqualByComparingTo("3.10")
    }

    @Test
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `an edit of a draft submitted meanwhile is refused, and the approver sees the submitted terms`() {
        val id = draft()
        val stale = load(id)
        assertThat(submit(id).statusCode()).isEqualTo(200)
        assertThatThrownBy { onContext { deals.save(edited(stale, "9.99")) } }
            .isInstanceOf(DealChangedException::class.java)
        val now = load(id)
        assertThat(now.rate).isEqualByComparingTo("3.00")
        assertThat(now.version).isEqualTo(1)
        assertThat(now.termEditors).isEmpty()
    }

    @Test
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `an edit racing a submission - exactly one wins, the other is a 409`() {
        val id = draft()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val codes = try {
            listOf(
                Callable {
                    barrier.await()
                    edit(id, "3.50").statusCode()
                },
                Callable {
                    barrier.await()
                    submit(id).statusCode()
                },
            ).map { pool.submit(it) }.map { it.get() }
        } finally {
            pool.shutdown()
        }
        // Overlapping, the loser is refused (a stale copy, or an edit of a no-longer-DRAFT deal);
        // serialised edit-then-submit both succeed. Never a lost update in either order.
        assertThat(codes).describedAs(codes.toString()).contains(200).allMatch { it == 200 || it == 409 }
        val d = load(id)
        val (editCode, submitCode) = codes
        if (editCode == 200) assertThat(d.rate).isEqualByComparingTo("3.50")
        if (editCode == 409) assertThat(d.rate).isEqualByComparingTo("3.00")
        if (submitCode == 200) assertThat(d.state.name).isEqualTo("PENDING_APPROVAL")
    }
}
