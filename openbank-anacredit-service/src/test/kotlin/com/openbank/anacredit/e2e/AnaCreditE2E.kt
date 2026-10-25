// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 License.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.util.Properties
import java.util.UUID
import javax.sql.DataSource

/**
 * The AnaCredit read/derive feed (ADR-0037) over the real wire: caller → endpoint → real Postgres
 * (`credit_exposures`, `loan_stage_projection`) and — for the IFRS 9 stage side — over the real
 * broker the module's global [com.openbank.anacredit.it.PostgresRedpandaTestResource] boots.
 *
 * Journeys mint their OWN debtors/instruments/loans, so they are order-independent: return-rendering
 * assertions filter the response to the journey's own debtors instead of counting globals — the
 * monthly return is built over the whole feed by design, and the €25 000 threshold is a per-debtor
 * aggregation across ALL the debtor's instruments, which is exactly the regulatory behaviour under
 * test.
 *
 * The durable facts are asserted over plain JDBC: the exposure row (upsert semantics included),
 * the loan-stage projection written by the Kafka consumer, and the rendered dataset rows.
 */
@QuarkusTest
class AnaCreditE2E {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `registering an exposure persists the durable row`() {
        val instrument = uniq("INSTR")
        val registered = postExposure(exposureBody(instrument, uniq("DEBTOR")), 201)

        assertThat(registered.get("instrumentId").asText()).isEqualTo(instrument)
        // Off-balance = undrawn commitment: 100 000 committed − 80 000 drawn.
        assertThat(registered.get("offBalanceSheetAmount").decimalValue())
            .isEqualByComparingTo(BigDecimal("20000"))

