// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Golden month (#12425): March 2009 of pension activity seeded straight into the real schema, then
 * read back through the real HTTP route and the real SQL. 2009 predates every date any other IT
 * in this module seeds (the earliest is a 2010 start), so every figure below is exact.
 *
 * Seeded:
 *  A  DPS, born 1945-06-01, started 2008-05-01, ACTIVE            -> in force, 60-64
 *  B  DPS, born 1975-01-10, started 2009-03-05, ACTIVE            -> in force, new, 18-34
 *  C  DIP, born 1960-07-07, PAID_OUT on 2009-03-20                -> exited (lump sum)
 *  D  DPS, born 1935-02-02, TERMINATING since 2009-04-15          -> in force, status changed after
 *  E  DPS, started 2009-04-02                                     -> not yet in force
 *  F  DPS, TRANSFERRED_OUT on 2009-03-12                          -> exited (transfer out)
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ParticipantReportingApiIT {

    companion object {
        private val ids = mutableMapOf<String, UUID>()
        private var seeded = false
        private const val PATH = "/api/v1/pension/reporting/participant-aggregates"
    }

    private fun <T> jdbc(block: (Connection) -> T): T {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private fun Connection.exec(sql: String, vararg args: Any?) = prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeUpdate()
    }

    @Suppress("LongParameterList")
    private fun Connection.contract(
        key: String,
        line: String,
        born: String,
        status: String,
        start: String,
        updated: String,
    ) {
        val id = UUID.randomUUID().also { ids[key] = it }
        exec(
            "INSERT INTO pension_contracts (id, contract_id, participant_party_id, product_line, jurisdiction, " +
                "pack_version, " +
                "provider_entity_id, provider_type, participant_birth_date, status, contribution_amount, " +
                "contribution_currency, contribution_frequency, start_date, created_at, updated_at) VALUES " +
                "(1000000000000 + (random() * 1000000000)::bigint, ?, ?, ?, 'CZ', 2, ?, 'PENSION_COMPANY', " +
                "?::date, ?, " +
                "1000, 'CZK', 'MONTHLY', ?::date, ?::timestamptz, ?::timestamptz)",
            id, UUID.randomUUID(), line, UUID.randomUUID(), born, status, start, "${start}T08:00:00Z", updated,
        )
    }

    private fun Connection.contribution(key: String, source: String, amount: String, valueDate: String) = exec(
        "INSERT INTO pension_contributions (id, contract_id, payment_id, source, channel, amount, currency, " +
            "value_date, " +
            "employer_party_id, received_at) " +
            "VALUES (?, ?, ?, ?, 'BANK_TRANSFER', ?::numeric, 'CZK', ?::date, ?, now())",
        UUID.randomUUID(),
        ids.getValue(key),
        "rep-${UUID.randomUUID()}",
        source,
        amount,
        valueDate,
        if (source == "EMPLOYER") UUID.randomUUID() else null,
    )

    private fun Connection.instruction(key: String, purpose: String, amount: String, status: String, at: String) = exec(
        "INSERT INTO pension_payment_instructions (idempotency_key, contract_id, purpose, amount, currency, " +
            "creditor_iban, " +
            "status, payment_ref, created_at, updated_at) VALUES (?, ?, ?, ?::numeric, 'CZK', " +
            "'CZ6508000000192000145399', " +
            "?, ?, ?::timestamptz, ?::timestamptz)",
        "rep-${UUID.randomUUID()}", ids.getValue(key), purpose, amount, status,
        if (status == "PENDING") null else "ref-${UUID.randomUUID()}", at, at,
    )

    private fun Connection.transfer(key: String, direction: String, net: String, at: String) = exec(
        "INSERT INTO pension_transfer_requests (id, transfer_id, direction, contract_id, party_id, status, payload, " +
            "version, created_at, updated_at) VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, ?, ?, ?, " +
            "'COMPLETED', ?, 1, ?::timestamptz, ?::timestamptz)",
        UUID.randomUUID(),
        direction,
        ids.getValue(key),
        UUID.randomUUID(),
        """{"netAmount":$net}""",
        at,
        at,
    )

    @BeforeEach
    fun seedMarch2009() {
        if (seeded) return
        jdbc { c ->
            c.contract("A", "DPS", "1945-06-01", "ACTIVE", "2008-05-01", "2008-05-01T08:00:00Z")
            c.contract("B", "DPS", "1975-01-10", "ACTIVE", "2009-03-05", "2009-03-05T08:00:00Z")
            c.contract("C", "DIP", "1960-07-07", "PAID_OUT", "2005-01-01", "2009-03-20T10:00:00Z")
            c.contract("D", "DPS", "1935-02-02", "TERMINATING", "2000-01-01", "2009-04-15T10:00:00Z")
            c.contract("E", "DPS", "1965-01-01", "ACTIVE", "2009-04-02", "2009-04-02T08:00:00Z")
            c.contract("F", "DPS", "1955-01-01", "TRANSFERRED_OUT", "2001-01-01", "2009-03-12T10:00:00Z")

            c.contribution("A", "PARTICIPANT", "1000", "2009-02-10")
            c.contribution("A", "PARTICIPANT", "1000", "2009-03-10")
            c.contribution("A", "EMPLOYER", "500", "2009-03-10")
            c.contribution("B", "PARTICIPANT", "300", "2009-03-15")
            c.contribution("A", "STATE", "230", "2009-03-25")
            c.contribution("B", "TRANSFER_IN", "50000", "2009-03-06")

            c.instruction("C", "LUMP_SUM", "120000", "SETTLED", "2009-03-20T11:00:00Z")
            c.instruction("C", "PAYOUT_WITHHOLDING", "18000", "SENT", "2009-03-20T11:00:00Z")
            c.instruction("D", "PHASED_WITHDRAWAL", "5000", "SENT", "2009-03-28T09:00:00Z")
            // Rejected by the bank: never counted as paid.
            c.instruction("D", "PHASED_WITHDRAWAL", "999", "REJECTED", "2009-03-29T09:00:00Z")
            // 1 April 00:30 UTC is April, whatever the session time zone.
            c.instruction("A", "EARLY_WITHDRAWAL", "777", "SENT", "2009-04-01T00:30:00Z")

            c.exec(
                "INSERT INTO pension_incentive_claims (id, contract_id, incentive_id, period, basis, claimed_amount, " +
                    "currency, " +
                    "status, created_at, updated_at) VALUES (?, ?, 'cz-state-contribution', '2009-03', 1000, 230, " +
                    "'CZK', " +
                    "'PENDING', now(), now())",
                UUID.randomUUID(),
                ids.getValue("A"),
            )
            c.exec(
                "INSERT INTO pension_incentive_ledger " +
                    "(id, contract_id, incentive_id, kind, amount, tax_year, period, " +
                    "occurred_at) " +
                    "VALUES (?, ?, 'cz-state-contribution', 'RECEIVED', 230, 2009, '2009-03', '2009-03-25T09:00:00Z')",
                UUID.randomUUID(),
                ids.getValue("A"),
            )

            c.transfer("B", "IN", "50000", "2009-03-06T09:00:00Z")
            c.transfer("F", "OUT", "80000", "2009-03-12T10:00:00Z")
        }
        seeded = true
    }

    @Test
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
    fun `march 2009 aggregates are exact, reconcile, and carry no participant identifier`() {
        val body = given().queryParam("periodStart", "2009-03-01").queryParam("periodEnd", "2009-03-31")
            .`when`().get(PATH).then().log().ifValidationFails().statusCode(200)
            .body("currency", equalTo("CZK"))
            .body("participants.inForce", equalTo(3))
            .body("participants.newInPeriod", equalTo(1))
            .body("participants.exitedInPeriod", equalTo(2))
            .body("participants.contributing", equalTo(2))
            .body("participants.pensioners", equalTo(1))
            .body("participants.byProductLine.DPS", equalTo(3))
            .body("participants.byAgeBand.'18-34'", equalTo(1))
            .body("participants.byAgeBand.'60-64'", equalTo(1))
            .body("participants.byAgeBand.'65+'", equalTo(1))
            .body("participants.byAgeBand.'35-49'", equalTo(0))
            .body("participants.byStatusAtPeriodEnd.ACTIVE", equalTo(2))
            .body("participants.byStatusAtPeriodEnd.CHANGED_AFTER_PERIOD_END", equalTo(1))
            .body("contributions.participant", equalTo(1300.0f))
            .body("contributions.employer", equalTo(500.0f))
            .body("contributions.state", equalTo(230.0f))
            .body("contributions.transferIn", equalTo(50000.0f))
            .body("contributions.total", equalTo(2030.0f))
            .body("contributionsYtd.participant", equalTo(2300.0f))
            .body("contributionsYtd.total", equalTo(3030.0f))
            .body("stateContributions.claimed", equalTo(230.0f))
            .body("stateContributions.received", equalTo(230.0f))
            .body("payouts.total", equalTo(125000.0f))
            .body("payouts.cases", equalTo(2))
            .body("payouts.taxWithheld", equalTo(18000.0f))
            .body("payouts.byForm.LUMP_SUM.amount", equalTo(120000.0f))
            .body("payouts.byForm.PHASED_WITHDRAWAL.amount", equalTo(5000.0f))
            .body("payouts.byForm.PHASED_WITHDRAWAL.count", equalTo(1))
            .body("transfers.inCount", equalTo(1))
            .body("transfers.inAmount", equalTo(50000.0f))
            .body("transfers.outCount", equalTo(1))
            .body("transfers.outAmount", equalTo(80000.0f))
            .extract().asString()
        ids.values.forEach { assertThat(body).doesNotContain(it.toString()) }
        // Reproducible: the same closed period answers byte for byte the same.
        val again = given().queryParam("periodStart", "2009-03-01").queryParam("periodEnd", "2009-03-31")
            .`when`().get(PATH).then().statusCode(200).extract().asString()
        assertThat(again).isEqualTo(body)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
    fun `the April instruction at 00h30 UTC lands in April, and absent parameters are a 400`() {
        given().queryParam("periodStart", "2009-04-01").queryParam("periodEnd", "2009-04-30")
            .`when`().get(PATH).then().statusCode(200)
            .body("payouts.byForm.EARLY_WITHDRAWAL.amount", equalTo(777.0f))
            .body("participants.newInPeriod", equalTo(1))
        given().queryParam("periodEnd", "2009-03-31").`when`().get(PATH).then().statusCode(400)
        given().queryParam("periodStart", "2009-03-31").queryParam("periodEnd", "2009-03-01")
            .`when`().get(PATH).then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "cust-1", roles = ["ROLE_CUSTOMER"])
    fun `a customer cannot reach the aggregates`() {
        given().queryParam("periodStart", "2009-03-01").queryParam("periodEnd", "2009-03-31")
            .`when`().get(PATH).then().statusCode(403)
    }
}
