// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.e2e

import com.openbank.risk.domain.Fixtures
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The ADR-0314 snapshot lifecycle over the real HTTP surface: caller → endpoint → ledger's two
 * read views + lending's loan book (stood in for by [RiskDownstreamWireMockResource] over real
 * HTTP) → tie-out → sequenced run manifest + positions in real Postgres, asserted over plain JDBC.
 *
 * Every journey works on its own as-of day, so journeys never share stubs or rows and need no
 * ordering. The days are relative to the business date, so the suite does not rot as the calendar
 * moves.
 */
@QuarkusTest
@TestProfile(RiskE2EProfile::class)
class RiskSnapshotE2E : RiskE2EBase() {

    @BeforeEach
    fun resetDownstream() = RiskDownstreamWireMockResource.reset()

    @Test
    @TestSecurity(user = "risk-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `create builds a tied-out snapshot with a downstream fan-out proof`() {
        val asOf = LocalDate.now().minusDays(5).toString()
        stubTiedOutBook(asOf)

        val body = createSnapshot(asOf, 201)
        assertThat(body.get("status").asText()).isEqualTo("TIED_OUT")
        assertThat(body.get("positionCount").asInt()).isEqualTo(3)
        val runId = body.get("id").asText()

        // The durable fact, over plain JDBC: one run, tied out, attributed to the caller
        // (ADR-0314 §V5), with the positions the tie-out actually compared.
        val rows = runRows(asOf)
        assertThat(rows).hasSize(1)
        assertThat(rows[0].status).isEqualTo("TIED_OUT")
        assertThat(rows[0].positionCount).isEqualTo(3)
        assertThat(rows[0].mismatchCount).isZero()
        assertThat(rows[0].requestedBy).isEqualTo("risk-e2e-operator")
        assertThat(positionCount(runId)).isEqualTo(3)
        assertThat(mismatchRows(runId)).isEmpty()

        // The fan-out actually crossed the wire: both ledger views (trial balance pinned to
        // scope=ALL by the stub's query-param matcher) plus lending's loan book.
        assertThat(RiskDownstreamWireMockResource.requestsTo("/api/v1/journals/trial-balance")).isEqualTo(1)
        assertThat(RiskDownstreamWireMockResource.requestsTo("/api/v1/journals/sub-ledger-balances")).isEqualTo(1)
        assertThat(RiskDownstreamWireMockResource.requestsTo("/api/v1/lending/loan-book")).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "risk-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `replaying the same ledger answers 200 and never double-mints`() {
        val asOf = LocalDate.now().minusDays(4).toString()
        stubTiedOutBook(asOf)

        val first = createSnapshot(asOf, 201)
        val replay = createSnapshot(asOf, 200)
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText())
        assertThat(replay.get("status").asText()).isEqualTo("TIED_OUT")