        val row = exposureRows(instrument).single()
        assertThat(row.debtorType).isEqualTo("LEGAL_ENTITY")
        assertThat(row.instrumentType).isEqualTo("LOAN")
        assertThat(row.currency).isEqualTo("CZK")
        assertThat(row.committedAmount.stripTrailingZeros().toPlainString()).isEqualTo("100000")
        assertThat(row.drawnAmount.stripTrailingZeros().toPlainString()).isEqualTo("80000")
        assertThat(row.committedAmountEur.stripTrailingZeros().toPlainString()).isEqualTo("4000")
        assertThat(row.defaulted).isFalse()
    }

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `the feed upserts on the durable instrument id`() {
        val instrument = uniq("INSTR")
        postExposure(exposureBody(instrument, uniq("DEBTOR")), 201)
        val amended = postExposure(
            exposureBody(instrument, uniq("DEBTOR")).put("drawnAmount", 90000),
            201,
        )
        assertThat(amended.get("drawnAmount").decimalValue()).isEqualByComparingTo(BigDecimal("90000"))

        // One row, the amended values — never a duplicate mint.
        val rows = exposureRows(instrument)
        assertThat(rows).hasSize(1)
        assertThat(rows[0].drawnAmount.stripTrailingZeros().toPlainString()).isEqualTo("90000")

        val listed = getJson("/api/v1/anacredit/exposures", 200)
        assertThat(listed.filter { it.get("instrumentId").asText() == instrument }).hasSize(1)
    }

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `registration validation refuses malformed payloads without persisting anything`() {
        val before = exposureCount()

        postText("/api/v1/anacredit/exposures", """{"instrumentId":"x"}""", 400)
        postText(
            "/api/v1/anacredit/exposures",
            exposureBody(uniq("INSTR"), uniq("DEBTOR")).put("debtorType", "GOVERNMENT").toString(),
            400,
        )
        postText("/api/v1/anacredit/exposures", "{not json", 400)

        assertThat(exposureCount()).isEqualTo(before)
    }

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `the monthly return aggregates the reporting threshold per debtor`() {
        val debtorA = uniq("DEBTOR")
        val debtorB = uniq("DEBTOR")
        // Debtor A: two instruments of €15 000 each — NEITHER reaches €25 000 alone; the debtor's
        // aggregated €30 000 commitment makes BOTH reportable. Debtor B: €10 000 — excluded.
        val a1 = registerAndGetId(exposureBody(uniq("INSTR"), debtorA, committedEur = "15000"))
        val a2 = registerAndGetId(
            exposureBody(uniq("INSTR"), debtorA, committedEur = "15000").put("instrumentType", "REVOLVING_CREDIT"),
        )
        val b1 = registerAndGetId(exposureBody(uniq("INSTR"), debtorB, committedEur = "10000"))

        val ret = getJson("/api/v1/anacredit/returns/2026-03-31", 200)
        assertThat(ret.get("referenceDate").asText()).isEqualTo("2026-03-31")

        val records = ret.get("records")
        val reportableIds = records.map { it.get("instrumentId").asText() }
        assertThat(reportableIds).contains(a1, a2)

        val exclusion = ret.get("exclusions").firstOrNull { it.get("instrumentId").asText() == b1 }
        assertThat(exclusion).isNotNull()
        assertThat(exclusion!!.get("reason").asText()).isEqualTo("BELOW_THRESHOLD")
    }

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `households and zero exposure instruments carry stable exclusion reasons`() {
        val personDebtor = uniq("DEBTOR")
        val zeroDebtor = uniq("DEBTOR")
        val household = registerAndGetId(
            exposureBody(uniq("INSTR"), personDebtor, committedEur = "90000")
                .put("debtorType", "NATURAL_PERSON"),
        )
        val zeroed = registerAndGetId(
            exposureBody(uniq("INSTR"), zeroDebtor).put("committedAmount", 0).put("drawnAmount", 0)
                .put("committedAmountEur", 0),
        )

        val ret = getJson("/api/v1/anacredit/returns/2026-04-30", 200)
        val reasons = ret.get("exclusions").associate { it.get("instrumentId").asText() to it.get("reason").asText() }
        assertThat(reasons[household]).isEqualTo("HOUSEHOLD_OUT_OF_SCOPE")
        assertThat(reasons[zeroed]).isEqualTo("NO_EXPOSURE")
    }

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `default status and arrears map onto the dataset row`() {
        val instrument = registerAndGetId(
            exposureBody(uniq("INSTR"), uniq("DEBTOR"), committedEur = "30000")
                .put("defaulted", true).put("arrearsAmount", 500),
        )

        val ret = getJson("/api/v1/anacredit/returns/2026-05-31", 200)
        val record = ret.get("records").first { it.get("instrumentId").asText() == instrument }
        assertThat(record.get("defaultStatus").asText()).isEqualTo("DEFAULT")
        assertThat(record.get("arrearsAmount").decimalValue()).isEqualByComparingTo(BigDecimal("500"))
        assertThat(record.get("referenceDate").asText()).isEqualTo("2026-05-31")
    }

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `a loan stage event becomes a durable projection and stale events never regress it`() {
        val loanId = UUID.randomUUID()
        produceLoanStageEvent(loanId, newStage = "STAGE_2", daysPastDue = 35, asOf = "2026-06-10T00:00:00Z")

        // The consumer crossed the real broker and the projection is a durable row.
        val projection = awaitLoanStageRow(loanId)!!
        assertThat(projection.stage).isEqualTo("STAGE_2")
        assertThat(projection.daysPastDue).isEqualTo(35)
        assertThat(projection.eventTimestamp).startsWith("2026-06-10")

        // Ordering safety: an OLDER event must not regress the projection (applyIfNewer).
        produceLoanStageEvent(loanId, newStage = "STAGE_1", daysPastDue = 5, asOf = "2026-05-01T00:00:00Z")
        Thread.sleep(2_000)
        val afterStale = loanStageRow(loanId)!!
        assertThat(afterStale.stage).isEqualTo("STAGE_2")
        assertThat(afterStale.daysPastDue).isEqualTo(35)
    }

    @Test
    @TestSecurity(user = "e2e-auditor", roles = ["ROLE_AUDITOR"])
    fun `auditor reads the return and bad reference dates are a client error`() {
        given().`when`().get("/api/v1/anacredit/returns/not-a-date").then().statusCode(400)

        val ret = getJson("/api/v1/anacredit/returns/2026-06-30", 200)
        assertThat(ret.get("referenceDate").asText()).isEqualTo("2026-06-30")
        assertThat(ret.has("records")).isTrue()
    }

    @Test
    fun `the feed is anonymous-refused and customer-denied`() {
        given().`when`().get("/api/v1/anacredit/exposures").then().statusCode(401)
        given().`when`().get("/api/v1/anacredit/returns/2026-06-30").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "e2e-customer", roles = ["ROLE_CUSTOMER"])
    fun `a customer role is outside the regulatory boundary`() {
        given().`when`().get("/api/v1/anacredit/exposures").then().statusCode(403)
        given().`when`().get("/api/v1/anacredit/returns/2026-06-30").then().statusCode(403)
    }

