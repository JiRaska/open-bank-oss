// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.application.exit.DeathClaimService
import com.openbank.pension.application.exit.NotifyDeathCommand
import com.openbank.pension.application.maintenance.ContractChangeStore
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.maintenance.BeneficiaryDesignationVersion
import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.ContractFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** #12376 over real HTTP and Postgres: routing, ownership, idempotency, history, and the write races. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ContractChangesApiIT {

    @Inject
    lateinit var contractUseCase: PensionContractUseCase

    @Inject
    lateinit var contractRepository: PensionContractRepository

    @Inject
    lateinit var store: ContractChangeStore

    @Inject
    lateinit var deathClaims: DeathClaimService

    @Inject
    lateinit var claimRepo: com.openbank.pension.application.exit.DeathClaimRepository

    private val party: UUID = UUID.randomUUID()
    private val contracts = "/api/v2/pension/contracts"

    private fun <T> onVertx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    private fun active(): UUID = onVertx { ContractFixtures.activeContract(contractUseCase, contractRepository, party) }

    private fun req(asParty: UUID? = party, key: String? = UUID.randomUUID().toString()) = given()
        .contentType("application/json")
        .apply { if (asParty != null) header("X-Customer-Party-Id", asParty.toString()) }
        .apply { if (key != null) header("Idempotency-Key", key) }

    private fun jdbc(sql: String, id: UUID): Long {
        val c = ConfigProvider.getConfig()
        DriverManager.getConnection(
            c.getValue("quarkus.datasource.jdbc.url", String::class.java),
            c.getValue("quarkus.datasource.username", String::class.java),
            c.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement(sql).use { st ->
                st.setObject(1, id)
                st.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `schedule change - preview, SCA change, replay, history`() {
        val id = active()
        val body = """{"amount":2000,"frequency":"MONTHLY","dayOfMonth":20}"""
        req(key = null).body(body).post("$contracts/$id/contribution-schedule/preview").then().statusCode(400)
        val hash: String = req().body(body).post("$contracts/$id/contribution-schedule/preview").then().statusCode(200)
            .extract().path("documentSha256")
        assertThat(hash).hasSize(64)
        val change = """{"amount":2000,"frequency":"MONTHLY","dayOfMonth":20,"scaChallengeId":"${UUID.randomUUID()}"}"""
        req(key = null).body(change).post("$contracts/$id/contribution-schedule/changes").then().statusCode(400)
        req(key = "s-1").body(change).post("$contracts/$id/contribution-schedule/changes").then().statusCode(201)
            .body("seq", equalTo(1)).body("status", equalTo("SCHEDULED"))
        req(key = "s-1").body(change).post("$contracts/$id/contribution-schedule/changes").then().statusCode(201)
            .header("Idempotent-Replayed", "true")
        req(asParty = UUID.randomUUID()).get("$contracts/$id/contribution-schedule").then().statusCode(404)
        req(key = null).get("$contracts/$id/contribution-schedule").then().statusCode(200)
            .body("history", hasSize<Any>(1)).body("pending.amount", equalTo(2000.0f))
        assertThat(jdbc("select count(*) from pension_contribution_schedule_changes where contract_id = ?", id))
            .isEqualTo(1)
        // below the pack minimum: 400, nothing stored
        req().body("""{"amount":50,"frequency":"MONTHLY","dayOfMonth":20,"acknowledgeIncentiveReduction":true}""")
            .post("$contracts/$id/contribution-schedule/preview").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `beneficiary change - shares must total 100, history kept, contract updated`() {
        val id = active()
        val bad = """{"beneficiaries":[{"name":"A","sharePercent":60},{"name":"B","sharePercent":30}],""" +
            """"scaChallengeId":"x"}"""
        req().body(bad).post("$contracts/$id/beneficiaries/changes").then().statusCode(400)
        val good =
            """{"beneficiaries":[{"name":"John Doe","sharePercent":40},{"name":"Jane Doe","sharePercent":60}],""" +
                """"scaChallengeId":"${UUID.randomUUID()}"}"""
        req().body(good).post("$contracts/$id/beneficiaries/changes").then().statusCode(201).body("seq", equalTo(1))
        req(key = null).get("$contracts/$id/beneficiaries").then().statusCode(200)
            .body("current[0].name", equalTo("John Doe")).body("history", hasSize<Any>(1))
    }

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `operator reads history but cannot change`() {
        val id = active()
        given().get("/api/v2/pension/operator/contracts/$id/beneficiaries").then().statusCode(200)
            .body("current", hasSize<Any>(2))
        given().get("/api/v2/pension/operator/contracts/$id/contribution-schedule").then().statusCode(200)
        given().contentType("application/json").header("Idempotency-Key", "k")
            .body("""{"beneficiaries":[]}""").post("$contracts/$id/beneficiaries/changes").then().statusCode(400)
    }

    private fun version(seq: Int, key: String, vararg b: Beneficiary) =
        BeneficiaryDesignationVersion(seq, b.toList(), "0".repeat(64), "sca-$key", key, Instant.now())

    @Test
    fun `two parallel designations from the same read - exactly one wins`() {
        val id = active()
        val snapshot = onVertx { contractRepository.findById(id)!! }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = listOf("a", "b").map { k ->
            pool.submit(
                Callable {
                    start.await()
                    runCatching {
                        val b = Beneficiary("Person $k", null, BigDecimal("100"))
                        onVertx {
                            store.saveBeneficiaries(
                                snapshot.designateBeneficiaries(listOf(b), Instant.now()),
                                version(1, k, b),
                            )
                        }
                    }
                },
            )
        }
        start.countDown()
        val outcomes = results.map { it.get() }
        pool.shutdown()
        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat(jdbc("select count(*) from pension_beneficiary_designations where contract_id = ?", id)).isEqualTo(1)
    }

    @Test
    fun `a death claim registered after the change was read still wins`() {
        val id = active()
        // TERMINATING: death notification then does NOT rewrite the contract row, so only the
        // in-transaction death-claim check (under the contract lock) can stop the stale change.
        onVertx { contractRepository.save(contractRepository.findById(id)!!.requestTermination(Instant.now())) }
        val snapshot = onVertx { contractRepository.findById(id)!! }
        onVertx {
            deathClaims.notify(NotifyDeathCommand("op-1", id, LocalDate.now().minusDays(1), "certificate-1", "death-1"))
        }
        val b = Beneficiary("Late Change", null, BigDecimal("100"))
        val outcome = runCatching {
            onVertx {
                store.saveBeneficiaries(
                    snapshot.designateBeneficiaries(listOf(b), Instant.now()),
                    version(1, "late", b),
                )
            }
        }
        assertThat(outcome.exceptionOrNull()).hasMessageContaining("death claim")
        assertThat(jdbc("select count(*) from pension_beneficiary_designations where contract_id = ?", id)).isZero()
    }

    @Test
    fun `two parallel schedule changes planned on the same history - exactly one wins`() {
        val id = active()
        val contract = onVertx { contractRepository.findById(id)!! }
        val base = onVertx { store.scheduleHistory(id) }
        val pack = com.openbank.pension.infrastructure.pack.JurisdictionPackLoader.loadRegistry().pinnedFor(contract)
        val today = LocalDate.now(java.time.ZoneOffset.UTC)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = listOf("2000", "2100").map { amount ->
            pool.submit(
                Callable {
                    start.await()
                    runCatching {
                        val req = com.openbank.pension.domain.maintenance.ScheduleChangeRequest(
                            BigDecimal(amount),
                            com.openbank.pension.domain.model.ContributionFrequency.MONTHLY,
                            20,
                        )
                        val plan = base.plan(contract, pack, req, today)
                        onVertx {
                            store.saveSchedule(base.record(plan, "sca-$amount", "k-$amount", today, Instant.now()))
                        }
                    }
                },
            )
        }
        start.countDown()
        val outcomes = results.map { it.get() }
        pool.shutdown()
        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat(
            jdbc("select count(*) from pension_contribution_schedule_changes where contract_id = ?", id),
        ).isEqualTo(1)
    }

    /**
     * Change vs death registration IN PARALLEL, repeated. Whatever the interleaving, the claim's
     * claimants are the designation in force when the claim committed: either the change was
     * refused, or it committed first and the claim carries it. Never a committed designation the
     * claim does not reflect.
     */
    @Test
    fun `beneficiary change racing a death registration - the claim always reflects the final designation`() {
        repeat(RACE_ROUNDS) { round ->
            val id = active()
            onVertx { contractRepository.save(contractRepository.findById(id)!!.requestTermination(Instant.now())) }
            val snapshot = onVertx { contractRepository.findById(id)!! }
            val b = Beneficiary("Racer $round", null, BigDecimal("100"))
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val change = pool.submit(
                Callable {
                    start.await()
                    runCatching {
                        onVertx {
                            store.saveBeneficiaries(
                                snapshot.designateBeneficiaries(listOf(b), Instant.now()),
                                version(1, "r$round", b),
                            )
                        }
                    }
                },
            )
            val death = pool.submit(
                Callable {
                    start.await()
                    onVertx {
                        deathClaims.notify(
                            NotifyDeathCommand("op-1", id, LocalDate.now().minusDays(1), "cert", "d$round"),
                        )
                    }
                },
            )
            start.countDown()
            val changed = change.get().isSuccess
            death.get()
            pool.shutdown()
            val claimantNames = onVertx { claimRepo.findByContract(id)!! }.claimants.map { it.name }
            if (changed) {
                assertThat(claimantNames).containsExactly("Racer $round")
            } else {
                assertThat(claimantNames).containsExactlyInAnyOrder("Jane Doe", "John Doe")
                assertThat(
                    jdbc("select count(*) from pension_beneficiary_designations where contract_id = ?", id),
                ).isZero()
            }
        }
    }

    private companion object {
        const val RACE_ROUNDS = 8
    }
}
