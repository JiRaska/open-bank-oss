// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.`in`.IntentReconciliation
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.out.DayJournalPostings
import com.openbank.treasury.application.port.out.TreasuryCloseEvidencePort
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * #13883 on real Postgres: the flow dies between the ledger's commit and treasury's local commit
 * ([FakeLedger.loseNextResponse]: the ledger holds the journal, treasury never sees the answer). The
 * posting intent written before the call is then the only trace treasury has: it holds the value
 * day's close report, and the reconciler completes it — deal SETTLED, ONE journal in the ledger, ONE
 * `deal_journals` row, the intent COMPLETED in the transaction of the state change.
 *
 * Ordered: four-eyes needs a dealer and a different approver, and `@TestSecurity` fixes one identity
 * per method. The reconciler is called directly on a Vert.x context (its cron never fires in %test).
 */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PostingIntentReconcileIT {

    @Inject
    lateinit var ledger: FakeLedger

    @Inject
    lateinit var deals: TreasuryDealUseCase

    @Inject
    lateinit var evidence: TreasuryCloseEvidencePort

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    private fun action(id: String, verb: String) = given()
        .contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .`when`().post("/api/v1/treasury/deals/$id/$verb")

    @Test
    @Order(1)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - a dealer drafts and submits a placement`() {
        ledger.reset()
        dealId = given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK","principal":4321.00,
                "rate":4.25,"valueDate":"$today","maturityDate":"${today.plusDays(30)}"}""",
            )
            .`when`().post("/api/v1/treasury/deals").then().statusCode(201).extract().path("dealId")
        action(dealId, "submit").then().statusCode(200)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `2 - the flow dies after the ledger committed - an open intent holds the day`() {
        action(dealId, "approve").then().statusCode(200).body("state", equalTo("BOOKED"))
        action(dealId, "confirm").then().statusCode(200).body("state", equalTo("CONFIRMED"))
        val before = postings()
        ledger.loseNextResponse = true
        action(dealId, "settle").then().statusCode(500)

        val key = "treasury:$dealId:settled"
        assertThat(ledger.journals.keys).describedAs("the ledger committed the journal").containsExactly(key)
        assertThat(journalRows()).describedAs("treasury never recorded it").isZero()
        assertThat(intent(key)).isEqualTo("OPEN" to today)
        assertThat(state()).isEqualTo("CONFIRMED")
        assertThat(postings().openIntents)
            .describedAs("the open intent is a hold on its value day")
            .isEqualTo(before.openIntents + 1)
    }

    @Test
    @Order(3)
    fun `3 - an intent younger than the grace cutoff is left alone`() {
        val run = reconcile(Instant.EPOCH)
        assertThat(run.completed).isZero()
        assertThat(intent("treasury:$dealId:settled").first).isEqualTo("OPEN")
    }

    @Test
    @Order(4)
    fun `4 - the reconciler completes it under the same key - one journal, deal SETTLED, hold released`() {
        val before = postings()
        val run = reconcile(Instant.now().plusSeconds(1))
        // Other classes share this database and may leave intents of their own; this deal's is the subject.
        assertThat(run.completed).isGreaterThanOrEqualTo(1)

        val key = "treasury:$dealId:settled"
        assertThat(state()).isEqualTo("SETTLED")
        assertThat(intent(key).first).isEqualTo("COMPLETED")
        assertThat(ledger.calls.filter { it == key }).describedAs("re-posted under ONE key").hasSize(2)
        assertThat(ledger.journals).describedAs("and the ledger holds one journal").hasSize(1)
        assertThat(journalRows()).isEqualTo(1)
        assertThat(journalId()).isEqualTo(ledger.journals.getValue(key).first)
        assertThat(postings().openIntents).isLessThan(before.openIntents)
        // Completed in the SAME transaction as the state change: same writing xid.
        assertThat(intentXmin(key)).isEqualTo(dealXmin())
    }

    @Test
    @Order(5)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `5 - a dealer drafts two more placements`() {
        raceId = draftAndSubmit("4322.00")
        closeId = draftAndSubmit("4323.00")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `6 - both lose the ledger's answer after commit`() {
        for (id in listOf(raceId, closeId)) {
            action(id, "approve").then().statusCode(200)
            action(id, "confirm").then().statusCode(200)
            ledger.loseNextResponse = true
            action(id, "settle").then().statusCode(500)
            assertThat(intent("treasury:$id:settled").first).isEqualTo("OPEN")
        }
    }

    @Test
    @Order(9)
    fun `9 - two reconcilers racing resume the intent exactly once`() {
        val key = "treasury:$raceId:settled"
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runs = (1..2).map {
                pool.submit(
                    Callable {
                        barrier.await()
                        reconcile(Instant.now().plusSeconds(1))
                    },
                )
            }.map { it.get() }
            assertThat(state(raceId)).isEqualTo("SETTLED")
            assertThat(intent(key).first).isEqualTo("COMPLETED")
            assertThat(ledger.calls.filter { it == key })
                .describedAs("the settle call plus ONE resume: the second reconciler skipped the claimed row")
                .hasSize(2)
            assertThat(runs.sumOf { it.failures.size }).describedAs("no false error from the loser").isZero()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "agent:treasury-drafter", roles = ["ROLE_TREASURY_APPROVER"])
    fun `7 - an AI agent cannot close a posting intent`() {
        close(closeId, "SETTLED", "agent tries").then().statusCode(403)
        assertThat(intent("treasury:$closeId:settled").first).isEqualTo("OPEN")
    }

    @Test
    @Order(8)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `8 - an approver closes it with a reason, recorded, and a second close finds nothing`() {
        close(closeId, "SETTLED", "").then().statusCode(400)
        close(closeId, "SETTLED", "ledger confirmed to hold no journal").then().statusCode(204)
        val row = closedRow("treasury:$closeId:settled")
        assertThat(row.first).isEqualTo("REFUSED")
        assertThat(row.second).isEqualTo("adam.approver")
        close(closeId, "SETTLED", "again").then().statusCode(404)
    }

    private fun close(id: String, event: String, reason: String) = given()
        .contentType("application/json")
        .body("""{"event":"$event","reason":"$reason"}""")
        .`when`().post("/api/v1/treasury/deals/$id/posting-intent/close")

    private fun draftAndSubmit(principal: String): String {
        val id: String = given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK","principal":$principal,
                "rate":4.25,"valueDate":"$today","maturityDate":"${today.plusDays(30)}"}""",
            )
            .`when`().post("/api/v1/treasury/deals").then().statusCode(201).extract().path("dealId")
        action(id, "submit").then().statusCode(200)
        return id
    }

    private fun state(id: String): String = jdbc { c ->
        c.prepareStatement("select state from deals where deal_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    private fun closedRow(key: String): Pair<String, String?> = jdbc { c ->
        c.prepareStatement("select status, closed_by from deal_posting_intent where idempotency_key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1) to rs.getString(2)
            }
        }
    }

    private fun reconcile(olderThan: Instant): IntentReconciliation =
        onContext { deals.reconcilePostingIntents(olderThan) }

    private fun postings(): DayJournalPostings = VertxContextSupport.subscribeAndAwait { evidence.postingsFor(today) }

    private fun state(): String = scalar("select state from deals where deal_id = ?") { it.getString(1) }

    private fun journalRows(): Int =
        scalar("select count(*) from deal_journals where deal_id = ? and event = 'SETTLED'") { it.getInt(1) }

    private fun journalId(): UUID =
        scalar("select journal_id from deal_journals where deal_id = ? and event = 'SETTLED'") {
            it.getObject(1, UUID::class.java)
        }

    private fun dealXmin(): String = scalar("select xmin::text from deals where deal_id = ?") { it.getString(1) }

    private fun intentXmin(key: String): String = jdbc { c ->
        c.prepareStatement("select xmin::text from deal_posting_intent where idempotency_key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    private fun intent(key: String): Pair<String, LocalDate> = jdbc { c ->
        c.prepareStatement("select status, value_date from deal_posting_intent where idempotency_key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                assertThat(rs.next()).describedAs("an intent row for %s", key).isTrue()
                rs.getString(1) to rs.getObject(2, LocalDate::class.java)
            }
        }
    }

    private fun <T> scalar(sql: String, read: (java.sql.ResultSet) -> T): T = jdbc { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, UUID.fromString(dealId))
            ps.executeQuery().use { rs ->
                rs.next()
                read(rs)
            }
        }
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    companion object {
        lateinit var dealId: String
        lateinit var raceId: String
        lateinit var closeId: String
    }
}