    private fun registerAndGetId(body: ObjectNode): String = postExposure(body, 201).get("instrumentId").asText()

    private fun exposureBody(instrumentId: String, debtorId: String, committedEur: String = "4000"): ObjectNode =
        objectMapper.createObjectNode()
            .put("instrumentId", instrumentId)
            .put("debtorId", debtorId)
            .put("debtorType", "LEGAL_ENTITY")
            .put("instrumentType", "LOAN")
            .put("currency", "CZK")
            .put("committedAmount", 100000)
            .put("drawnAmount", 80000)
            .put("committedAmountEur", BigDecimal(committedEur))
            .put("arrearsAmount", 0)
            .put("defaulted", false)
            .put("originationDate", "2026-01-15")

    private fun postExposure(body: ObjectNode, expectedStatus: Int): JsonNode =
        objectMapper.readTree(postText("/api/v1/anacredit/exposures", body.toString(), expectedStatus))

    private fun postText(path: String, body: String, expectedStatus: Int): String =
        given().contentType("application/json").body(body)
            .`when`().post(path)
            .then().statusCode(expectedStatus)
            .extract().response().asString()

    private fun getJson(path: String, expectedStatus: Int): JsonNode = objectMapper.readTree(
        given().`when`().get(path)
            .then().statusCode(expectedStatus)
            .extract().response().asString(),
    )

    private fun produceLoanStageEvent(loanId: UUID, newStage: String, daysPastDue: Int, asOf: String) {
        val props = Properties()
        props[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] =
            com.openbank.anacredit.it.PostgresRedpandaTestResource.lastBootstrapServers
        props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.name
        props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.name
        KafkaProducer<String, String>(props).use { producer ->
            producer.send(
                ProducerRecord(
                    "openbank.lending.events",
                    loanId.toString(),
                    """{"eventType":"loan.stage_changed","loanId":"$loanId","newStage":"$newStage","daysPastDue":$daysPastDue,"asOf":"$asOf"}""",
                ),
            ).get()
        }
    }

    private fun awaitLoanStageRow(loanId: UUID): LoanStageRow? {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline) {
            val row = loanStageRow(loanId)
            if (row != null) {
                return row
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun loanStageRow(loanId: UUID): LoanStageRow? = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT stage, days_past_due, event_timestamp FROM loan_stage_projection WHERE loan_id = ?",
        ).use { ps ->
            ps.setObject(1, loanId)
            val rs = ps.executeQuery()
            if (!rs.next()) {
                null
            } else {
                LoanStageRow(
                    stage = rs.getString("stage"),
                    daysPastDue = rs.getInt("days_past_due"),
                    eventTimestamp = rs.getTimestamp("event_timestamp").toInstant().toString(),
                )
            }
        }
    }

    private fun exposureRows(instrumentId: String): List<ExposureRow> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT debtor_type, instrument_type, currency, committed_amount, drawn_amount, " +
                "committed_amount_eur, defaulted FROM credit_exposures WHERE instrument_id = ?",
        ).use { ps ->
            ps.setString(1, instrumentId)
            val rs = ps.executeQuery()
            val rows = mutableListOf<ExposureRow>()
            while (rs.next()) {
                rows += ExposureRow(
                    debtorType = rs.getString("debtor_type"),
                    instrumentType = rs.getString("instrument_type"),
                    currency = rs.getString("currency"),
                    committedAmount = rs.getBigDecimal("committed_amount"),
                    drawnAmount = rs.getBigDecimal("drawn_amount"),
                    committedAmountEur = rs.getBigDecimal("committed_amount_eur"),
                    defaulted = rs.getBoolean("defaulted"),
                )
            }
            rows
        }
    }

    private fun exposureCount(): Int = dataSource.connection.use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM credit_exposures").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun uniq(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "_").uppercase().take(24)}"

    private data class ExposureRow(
        val debtorType: String,
        val instrumentType: String,
        val currency: String,
        val committedAmount: BigDecimal,
        val drawnAmount: BigDecimal,
        val committedAmountEur: BigDecimal,
        val defaulted: Boolean,
    )

    private data class LoanStageRow(val stage: String, val daysPastDue: Int, val eventTimestamp: String)
}
