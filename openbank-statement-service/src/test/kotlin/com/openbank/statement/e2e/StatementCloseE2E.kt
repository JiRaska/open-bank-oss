// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The ADR-0035 period-close lifecycle over the real HTTP surface: caller → endpoint → four
 * downstream services (account, party, transactions, balances — stood in for by
 * [StatementDownstreamWireMockResource] over real HTTP) → fail-closed reconciliation → sequenced,
 * snapshotted period row + atomic outbox envelope in real Postgres, asserted over plain JDBC.
 *
 * Every journey mints a fresh account id, so journeys never share stubs or rows and need no
 * ordering.
 */
@QuarkusTest
@TestProfile(StatementE2EProfile::class)
class StatementCloseE2E : StatementE2EBase() {

    @BeforeEach
    fun resetDownstream() = StatementDownstreamWireMockResource.reset()

    @Test
    @TestSecurity(user = "statement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `close mints a reconciled period per active pocket with an atomic outbox fact`() {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        stubViableAccount(accountId, partyId, IBAN, "CZK" to "ACTIVE", "EUR" to "ACTIVE")
        // ONE search stub serves BOTH pockets — the endpoint has no currency filter, the adapter
        // filters client-side; the EUR pocket's entryCount=0 below asserts that filter.
        StatementDownstreamWireMockResource.stubTransactions(accountId, FROM, TO, czkEntries(accountId))
        // Opening = balance as of the day before the period; closing must equal opening + net
        // movement EXACTLY (ReconciliationPolicy, ADR-0035 §E): 1000.00 + (500.00 - 120.50).
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", OPENING_AS_OF, "1000.00")
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", TO, "1379.50")
        StatementDownstreamWireMockResource.stubBalance(accountId, "EUR", OPENING_AS_OF, "200.00")
        StatementDownstreamWireMockResource.stubBalance(accountId, "EUR", TO, "200.00")

        val body = close(accountId, "from=$FROM&to=$TO", 200)
        assertThat(body.isArray).isTrue()
        assertThat(body.size()).isEqualTo(2)
        val currencies = body.map { it.get("pocketCurrency").asText() }
        assertThat(currencies).containsExactlyInAnyOrder("CZK", "EUR")
        body.forEach { assertThat(it.get("legalSequenceNumber").asLong()).isEqualTo(1L) }

        val czk = periodRow(accountId, "CZK")!!
        assertThat(czk.legalSequence).isEqualTo(1L)
        assertThat(czk.opening).isEqualByComparingTo(BigDecimal("1000.0000"))
        assertThat(czk.closing).isEqualByComparingTo(BigDecimal("1379.5000"))
        assertThat(czk.entryCount).isEqualTo(2)
        assertThat(czk.status).isEqualTo("CLOSED")
        assertThat(czk.supersedesSequence).isNull()
        val eur = periodRow(accountId, "EUR")!!
        assertThat(eur.closing).isEqualByComparingTo(BigDecimal("200.0000"))
        assertThat(eur.entryCount).isEqualTo(0)
        // One outbox envelope per pocket, atomically with the period row (ADR-0035 §F).
        assertThat(outboxCountFor(accountId)).isEqualTo(2)

        // The close actually fanned out over real HTTP: booked-entry search pinned to COMPLETED,
        // max page size, both balance anchors point-in-time.
        assertThat(StatementDownstreamWireMockResource.requestsTo("/api/v1/transactions/search")).isEqualTo(2)
        assertThat(StatementDownstreamWireMockResource.requestsTo("/api/v1/balances/$accountId/CZK")).isEqualTo(2)
        assertThat(StatementDownstreamWireMockResource.requestsTo("/api/v1/accounts/$accountId")).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "statement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `close is idempotent and never double-mints`() {
        val accountId = UUID.randomUUID()
        stubSinglePocketAccount(accountId)
        StatementDownstreamWireMockResource.stubTransactions(accountId, FROM, TO, czkEntries(accountId))
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", OPENING_AS_OF, "1000.00")
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", TO, "1379.50")

        close(accountId, "from=$FROM&to=$TO", 200)
        val replay = close(accountId, "from=$FROM&to=$TO", 200)
        assertThat(replay.get(0).get("legalSequenceNumber").asLong()).isEqualTo(1L)

        // Exactly one row and one outbox envelope per pocket, ever (ADR-0035 §F idempotence).
        assertThat(periodRows(accountId, "CZK")).hasSize(1)
        assertThat(outboxCountFor(accountId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "statement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `reconciliation mismatch fails closed with 409 and persists nothing`() {
        val accountId = UUID.randomUUID()
        stubSinglePocketAccount(accountId)
        StatementDownstreamWireMockResource.stubTransactions(accountId, FROM, TO, czkEntries(accountId))
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", OPENING_AS_OF, "1000.00")
        // One cent short: opening + net = 1379.50, the reported closing says 1379.49.
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", TO, "1379.49")

        val body = close(accountId, "from=$FROM&to=$TO", 409)
        assertThat(body.get("error").asText()).contains("reconcil")
        assertThat(periodRow(accountId, "CZK")).isNull()
        assertThat(outboxCountFor(accountId)).isEqualTo(0)
    }

    @Test
    @TestSecurity(user = "statement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `restate supersedes never edits and restating a never-closed period is 404`() {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        stubViableAccount(accountId, partyId, IBAN, "CZK" to "ACTIVE")
        StatementDownstreamWireMockResource.stubTransactions(accountId, FROM, TO, czkEntries(accountId))
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", OPENING_AS_OF, "1000.00")
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", TO, "1379.50")

        close(accountId, "from=$FROM&to=$TO", 200)

        // A late-arriving +300.00 credit lands in the books after the close froze its snapshot.
        // Restate recomputes against the CURRENT projections: 3 entries, closing 1679.50 — which
        // no longer matches the frozen 1379.50, so the restatement MUST mint a new sequence.
        StatementDownstreamWireMockResource.reset()
        stubViableAccount(accountId, partyId, IBAN, "CZK" to "ACTIVE")
        StatementDownstreamWireMockResource.stubTransactions(accountId, FROM, TO, czkEntries(accountId, 300.00))
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", OPENING_AS_OF, "1000.00")
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", TO, "1679.50")

        val restated = restate(accountId, "CZK", FROM, TO, 200)
        assertThat(restated.get("legalSequenceNumber").asLong()).isEqualTo(2L)
        assertThat(restated.get("entryCount").asLong()).isEqualTo(3L)

        val rows = periodRows(accountId, "CZK")
        assertThat(rows).hasSize(2)
        // ADR-0035 §D: the superseded close is flipped to SUPERSEDED; the restatement is a NEW
        // sequenced row referencing it — the issued record is never edited.
        assertThat(rows[0].status).isEqualTo("SUPERSEDED")
        assertThat(rows[1].status).isEqualTo("CLOSED")
        assertThat(rows[1].supersedesSequence).isEqualTo(1L)
        assertThat(outboxCountFor(accountId)).isEqualTo(2)

        val neverClosed = restate(accountId, "EUR", FROM, TO, 404)
        assertThat(neverClosed.get("error").asText()).isNotBlank()
    }

    @Test
    @TestSecurity(user = "statement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `summary and render replay the frozen snapshot without touching downstream`() {
        val accountId = UUID.randomUUID()
        stubSinglePocketAccount(accountId)
        StatementDownstreamWireMockResource.stubTransactions(accountId, FROM, TO, czkEntries(accountId))
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", OPENING_AS_OF, "1000.00")
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", TO, "1379.50")
        close(accountId, "from=$FROM&to=$TO", 200)

        val summary = getJson("/api/v1/statements/$accountId/CZK/1/summary", 200)
        assertThat(summary.get("iban").asText()).isEqualTo(IBAN)
        assertThat(summary.get("holderName").asText()).isEqualTo("Jiří Raška")
        assertThat(
            summary.get("closingBalance").get("amount").decimalValue(),
        ).isEqualByComparingTo(BigDecimal("1379.5"))
        assertThat(summary.get("entries").size()).isEqualTo(2)
        assertThat(summary.get("legalSequenceNumber").asLong()).isEqualTo(1L)

        val first = getRaw("/api/v1/statements/$accountId/CZK/1?format=MT940", 200)
        assertThat(first).contains(IBAN)

        // The snapshot-purity proof: drop EVERY downstream stub, then render again. The bytes
        // must be identical however much the live projections have moved (ADR-0035 §D/§F) — and
        // the request journal proves nothing was dialled.
        StatementDownstreamWireMockResource.reset()
        val second = getRaw("/api/v1/statements/$accountId/CZK/1?format=MT940", 200)
        assertThat(second).isEqualTo(first)
        assertThat(StatementDownstreamWireMockResource.requestsTo("/api/v1/transactions/search")).isZero()
        assertThat(StatementDownstreamWireMockResource.requestsTo("/api/v1/accounts/$accountId")).isZero()
        assertThat(StatementDownstreamWireMockResource.requestsTo("/api/v1/balances/$accountId/CZK")).isZero()
    }

    @Test
    @TestSecurity(user = "statement-e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `render of an unknown sequence is 404 and ad-hoc export is non-sequenced`() {
        val accountId = UUID.randomUUID()
        stubSinglePocketAccount(accountId)
        StatementDownstreamWireMockResource.stubTransactions(accountId, FROM, TO, czkEntries(accountId))
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", OPENING_AS_OF, "1000.00")
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", TO, "1379.50")

        val missing = getJson("/api/v1/statements/$accountId/CZK/99?format=MT940", 404)
        assertThat(missing.get("error").asText()).isNotBlank()

        // ADR-0035 §F.3: an arbitrary-range informational export — no sequence, nothing persisted.
        val exported = getRaw("/api/v1/statements/$accountId/CZK/export?from=$FROM&to=$TO&format=CAMT053", 200)
        assertThat(exported).contains(IBAN)
        assertThat(periodRows(accountId, "CZK")).isEmpty()
        assertThat(outboxCountFor(accountId)).isEqualTo(0)
    }

    @Test
    @TestSecurity(user = "statement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `missing period bounds are 400 before any downstream call`() {
        val accountId = UUID.randomUUID()

        // A period is never guessed (no @DefaultValue on purpose).
        val missingFrom = close(accountId, "to=$TO", 400)
        assertThat(missingFrom.get("message").asText()).contains("from")
        val missingTo = close(accountId, "from=$FROM", 400)
        assertThat(missingTo.get("message").asText()).contains("to")
        assertThat(StatementDownstreamWireMockResource.requestsTo("/api/v1/accounts/$accountId")).isZero()
    }

    @Test
    @TestSecurity(user = "statement-e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `viewer can read but never close, and refusals dial no downstream`() {
        val accountId = UUID.randomUUID()

        close(accountId, "from=$FROM&to=$TO", 403)

        // VIEWER may read: an empty period list for a never-closed account.
        val list = getJson("/api/v1/statements/$accountId", 200)
        assertThat(list.isArray).isTrue()
        assertThat(StatementDownstreamWireMockResource.requestsTo("/api/v1/accounts/$accountId")).isZero()
    }

    @Test
    fun `anonymous caller is refused before any downstream call`() {
        val accountId = UUID.randomUUID()
        getJson("/api/v1/statements/$accountId", 401)
        getJson("/api/v1/statements/$accountId/CZK/1/summary", 401)
        assertThat(StatementDownstreamWireMockResource.requestsTo("/api/v1/accounts/$accountId")).isZero()
    }

    @Test
    @TestSecurity(user = "statement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `document renders via document-service and answers 502 when it is down`() {
        val accountId = UUID.randomUUID()
        stubSinglePocketAccount(accountId)
        StatementDownstreamWireMockResource.stubTransactions(accountId, FROM, TO, czkEntries(accountId))
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", OPENING_AS_OF, "1000.00")
        StatementDownstreamWireMockResource.stubBalance(accountId, "CZK", TO, "1379.50")
        close(accountId, "from=$FROM&to=$TO", 200)

        StatementDownstreamWireMockResource.stubDocumentTemplates(
            "MESICNI_VYPIS_CS",
            "<html>statement for {{iban}}</html>",
        )
        StatementDownstreamWireMockResource.stubDocumentPreview("<html>statement for $IBAN</html>")
        val rendered = getRaw("/api/v1/statements/$accountId/CZK/1/document?locale=cs", 200)
        assertThat(rendered).contains(IBAN)

        // document-service unreachable: the caller's one endpoint degrades to 502, never the
        // statement lifecycle (ADR-0248).
        StatementDownstreamWireMockResource.reset()
        val degraded = getJson("/api/v1/statements/$accountId/CZK/1/document?locale=cs", 502)
        assertThat(degraded.get("error").asText()).isNotBlank()
        // …and the snapshot-backed endpoints are untouched by the document outage.
        val summary = getJson("/api/v1/statements/$accountId/CZK/1/summary", 200)
        assertThat(
            summary.get("closingBalance").get("amount").decimalValue(),
        ).isEqualByComparingTo(BigDecimal("1379.5"))
    }

    private fun stubSinglePocketAccount(accountId: UUID) {
        stubViableAccount(accountId, UUID.randomUUID(), IBAN, "CZK" to "ACTIVE")
    }

    private companion object {
        const val IBAN = "CZ860101000000000012345678"
        const val FROM = "2026-09-01"
        const val TO = "2026-09-30"
        const val OPENING_AS_OF = "2026-08-31"

        fun czkEntries(accountId: UUID, lateCredit: Double? = null): String {
            val base = """
            {"referenceNumber":"TXN-1","amount":500.00,"currencyCode":"CZK","type":"PAYMENT",
             "targetAccountId":"$accountId","bookingDate":"2026-09-05","valueDate":"2026-09-05","description":"Invoice 1"},
            {"referenceNumber":"TXN-2","amount":120.50,"currencyCode":"CZK","type":"PAYMENT",
             "sourceAccountId":"$accountId","bookingDate":"2026-09-12","valueDate":"2026-09-12","description":"Rent"}
            """.trimIndent()
            val late = lateCredit?.let {
                """,
                {"referenceNumber":"TXN-3","amount":$it,"currencyCode":"CZK","type":"PAYMENT",
                 "targetAccountId":"$accountId","bookingDate":"2026-09-20","valueDate":"2026-09-20","description":"Late credit"}"""
            } ?: ""
            return base + late
        }
    }
}
