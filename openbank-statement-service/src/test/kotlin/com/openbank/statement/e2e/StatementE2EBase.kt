// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource

/**
 * Base for the statement E2E suite: the real HTTP surface against real Postgres with the durable
 * facts (statement_period rows, statement_outbox envelopes) asserted over plain JDBC. Concrete
 * suites carry `@QuarkusTest` + `@TestProfile(StatementE2EProfile::class)` — the annotations do
 * not inherit, and Quarkus needs them on the concrete class to register the test class as a CDI
 * bean.
 */
abstract class StatementE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    /** POST /api/v1/statements/{accountId}/close?[query] expecting [expectedStatus]. */
    protected fun close(accountId: UUID, query: String, expectedStatus: Int): JsonNode = parse(
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/statements/$accountId/close?$query")
        } Then {
            statusCode(expectedStatus)
        } Extract { asString() },
    )

    /** POST /api/v1/statements/{accountId}/{currency}/restate?from&to expecting [expectedStatus]. */
    protected fun restate(accountId: UUID, currency: String, from: String, to: String, expectedStatus: Int): JsonNode =
        parse(
            Given {
                contentType("application/json")
            } When {
                post("/api/v1/statements/$accountId/$currency/restate?from=$from&to=$to")
            } Then {
                statusCode(expectedStatus)
            } Extract { asString() },
        )

    /** GET [path] expecting [expectedStatus]. */
    protected fun getJson(path: String, expectedStatus: Int): JsonNode = parse(
        Given { this } When { get(path) } Then {
            statusCode(expectedStatus)
        } Extract { asString() },
    )

    /** GET [path] expecting [expectedStatus], returning the RAW body — renders are text, not JSON. */
    protected fun getRaw(path: String, expectedStatus: Int): String = Given { this } When { get(path) } Then {
        statusCode(expectedStatus)
    } Extract { asString() }

    private fun parse(raw: String): JsonNode =
        if (raw.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(raw)

    /** The persisted close for the pocket, or null when the period was never closed. */
    protected fun periodRow(accountId: UUID, currency: String): PeriodRow? =
        periodRows(accountId, currency).firstOrNull()

    /** Every persisted close for the pocket — supersession journeys read the second row. */
    protected fun periodRows(accountId: UUID, currency: String): List<PeriodRow> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT legal_sequence_number, electronic_sequence_number, opening_balance, closing_balance, " +
                "entry_count, status, supersedes_sequence FROM statement_period " +
                "WHERE account_id = ? AND pocket_currency = ? ORDER BY legal_sequence_number",
        ).use { ps ->
            ps.setObject(1, accountId)
            ps.setString(2, currency)
            val rs = ps.executeQuery()
            val rows = mutableListOf<PeriodRow>()
            while (rs.next()) {
                rows.add(
                    PeriodRow(
                        legalSequence = rs.getLong("legal_sequence_number"),
                        electronicSequence = rs.getLong("electronic_sequence_number"),
                        opening = rs.getBigDecimal("opening_balance"),
                        closing = rs.getBigDecimal("closing_balance"),
                        entryCount = rs.getInt("entry_count"),
                        status = rs.getString("status"),
                        supersedesSequence = rs.getLong("supersedes_sequence").let { v ->
                            if (rs.wasNull()) null else v
                        },
                    ),
                )
            }
            rows
        }
    }

    /** How many `account.statement.period.closed.v1` envelopes this account has committed to the outbox. */
    protected fun outboxCountFor(accountId: UUID): Int = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT count(*) FROM statement_outbox WHERE aggregate_id IN " +
                "(SELECT id FROM statement_period WHERE account_id = ?)",
        ).use { ps ->
            ps.setObject(1, accountId)
            val rs = ps.executeQuery()
            rs.next()
            rs.getLong(1).toInt()
        }
    }

    /** A full downstream fixture for one account: account + ACTIVE pockets + holder name. */
    protected fun stubViableAccount(
        accountId: UUID,
        partyId: UUID,
        iban: String,
        vararg pockets: Pair<String, String>,
    ) {
        StatementDownstreamWireMockResource.stubAccount(accountId, iban, partyId)
        StatementDownstreamWireMockResource.stubPockets(accountId, *pockets)
        StatementDownstreamWireMockResource.stubParty(partyId, "Jiří Raška")
    }

    protected data class PeriodRow(
        val legalSequence: Long,
        val electronicSequence: Long,
        val opening: BigDecimal,
        val closing: BigDecimal,
        val entryCount: Int,
        val status: String,
        val supersedesSequence: Long?,
    )
}
