// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.it.PostgresTestResource
import com.openbank.treasury.nostro.NostroFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * Real HTTP, real Postgres, the ledger's READ API replaced by [FakeLedgerRead]. What only this
 * test shows: the routes are registered (#3371), V8 creates the tables the entities name, the
 * statement and its entries commit together, and the Idempotency-Key contract holds on the wire.
 */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class NostroReconciliationApiIT {

    @Inject
    lateinit var ledger: FakeLedgerRead

    @Inject
    lateinit var statements: BlindableStatementRepository

    @BeforeEach
    fun seedLedger() {
        ledger.reset()
        statements.reset()
        NostroFixtures.ledgerLines().forEach { ledger.lines += "1001" to it }
        ledger.balances[Triple("1001", "CZK", NostroFixtures.DATE.minusDays(1))] = BigDecimal("1000000.00")
        ledger.balances[Triple("1001", "CZK", NostroFixtures.DATE)] = BigDecimal("1149958.00")
    }

    private fun upload(xml: ByteArray, key: String? = UUID.randomUUID().toString()) = given()
        .contentType("application/xml")
        .apply { if (key != null) header("Idempotency-Key", key) }
        .body(xml)
        .`when`().post("/api/v1/treasury/nostro/statements")

    /** Each test uploads its own statement id, so tests do not depend on order. */
    private fun fixture(statementId: String): ByteArray =
        String(NostroFixtures.xml()).replace("SYNTH-STMT-20260925-CZK", statementId).toByteArray()

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `upload then reconcile - matches, unmatched on both sides, differences, nothing posted`() {
        val stmt = "SYNTH-IT-${UUID.randomUUID()}".take(40)
        val id: String = upload(fixture(stmt)).then().statusCode(201)
            .body("glCode", equalTo("1001"))
            .body("entryCount", equalTo(3))
            .body("uploadedBy", equalTo("anna.approver"))
            .extract().path("id")

        given().`when`().get("/api/v1/treasury/nostro/statements/$id/reconciliation")
            .then().statusCode(200)
            .body("statementId", equalTo(stmt))
            .body("matches", hasSize<Any>(2))
            .body("unmatchedStatementEntries", hasSize<Any>(1))
            .body("unmatchedStatementEntries[0].reference", equalTo("SYNTH-SVCR-0003"))
            .body("unmatchedLedgerLines", hasSize<Any>(1))
            .body("openingDifference", equalTo(0.0f))
            .body("closingDifference", equalTo(5042.0f))
            .body("reconciled", equalTo(false))

        assertThat(
            ledger.queries,
        ).contains("lines:1001:2026-09-25..2026-09-25", "balance:1001:CZK:2026-09-24", "balance:1001:CZK:2026-09-25")
        assertThat(entryRows(id)).isEqualTo(3)
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `NEGATIVE - a tampered ledger amount leaves the item unmatched on both sides`() {
        ledger.lines.clear()
        NostroFixtures.ledgerLines(inboundAmount = "250000.01").forEach { ledger.lines += "1001" to it }
        val id: String = upload(fixture("SYNTH-IT-TAMPER-${UUID.randomUUID()}".take(40)))
            .then().statusCode(201).extract().path("id")

        given().`when`().get("/api/v1/treasury/nostro/statements/$id/reconciliation")
            .then().statusCode(200)
            .body("matches", hasSize<Any>(1))
            .body("unmatchedStatementEntries", hasSize<Any>(2))
            .body("unmatchedLedgerLines", hasSize<Any>(2))
            .body("reconciled", equalTo(false))
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `Idempotency-Key - absent is 400, replay returns the original, reuse for other bytes is 409`() {
        val xml = fixture("SYNTH-IT-IDEM-${UUID.randomUUID()}".take(40))
        upload(xml, key = null).then().statusCode(400)

        val key = UUID.randomUUID().toString()
        val first: String = upload(xml, key).then().statusCode(201).extract().path("id")
        val replay: String = upload(xml, key).then().statusCode(201).extract().path("id")
        assertThat(replay).isEqualTo(first)

        upload(fixture("SYNTH-IT-OTHER-${UUID.randomUUID()}".take(40)), key).then().statusCode(409)
        // Same statement under a fresh key: already uploaded -> 409, not a second copy.
        upload(xml).then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `a lost insert race answers as the pre-check would - replay or 409, never a 500`() {
        val xml = fixture("SYNTH-IT-RACE-${UUID.randomUUID()}".take(40))
        val key = UUID.randomUUID().toString()
        val first: String = upload(xml, key).then().statusCode(201).extract().path("id")

        // The pre-check sees nothing, as it would for a concurrent upload that has not committed
        // yet — so the INSERT itself hits the unique constraint.
        statements.blindNextPreCheck()
        val replay: String = upload(xml, key).then().statusCode(201).extract().path("id")
        assertThat(replay).isEqualTo(first)

        statements.blindNextPreCheck()
        upload(xml, UUID.randomUUID().toString()).then().statusCode(409)
        assertThat(entryRows(first)).isEqualTo(3)
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `a EUR statement is reconciled on native EUR ledger balances`() {
        ledger.lines.clear()
        NostroFixtures.eurLedgerLines().forEach { ledger.lines += "1002" to it }
        ledger.balances[Triple("1002", "EUR", LocalDate.parse("2026-09-23"))] = BigDecimal("50000.00")
        ledger.balances[Triple("1002", "EUR", LocalDate.parse("2026-09-25"))] = BigDecimal("57500.00")
        val id: String = upload(NostroFixtures.eurXml("SYNTH-IT-EUR-${UUID.randomUUID()}".take(40)))
            .then().statusCode(201).body("glCode", equalTo("1002")).extract().path("id")
        // The upload already reconciled once to record breaks (ADR-0315 D7); this assertion is about
        // what ONE reconciliation reads, so it starts from the read below.
        ledger.queries.clear()

        given().`when`().get("/api/v1/treasury/nostro/statements/$id/reconciliation")
            .then().statusCode(200)
            .body("matches", hasSize<Any>(2))
            .body("ledgerOpeningBalance", equalTo(50000.00f))
            .body("ledgerClosingBalance", equalTo(57500.00f))
            .body("openingDifference", equalTo(0.00f))
            .body("closingDifference", equalTo(0.00f))
            .body("balanceNotStated", nullValue())
            .body("reconciled", equalTo(true))
        assertThat(ledger.queries).containsExactly(
            "lines:1002:2026-09-24..2026-09-25",
            "balance:1002:EUR:2026-09-23",
            "balance:1002:EUR:2026-09-25",
        )
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `a GL the ledger does not hold leaves balances unstated with the reason - reconciled is null`() {
        ledger.lines.clear()
        NostroFixtures.eurLedgerLines().forEach { ledger.lines += "1002" to it }
        ledger.unknownAccounts += "1002"
        val id: String = upload(NostroFixtures.eurXml("SYNTH-IT-EUR-${UUID.randomUUID()}".take(40)))
            .then().statusCode(201).extract().path("id")

        given().`when`().get("/api/v1/treasury/nostro/statements/$id/reconciliation")
            .then().statusCode(200)
            .body("ledgerOpeningBalance", nullValue())
            .body("closingDifference", nullValue())
            .body("balanceNotStated", containsString("does not hold GL account 1002"))
            .body("reconciled", nullValue())
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `a ledger that cannot answer the balance read is a 502, never balances blanked with a false reason`() {
        ledger.unavailableAccounts += "1001"
        val id: String = upload(fixture("SYNTH-IT-502-${UUID.randomUUID()}".take(40)))
            .then().statusCode(201).extract().path("id")

        given().`when`().get("/api/v1/treasury/nostro/statements/$id/reconciliation")
            .then().statusCode(502)
            .body("error", equalTo("LEDGER_UNAVAILABLE"))
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `an amount beyond the stored scale is a 400, not a statement that cannot be reloaded`() {
        val fine = String(fixture("SYNTH-IT-SCALE-${UUID.randomUUID()}".take(40)))
        val tooFine = fine.replace(">5000.00<", ">5000.00001<").replace(">1155000.00<", ">1155000.00001<")
        upload(tooFine.toByteArray()).then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `an unconfigured account, a malformed body and an unknown id are refused`() {
        val foreign = String(fixture("SYNTH-IT-FOREIGN")).replace(
            "CZ12 9999 0000 0000 0000 1001",
            "CZ37 9998 0000 0000 0000 1001",
        ).toByteArray()
        upload(foreign).then().statusCode(400)
        upload("<Document/>".toByteArray()).then().statusCode(400)
        given().`when`().get("/api/v1/treasury/nostro/statements/${UUID.randomUUID()}/reconciliation")
            .then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `a dealer may not upload a statement`() {
        upload(fixture("SYNTH-IT-DEALER")).then().statusCode(403)
    }

    /**
     * A statement stored under #11052's rules may carry an entry dated after its CLBD date. The
     * period check is an UPLOAD rule: reading such a row must still reconcile (200), with the entry
     * unmatched and flagged in outOfPeriodEntries — never a 400/500 on a statement already accepted.
     */
    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `a stored statement with an entry after its closing date still reconciles, the entry flagged`() {
        val id: String = upload(fixture("SYNTH-IT-LEGACY-${UUID.randomUUID()}".take(40)))
            .then().statusCode(201).extract().path("id")
        // What a pre-V8-rule upload could have stored: entry 1 booked the day after CLBD.
        jdbc { c ->
            c.prepareStatement(
                "update nostro_statement_entries set booking_date = ? where statement_uuid = ?::uuid and sequence = 1",
            ).use { ps ->
                ps.setObject(1, NostroFixtures.DATE.plusDays(1))
                ps.setString(2, id)
                check(ps.executeUpdate() == 1)
            }
        }

        given().`when`().get("/api/v1/treasury/nostro/statements/$id/reconciliation")
            .then().statusCode(200)
            .body("outOfPeriodEntries", hasSize<Any>(1))
            .body("outOfPeriodEntries[0].sequence", equalTo(1))
            .body("outOfPeriodEntries[0].bookingDate", equalTo(NostroFixtures.DATE.plusDays(1).toString()))
            .body("unmatchedStatementEntries.sequence", org.hamcrest.Matchers.hasItem(1))
            .body("reconciled", equalTo(false))
    }

    private fun uploadMt940(text: ByteArray, key: String? = UUID.randomUUID().toString()) = given()
        .contentType("text/plain")
        .apply { if (key != null) header("Idempotency-Key", key) }
        .body(text)
        .`when`().post("/api/v1/treasury/nostro/statements/mt940")

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `MT940 upload is stored and reconciled like camt053, and its breaks are listed with age`() {
        val ref = "SYNTH-940-${UUID.randomUUID()}".take(30)
        val id: String = uploadMt940(NostroFixtures.mt940(ref)).then().statusCode(201)
            .body("statementId", equalTo("$ref/00268/001"))
            .body("glCode", equalTo("1001"))
            .body("entryCount", equalTo(3))
            .extract().path("id")

        given().`when`().get("/api/v1/treasury/nostro/statements/$id/reconciliation")
            .then().statusCode(200)
            .body("matches", hasSize<Any>(2))
            .body("unmatchedStatementEntries[0].reference", equalTo("SYNTH-SVCR-0003"))

        // Recorded at upload (best effort), not only at the next hourly sweep.
        given().`when`().get("/api/v1/treasury/nostro/${NostroFixtures.IBAN}/breaks")
            .then().statusCode(200)
            .body("iban", equalTo(NostroFixtures.IBAN))
            .body("alertAgeDays", equalTo(3))
            .body("breaks.findAll { it.statementUuid == '$id' }", hasSize<Any>(2))
            .body("breaks.find { it.statementUuid == '$id' && it.side == 'STATEMENT' }.amount", equalTo(5000.0f))
            .body("breaks.find { it.statementUuid == '$id' && it.side == 'STATEMENT' }.ourSide", equalTo("DEBIT"))
            .body("breaks.find { it.statementUuid == '$id' && it.side == 'LEDGER' }.amount", equalTo(42.0f))
            .body("breaks.find { it.statementUuid == '$id' }.ageBusinessDays", equalTo(0))
            .body("breaks.find { it.statementUuid == '$id' }.aged", equalTo(false))
        assertThat(
            jdbc { c ->
                count(c, "select count(*) from nostro_breaks where statement_uuid = '$id'")
            },
        ).isEqualTo(2)

        // Reconciling again (the sweep does exactly this) opens nothing new.
        given().`when`().get("/api/v1/treasury/nostro/statements/$id/reconciliation").then().statusCode(200)
        uploadMt940(NostroFixtures.mt940(ref), key = UUID.randomUUID().toString()).then().statusCode(409)
        assertThat(
            jdbc { c ->
                count(c, "select count(*) from nostro_breaks where statement_uuid = '$id'")
            },
        ).isEqualTo(2)
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `a malformed MT940 is a 400 and an unconfigured account's breaks are a 404`() {
        uploadMt940(":20:X\n:25:nope\n".toByteArray()).then().statusCode(400)
        uploadMt940(
            String(NostroFixtures.mt940()).replace(":62F:C260925CZK1155000,00", ":62F:C260925CZK1,00").toByteArray(),
        )
            .then().statusCode(400).body("message", containsString("does not foot"))
        uploadMt940(NostroFixtures.mt940(), key = null).then().statusCode(400)
        given().`when`().get("/api/v1/treasury/nostro/CZ0000000000000000000000/breaks").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `a dealer may read breaks but not upload an MT940`() {
        uploadMt940(NostroFixtures.mt940()).then().statusCode(403)
        given().`when`().get("/api/v1/treasury/nostro/${NostroFixtures.IBAN}/breaks").then().statusCode(200)
    }

    private fun count(c: java.sql.Connection, sql: String): Int = c.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getInt(1)
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

    private fun entryRows(id: String): Int {
        val cfg = ConfigProvider.getConfig()
        DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            c.prepareStatement(
                "select count(*) from nostro_statement_entries where statement_uuid = ?::uuid",
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }
}
