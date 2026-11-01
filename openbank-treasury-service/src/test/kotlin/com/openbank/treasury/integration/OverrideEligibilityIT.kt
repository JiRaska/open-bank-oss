// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.domain.cashflow.CashFlowState
import com.openbank.treasury.domain.cashflow.FlowNotOverridableException
import com.openbank.treasury.domain.cashflow.OverrideStatus
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.it.PostgresTestResource
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
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D4 over real HTTP and Postgres: an override proposed while its flow was eligible is
 * refused at APPROVAL (409 FLOW_NOT_OVERRIDABLE) once the flow has settled or its deal has ended,
 * and the store refuses it again under the flow-row lock even when handed a flow copy read before
 * the change — the override stays PENDING and the flow is never marked manual.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(OverrideEligibilityIT.OwnDatabase::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OverrideEligibilityIT {
    class OwnDatabase : QuarkusTestProfile

    @Inject
    lateinit var flows: CashFlowRepository

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()
    private val start = today.plusDays(10)

    private fun post(path: String, body: String? = null) = given()
        .contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    @Test
    @Order(1)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - three placements are drafted and submitted`() {
        deals = List(3) {
            val id: String = post(
                "/deals",
                """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK","principal":100000.00,
                   "rate":4.25,"valueDate":"$start","maturityDate":"${start.plusDays(30)}","bookId":"BANKING-BOOK"}""",
            ).then().statusCode(201).extract().path("dealId")
            post("/deals/$id/submit").then().statusCode(200)
            id
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `2 - they are booked`() {
        deals.forEach { post("/deals/$it/approve").then().statusCode(200).body("state", equalTo("BOOKED")) }
        interest = deals.map { id ->
            given().`when`().get("/api/v1/treasury/deals/$id/cash-flows").then().statusCode(200)
                .extract().path<String>("flows.find { it.kind == 'INTEREST' }.flowId")
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "bob.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `3 - back office proposes an override on each interest flow while it is still eligible`() {
        overrides = deals.indices.map { i ->
            post(
                "/deals/${deals[i]}/cash-flows/${interest[i]}/overrides",
                """{"paymentDate":"${start.plusDays(31)}","reason":"counterparty pays a day later"}""",
            ).then().statusCode(201).body("status", equalTo("PENDING")).extract().path("overrideId")
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `4 - a flow that settled after the proposal cannot be overridden at approval`() {
        exec("update deal_cash_flows set state = 'SETTLED' where flow_id = ?", interest[0])
        post("/deals/${deals[0]}/cash-flows/${interest[0]}/overrides/${overrides[0]}/approve")
            .then().statusCode(409).body("error", equalTo("FLOW_NOT_OVERRIDABLE"))
        assertUnapplied(0)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `5 - a flow of a deal that ended after the proposal cannot be overridden at approval`() {
        exec("update deals set state = 'MATURED' where deal_id = ?", deals[1])
        post("/deals/${deals[1]}/cash-flows/${interest[1]}/overrides/${overrides[1]}/approve")
            .then().statusCode(409).body("error", equalTo("FLOW_NOT_OVERRIDABLE"))
        assertUnapplied(1)
    }

    @Test
    @Order(6)
    fun `6 - the store re-checks under the flow lock, even when handed a copy read before the change`() {
        val flowId = UUID.fromString(interest[2])
        val dealId = UUID.fromString(deals[2])
        val pending = onContext { checkNotNull(flows.findOverride(UUID.fromString(overrides[2]))) }
        val read = onContext { flows.flows(dealId, null).single { it.flowId == flowId } }
        assertThat(read.state).isIn(CashFlowState.PROJECTED, CashFlowState.FIXED)
        val approved = pending.approve(Actor("anna.approver", ActorType.HUMAN), Instant.now())
        // The deal ends between the approver's read and the write.
        exec("update deals set state = 'REVERSED' where deal_id = ?", deals[2])
        assertThatThrownBy {
            onContext { flows.saveOverride(approved, OverrideStatus.PENDING, read.withOverride(approved), null, null) }
        }.isInstanceOf(FlowNotOverridableException::class.java)
        assertUnapplied(2)
    }

    private fun assertUnapplied(i: Int) {
        assertThat(count("select count(*) from deal_cash_flows where flow_id = ? and manual", interest[i])).isZero()
        assertThat(
            count(
                "select count(*) from cash_flow_overrides where override_id = ? and status = 'PENDING'",
                overrides[i],
            ),
        )
            .isEqualTo(1)
    }

    private fun exec(sql: String, id: String) = jdbc { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            assertThat(ps.executeUpdate()).isEqualTo(1)
        }
    }

    private fun count(sql: String, id: String): Int = jdbc { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun <T> jdbc(block: (Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private companion object {
        lateinit var deals: List<String>
        lateinit var interest: List<String>
        lateinit var overrides: List<String>
    }
}
