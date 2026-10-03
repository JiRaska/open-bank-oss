// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.it.PostgresTestResource
import com.openbank.treasury.nostro.NostroFixtures
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * ADR-0315 D7 through the REAL `@Scheduled` sweep (a direct call would supply the Vert.x context
 * the scheduler does not have — root CLAUDE.md): a break back-dated past the threshold is alerted
 * exactly ONCE through the outbox, however many passes run, and the aged gauge reports it.
 */
@QuarkusTest
@TestProfile(NostroBreakSweepIT.SweepOn::class)
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class NostroBreakSweepIT {

    /** Literal values only: a profile loads in a different classloader from the test. */
    class SweepOn : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.treasury.nostro-breaks.enabled" to "true",
            "openbank.treasury.nostro-breaks.interval" to "1s",
            "openbank.treasury.nostro-breaks.initial-delay" to "1s",
            "openbank.treasury.nostro.break-alert-age-days" to "2",
            "openbank.treasury.nostro.break-alert-min-amount" to "1000",
        )
    }

    @Inject
    lateinit var ledger: FakeLedgerRead

    @Inject
    lateinit var registry: MeterRegistry

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `an aged break over the amount threshold is alerted once by the scheduler, a small one never`() {
        ledger.reset()
        NostroFixtures.ledgerLines().forEach { ledger.lines += "1001" to it }
        val xml = String(
            NostroFixtures.xml(),
        ).replace("SYNTH-STMT-20260925-CZK", "SYNTH-SWEEP-${UUID.randomUUID()}".take(40))
        val id: String = given().contentType("application/xml").header("Idempotency-Key", UUID.randomUUID().toString())
            .body(xml.toByteArray()).`when`().post("/api/v1/treasury/nostro/statements")
            .then().statusCode(201).extract().path("id")

        awaitCount("select count(*) from nostro_breaks where statement_uuid = '$id'", 2)
        // Back-date both breaks two weeks: the 5000 one is over 1000, the 42 fee is not.
        jdbc { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "update nostro_breaks set first_seen_on = first_seen_on - 14 where statement_uuid = '$id'",
                )
            }
        }

        val aged = "select count(*) from treasury_outbox o join nostro_breaks b on o.aggregate_id = b.break_uuid " +
            "where b.statement_uuid = '$id' and o.event_type = 'treasury.nostro.break-aged.v1'"
        awaitCount(aged, 1)
        Thread.sleep(PASSES_MS) // several more passes: still exactly one event
        assertThat(count(aged)).isEqualTo(1)
        assertThat(count("select count(*) from nostro_breaks where statement_uuid = '$id' and alerted_at is not null"))
            .isEqualTo(1)
        val gauge = registry.find("openbank.treasury.nostro.breaks.aged").gauge()
        assertThat(gauge).isNotNull
        assertThat(gauge!!.value()).isGreaterThanOrEqualTo(1.0)

        given().`when`().get("/api/v1/treasury/nostro/${NostroFixtures.IBAN}/breaks")
            .then().statusCode(200)
            .body("alertAgeDays", equalTo(2))
            .body("breaks.find { it.statementUuid == '$id' && it.side == 'STATEMENT' }.aged", equalTo(true))
            .body("breaks.find { it.statementUuid == '$id' && it.side == 'LEDGER' }.aged", equalTo(false))
    }

    private fun awaitCount(sql: String, expected: Int) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (count(sql) < expected) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $expected row(s): $sql" }
            Thread.sleep(POLL_MS)
        }
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

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 250L
        const val PASSES_MS = 3_500L
    }
}
