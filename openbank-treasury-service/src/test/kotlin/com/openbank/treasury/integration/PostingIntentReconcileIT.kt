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
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Inject
    lateinit var meters: io.micrometer.core.instrument.MeterRegistry

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
        lateId = draftAndSubmit("4324.00")
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
    @Order(11)
    fun `11 - two reconcilers racing resume the intent exactly once, past more escalated intents than a batch`() {
        val key = "treasury:$raceId:settled"
        // More escalated intents than BOTH racing passes' batches together (2 x 100), all OLDER than
        // this one: were they claimable, both passes would take only them and never reach it.
        val flood = "it-escalated-${UUID.randomUUID()}"
        jdbc { c ->
            c.prepareStatement(
                "insert into deal_posting_intent (idempotency_key, deal_id, event, action, value_date, actor_id, " +
                    "actor_type, created_at, escalated_at) select ? || ':' || g, gen_random_uuid(), 'SETTLED', " +
                    "'SETTLE', date '2000-01-03', 'it', 'HUMAN', now() - interval '2 days', now() " +
                    "from generate_series(1, 201) g",
            ).use { ps ->
                ps.setString(1, flood)
                ps.executeUpdate()
            }
        }
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
            jdbc { c ->
                c.prepareStatement("delete from deal_posting_intent where idempotency_key like ?").use { ps ->
                    ps.setString(1, "$flood:%")
                    ps.executeUpdate()
                }
            }
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
    fun `8 - the approver who booked and settled the deal may not close its posting intent`() {
        backdate("treasury:$closeId:settled")
        close(closeId, "SETTLED", "my own posting").then().statusCode(422)
            .body("error", equalTo("FOUR_EYES_VIOLATION"))
        assertThat(intent("treasury:$closeId:settled").first).isEqualTo("OPEN")
    }

    @Test
    @Order(9)
    @TestSecurity(user = "olga.operator", roles = ["ROLE_TREASURY_APPROVER"])
    fun `9 - a third person closes it, not while in flight, recorded append-only, a second close finds nothing`() {
        val key = "treasury:$closeId:settled"
        close(closeId, "SETTLED", "").then().statusCode(400)
        // In flight: younger than the grace, or under a live reconciler claim - refused, not raced.
        jdbcUpdate("update deal_posting_intent set created_at = now() where idempotency_key = ?", key)
        close(closeId, "SETTLED", "too soon").then().statusCode(409).body("error", equalTo("POSTING_IN_FLIGHT"))
        backdate(key)
        jdbcUpdate(
            "update deal_posting_intent set claimed_until = now() + interval '10 minutes' where idempotency_key = ?",
            key,
        )
        close(closeId, "SETTLED", "claimed").then().statusCode(409).body("error", equalTo("POSTING_IN_FLIGHT"))
        jdbcUpdate("update deal_posting_intent set claimed_until = null where idempotency_key = ?", key)

        close(closeId, "SETTLED", "ledger confirmed to hold no journal").then().statusCode(204)
        val row = closedRow(key)
        assertThat(row.first).isEqualTo("CLOSED_BY_OPERATOR")
        assertThat(row.second).isEqualTo("olga.operator")
        assertThat(history(key)).containsExactly(
            listOf("OPENED", "adam.approver", null),
            listOf("CLOSED_BY_OPERATOR", "olga.operator", "ledger confirmed to hold no journal"),
        )
        close(closeId, "SETTLED", "again").then().statusCode(404)
        // The history refuses to be rewritten, erased or truncated.
        assertThatThrownBy {
            jdbcUpdate("update deal_posting_intent_history set reason = 'x' where idempotency_key = ?", key)
        }
            .hasMessageContaining("append-only")
        assertThatThrownBy { jdbcUpdate("delete from deal_posting_intent_history where idempotency_key = ?", key) }
            .hasMessageContaining("append-only")
        assertThatThrownBy {
            jdbc { c -> c.createStatement().use { it.execute("truncate deal_posting_intent_history") } }
        }
            .hasMessageContaining("append-only")
    }

    @Test
    @Order(10)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `10 - a re-issue after the operator close reopens it with its first day and keeps the close on record`() {
        val key = "treasury:$closeId:settled"
        action(closeId, "settle").then().statusCode(200).body("state", equalTo("SETTLED"))
        assertThat(intent(key)).isEqualTo("COMPLETED" to today)
        assertThat(closedRow(key)).isEqualTo("COMPLETED" to "olga.operator")
        assertThat(history(key).map { it[0] }).containsExactly(
            "OPENED",
            "CLOSED_BY_OPERATOR",
            "REOPENED_AFTER_OPERATOR_CLOSE",
            "COMPLETED",
        )
        assertThat(ledger.calls.filter { it == key }).describedAs("re-posted under the same key").hasSize(2)
        assertThat(history(key)[2]).isEqualTo(listOf("REOPENED_AFTER_OPERATOR_CLOSE", "adam.approver", null))
    }

    @Test
    @Order(12)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `12 - a journal applied after its intent was closed is recorded beside the close, counted, never over it`() {
        val key = "treasury:$lateId:settled"
        action(lateId, "approve").then().statusCode(200)
        action(lateId, "confirm").then().statusCode(200)
        val before = meters.find("openbank.treasury.posting_intents.applied_after_close").counter()?.count() ?: 0.0
        // The close lands while this very settle is between the ledger's answer and its local commit.
        ledger.afterCommit = {
            jdbcUpdate(
                "update deal_posting_intent set status = 'CLOSED_BY_OPERATOR', closed_by = 'olga.operator', " +
                    "close_reason = 'raced' where idempotency_key = ?",
                key,
            )
        }
        action(lateId, "settle").then().statusCode(200).body("state", equalTo("SETTLED"))
        assertThat(closedRow(key)).describedAs("the close is not overwritten").isEqualTo(
            "CLOSED_BY_OPERATOR" to "olga.operator",
        )
        val late = jdbc { c ->
            c.prepareStatement(
                "select prior_status from deal_posting_intent_history " +
                    "where idempotency_key = ? and transition = 'JOURNAL_APPLIED_AFTER_CLOSE'",
            ).use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }
        assertThat(late).containsExactly("CLOSED_BY_OPERATOR")
        assertThat(meters.find("openbank.treasury.posting_intents.applied_after_close").counter()!!.count())
            .isEqualTo(before + 1)
    }

    private fun backdate(key: String) = jdbcUpdate(
        "update deal_posting_intent set created_at = now() - interval '10 minutes' where idempotency_key = ?",
        key,
    )

    private fun jdbcUpdate(sql: String, key: String): Int = jdbc { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, key)
            ps.executeUpdate()
        }
    }

    private fun history(key: String): List<List<String?>> = jdbc { c ->
        c.prepareStatement(
            "select transition, actor_id, reason from deal_posting_intent_history where idempotency_key = ? order by id",
        ).use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) listOf(rs.getString(1), rs.getString(2), rs.getString(3)) else null }
                    .toList()
            }
        }
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
        lateinit var lateId: String
    }
}
