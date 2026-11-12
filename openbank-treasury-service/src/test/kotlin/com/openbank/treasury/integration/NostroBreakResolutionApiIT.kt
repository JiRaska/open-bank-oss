// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.treasury.application.port.out.NostroBreakRepository
import com.openbank.treasury.domain.model.BreakChanges
import com.openbank.treasury.nostro.NostroFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0315 D7 four-eyes manual break resolution over real HTTP and real Postgres: the routes are
 * registered (#3371), V33's columns, CHECKs and append-only history exist, a proposed break stays
 * open, the proposer can never approve (not even holding both roles), and every refusal answers its
 * own status. Ordered because four-eyes needs two people and `@TestSecurity` fixes one identity per
 * method (the TreasuryDealApiIT shape).
 */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NostroBreakResolutionApiIT {

    @Inject
    lateinit var ledger: FakeLedgerRead

    @Inject
    lateinit var breakRepo: NostroBreakRepository

    @BeforeEach
    fun seedLedger() {
        ledger.reset()
        NostroFixtures.ledgerLines().forEach { ledger.lines += "1001" to it }
    }

    @Test
    @Order(1)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `1 - an approver proposes - the break stays open, bad requests are 400, an unknown break 404`() {
        proposed = statementBreak()
        unproposed = statementBreak()
        raced = statementBreak()
        reproposed = statementBreak()

        step(proposed, "", body = "{}").then().statusCode(400)
        step(proposed, "", body = null).then().statusCode(400)
        step(proposed, "", body = """{"reason":"   short   "}""").then().statusCode(400)
        step(proposed, "", key = null).then().statusCode(400)
        step(UUID.randomUUID().toString(), "").then().statusCode(404).body("error", equalTo("NOT_FOUND"))

        proposedAt = step(proposed, "").then().statusCode(200)
            .body("resolvedOn", nullValue())
            .body("resolutionKind", nullValue())
            .body("proposedBy", equalTo("anna.approver"))
            .body("resolutionReason", equalTo(REASON))
            .extract().path("proposedAt")
        racedAt = step(raced, "").then().statusCode(200).extract().path("proposedAt")
        firstReproposedAt = step(reproposed, "").then().statusCode(200).extract().path("proposedAt")
        // Still OPEN: in the default listing, and counted by the sweep's open query.
        given().`when`().get(BREAKS).then().statusCode(200)
            .body("breaks.find { it.breakId == '$proposed' }.proposedBy", equalTo("anna.approver"))
        assertThat(count("select count(*) from nostro_breaks where break_uuid = '$proposed' and resolved_on is null"))
            .isEqualTo(1)
        step(proposed, "").then().statusCode(409) // a second proposal while one is pending
    }

    @Test
    @Order(2)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `2 - an approver without the senior role cannot approve - 403`() {
        step(proposed, "/approve", body = approval(proposedAt)).then().statusCode(403)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER", "ROLE_TREASURY_SENIOR_APPROVER"])
    fun `3 - four-eyes - the proposer cannot approve, even holding both roles`() {
        step(
            proposed,
            "/approve",
            body = approval(proposedAt),
        ).then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
        assertThat(count("select count(*) from nostro_breaks where break_uuid = '$proposed' and resolved_on is null"))
            .isEqualTo(1)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "sara.senior", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `4 - a different senior approves - MANUAL, out of the open list, audited, again or unproposed is 409`() {
        step(unproposed, "/approve", body = approval(proposedAt)).then().statusCode(409)

        val key = UUID.randomUUID().toString()
        step(proposed, "/approve", body = approval(proposedAt), key = key).then().statusCode(200)
            .body("resolutionKind", equalTo("MANUAL"))
            .body("resolvedBy", equalTo("sara.senior"))
            .body("proposedBy", equalTo("anna.approver"))
            .body("resolvedOn", notNullValue())
        step(proposed, "/approve", body = approval(proposedAt), key = key).then().statusCode(200) // replay
        step(proposed, "/approve", body = approval(proposedAt)).then().statusCode(409)

        val open: List<String> = given().`when`().get(BREAKS).then().statusCode(200).extract().path("breaks.breakId")
        assertThat(open).doesNotContain(proposed)
        assertThat(
            count(
                "select count(*) from nostro_break_resolution_history where break_uuid = '$proposed' and " +
                    "((step = 'PROPOSED' and actor_id = 'anna.approver') or (step = 'APPROVED' and actor_id = 'sara.senior'))",
            ),
        ).isEqualTo(2)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "ben.both", roles = ["ROLE_TREASURY_APPROVER", "ROLE_TREASURY_SENIOR_APPROVER"])
    fun `4a - another person rejects a proposal and proposes a different reason`() {
        step(reproposed, "/reject", body = """{"reason":"Not explained yet, ask again."}""").then().statusCode(200)
            .body("proposedBy", nullValue())
        step(reproposed, "", body = """{"reason":"A different reason entirely, re-investigated."}""")
            .then().statusCode(200).body("proposedBy", equalTo("ben.both"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "sara.senior", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `4b - an approval naming the proposal it read is refused once that proposal was replaced`() {
        step(reproposed, "/approve", body = approval(firstReproposedAt)).then().statusCode(409)
        assertThat(count("select count(*) from nostro_breaks where break_uuid = '$reproposed' and resolved_on is null"))
            .isEqualTo(1)
    }

    /**
     * The sweep reads a break, an approval commits, then the sweep writes what it computed from its
     * read. Without the version compare-and-set the stale write would turn the MANUAL resolution
     * into MATCHED with no approver while the history still said APPROVED.
     */
    @Test
    @Order(7)
    @TestSecurity(user = "sara.senior", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `4c - a sweep computed before an approval committed cannot overwrite it`() {
        val id = UUID.fromString(raced)
        val staleRead = onContext { breakRepo.find(id) }!!
        assertThat(staleRead.resolutionProposed).isTrue()

        step(raced, "/approve", body = approval(racedAt)).then().statusCode(200)
        onContext {
            breakRepo.apply(BreakChanges(emptyList(), listOf(staleRead.matched(LocalDate.now())), emptyList()))
        }

        assertThat(
            count(
                "select count(*) from nostro_breaks where break_uuid = '$raced' and resolution_kind = 'MANUAL' " +
                    "and resolved_by = 'sara.senior' and proposed_by = 'anna.approver'",
            ),
        ).isEqualTo(1)
    }

    /** The database repeats four-eyes (nostro_breaks_four_eyes): proposer = approver is refused even past the domain. */
    @Test
    @Order(8)
    @TestSecurity(user = "sara.senior", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `4d - the database refuses a manual resolution approved by its proposer`() {
        val e = org.junit.jupiter.api.assertThrows<SQLException> {
            jdbc { c ->
                c.createStatement().use {
                    it.executeUpdate(
                        "update nostro_breaks set proposed_by = 'x.person', proposed_at = now(), " +
                            "resolution_reason = 'self approved reason', resolved_on = first_seen_on, " +
                            "resolution_kind = 'MANUAL', resolved_by = 'x.person', resolved_at = now() " +
                            "where break_uuid = '$unproposed'",
                    )
                }
            }
        }
        assertThat(e.sqlState).isEqualTo("23514")
        assertThat(e.message).contains("nostro_breaks_four_eyes")
    }

    @Test
    @Order(9)
    @TestSecurity(user = "agent:treasury-dealing-assistant", roles = ["ROLE_TREASURY_APPROVER"])
    fun `5 - an AI agent principal is refused even with the role`() {
        step(unproposed, "").then().statusCode(403)
        assertThat(count("select count(*) from nostro_breaks where break_uuid = '$unproposed' and proposed_by is null"))
            .isEqualTo(1)
    }

    /** Uploads a fresh statement (the upload records its breaks) and returns its STATEMENT-side break id. */
    private fun statementBreak(): String {
        val xml = String(NostroFixtures.xml())
            .replace("SYNTH-STMT-20260925-CZK", "SYNTH-RES-${UUID.randomUUID()}".take(40))
        val stmt: String = given().contentType(
            "application/xml",
        ).header("Idempotency-Key", UUID.randomUUID().toString())
            .body(xml.toByteArray()).`when`().post("/api/v1/treasury/nostro/statements")
            .then().statusCode(201).extract().path("id")
        return given().`when`().get(BREAKS).then().statusCode(200)
            .extract().path("breaks.find { it.statementUuid == '$stmt' && it.side == 'STATEMENT' }.breakId")
    }

    private fun step(
        breakId: String,
        suffix: String,
        body: String? = """{"reason":"$REASON"}""",
        key: String? = UUID.randomUUID().toString(),
    ) = given().contentType("application/json")
        .apply<RequestSpecification> { if (key != null) header("Idempotency-Key", key) }
        .apply<RequestSpecification> { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury/nostro/${NostroFixtures.IBAN}/breaks/$breakId/resolution$suffix")

    private fun approval(proposedAt: String) = """{"proposedAt":"$proposedAt"}"""

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    private fun count(sql: String): Int = jdbc { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
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
        const val REASON = "Investigated: synthetic demo item, no cash moved."
        val BREAKS = "/api/v1/treasury/nostro/${NostroFixtures.IBAN}/breaks"

        /** Shared across the ordered steps (one identity per method). */
        lateinit var proposed: String
        lateinit var unproposed: String
        lateinit var raced: String
        lateinit var reproposed: String
        lateinit var proposedAt: String
        lateinit var racedAt: String
        lateinit var firstReproposedAt: String
    }
}