        // The natural key (as_of, input_hash) is the replay guard: exactly one row, ever.
        assertThat(runRows(asOf)).hasSize(1)
        assertThat(RiskDownstreamWireMockResource.requestsTo("/api/v1/journals/trial-balance")).isEqualTo(2)
    }

    @Test
    @TestSecurity(user = "risk-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a moved ledger mints a new run, mismatches persist and positions stay gated 409`() {
        val asOf = LocalDate.now().minusDays(3).toString()
        stubTiedOutBook(asOf)
        val first = createSnapshot(asOf, 201)
        assertThat(first.get("status").asText()).isEqualTo("TIED_OUT")

        // After the close, the GL learns of a fee that never reached the sub-ledger: the deposit
        // control now credits 1600 while Alice+Bob still hold 1500. Same day, NEW knowledge — a
        // new run that must fail its tie-out, never silently reuse the tied-out one.
        RiskDownstreamWireMockResource.reset()
        RiskDownstreamWireMockResource.stubTrialBalance(
            asOf,
            TB_LINE_1001 + "," + tbLine("2100", "LIABILITY", "CZK", "0", "1600.00"),
        )
        RiskDownstreamWireMockResource.stubSubLedgerBalances(asOf, SUB_LEDGER)
        RiskDownstreamWireMockResource.stubLoanBook(asOf, "")

        val second = createSnapshot(asOf, 201)
        assertThat(second.get("status").asText()).isEqualTo("UNTIED")
        val runId = second.get("id").asText()
        assertThat(runId).isNotEqualTo(first.get("id").asText())

        val rows = runRows(asOf)
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.status }).containsExactlyInAnyOrder("TIED_OUT", "UNTIED")

        // The issued record is never edited: the first run still reads TIED_OUT; the break is a
        // persisted fact on the new manifest, readable over plain JDBC.
        val mismatches = mismatchRows(runId)
        assertThat(mismatches).hasSize(1)
        assertThat(mismatches[0].glAccountCode).isEqualTo("2100")
        assertThat(mismatches[0].ledgerNet).isEqualByComparingTo(BigDecimal("-1600"))
        assertThat(mismatches[0].positionsNet).isEqualByComparingTo(BigDecimal("-1500"))

        // The run manifest serves the mismatches; the derived reads refuse an untied run.
        val manifest = getJson("/api/v1/risk/snapshots/$runId", 200)
        assertThat(manifest.get("mismatches").size()).isEqualTo(1)
        getJson("/api/v1/risk/snapshots/$runId/positions", 409)
        getJson("/api/v1/risk/snapshots/$runId/instruments", 409)
    }

    @Test
    @TestSecurity(user = "risk-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `lending answering a different as-of fails the snapshot and persists nothing`() {
        val asOf = LocalDate.now().minusDays(2).toString()
        RiskDownstreamWireMockResource.stubTrialBalance(asOf, TB_LINE_1001 + "," + TB_LINE_2100)
        RiskDownstreamWireMockResource.stubSubLedgerBalances(asOf, SUB_LEDGER)
        // The loan book's own asOf disagrees with the requested date: the adapter refuses to
        // carry it rather than tie out against the wrong trial balance.
        RiskDownstreamWireMockResource.stubLoanBookMismatched(asOf, LocalDate.parse(asOf).minusDays(1).toString())

        // The adapter's presence check is an IllegalStateException, which libs-runtime surfaces
        // as 422 BUSINESS_RULE_VIOLATION — an honest "the provider answered inconsistently",
        // never a laundered 500 and never a persisted half-run.
        val body = createSnapshot(asOf, 422)
        assertThat(body.get("code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION")
        assertThat(body.get("message").asText()).contains("as of")
        assertThat(runRows(asOf)).isEmpty()
    }

    @Test
    @TestSecurity(user = "risk-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `validation refuses missing malformed and future as-of before any downstream call`() {
        val missing = createSnapshotRaw("{}", 400)
        assertThat(missing.get("message").asText()).contains("asOf")
        val malformed = createSnapshotRaw("""{"asOf":"not-a-date"}""", 400)
        assertThat(malformed.get("message").asText()).contains("YYYY-MM-DD")
        val future = createSnapshotRaw(
            """{"asOf":"${LocalDate.now(java.time.ZoneId.of("Europe/Prague")).plusDays(1)}"}""",
            400,
        )
        assertThat(future.get("message").asText()).contains("must not be after")

        // A day that has not closed cannot be tied out — and no refusal ever dialled downstream.
        assertThat(RiskDownstreamWireMockResource.requestsTo("/api/v1/journals/trial-balance")).isZero()
        assertThat(RiskDownstreamWireMockResource.requestsTo("/api/v1/lending/loan-book")).isZero()
    }

    @Test
    @TestSecurity(user = "risk-e2e-finance", roles = ["ROLE_FINANCE"])
    fun `finance reads the run manifest but never creates a snapshot`() {
        val asOf = LocalDate.now().minusDays(1).toString()
        createSnapshot(asOf, 403)
        getJson("/api/v1/risk/snapshots", 200)
        assertThat(RiskDownstreamWireMockResource.requestsTo("/api/v1/journals/trial-balance")).isZero()
    }

    @Test
    fun `anonymous caller is refused before any downstream call`() {
        val asOf = LocalDate.now().minusDays(1).toString()
        createSnapshotRaw("""{"asOf":"$asOf"}""", 401)
        getJson("/api/v1/risk/snapshots", 401)
        assertThat(RiskDownstreamWireMockResource.requestsTo("/api/v1/journals/trial-balance")).isZero()
    }

    @Test
    @TestSecurity(user = "risk-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a ledger outage fails the snapshot closed and persists nothing`() {
        val asOf = LocalDate.now().minusDays(6).toString()
        RiskDownstreamWireMockResource.stubTrialBalanceUnavailable(asOf)
        RiskDownstreamWireMockResource.stubSubLedgerBalances(asOf, SUB_LEDGER)
        RiskDownstreamWireMockResource.stubLoanBook(asOf, "")

        // The downstream 503 propagates through the REST client as a WebApplicationException,
        // which libs-runtime surfaces VERBATIM — an outage reads as "the platform is broken",
        // never laundered into a 4xx, and nothing is persisted.
        createSnapshot(asOf, 503)
        assertThat(runRows(asOf)).isEmpty()
    }

    private fun stubTiedOutBook(asOf: String) {
        RiskDownstreamWireMockResource.stubTrialBalance(asOf, TB_LINE_1001 + "," + TB_LINE_2100)
        RiskDownstreamWireMockResource.stubSubLedgerBalances(asOf, SUB_LEDGER)
        RiskDownstreamWireMockResource.stubLoanBook(asOf, "")
    }

    private companion object {
        val TB_LINE_1001 = tbLine("1001", "ASSET", "CZK", "1500.00", "0")
        val TB_LINE_2100 = tbLine("2100", "LIABILITY", "CZK", "0", "1500.00")
        val SUB_LEDGER =
            """{"subAccountId":"${Fixtures.ALICE}","currency":"CZK","totalDebit":0,"totalCredit":1000.00},""" +
                """{"subAccountId":"${Fixtures.BOB}","currency":"CZK","totalDebit":0,"totalCredit":500.00}"""

        fun tbLine(code: String, type: String, ccy: String, debit: String, credit: String): String =
            """{"code":"$code","type":"$type","currency":"$ccy",""" +
                """"totalDebit":$debit,"totalCredit":$credit,""" +
                """"net":${BigDecimal(debit).subtract(BigDecimal(credit))}}"""
    }
}
