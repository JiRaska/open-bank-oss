// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource

/**
 * Shared harness for the party E2E suites: the exact request bodies every journey sends, the
 * JDBC readers that assert durable state (party row, outbox events) directly, and nothing else.
 * Splitting the journeys across two concrete classes keeps each one inside the complexity
 * budget; the harness lives here exactly once.
 */
@Suppress("LongParameterList", "TooManyFunctions")
abstract class PartyE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    // ----------------------------------------------------------------------------------------------
    // HTTP builders
    // ----------------------------------------------------------------------------------------------

    protected fun jsonSpec(body: String): RequestSpecification =
        RestAssured.given().contentType(ContentType.JSON).body(body)

    protected fun createSpec(body: String, idempotencyKey: String): RequestSpecification =
        jsonSpec(body).header("Idempotency-Key", idempotencyKey)

    protected fun createPartyBody(
        email: String,
        legalName: String = "E2E Party ${UUID.randomUUID()}",
        partyType: String = "INDIVIDUAL",
        phone: String? = null,
        id: UUID? = null,
        consentGdpr: Boolean? = null,
        consentMarketing: Boolean? = null,
        classification: String? = null,
    ): String {
        val fields = buildList {
            add("\"partyType\":\"$partyType\"")
            add("\"legalName\":\"$legalName\"")
            add("\"email\":\"$email\"")
            if (phone != null) add("\"phone\":\"$phone\"")
            if (id != null) add("\"id\":\"$id\"")
            if (consentGdpr != null) add("\"consentGdpr\":$consentGdpr")
            if (consentMarketing != null) add("\"consentMarketing\":$consentMarketing")
            if (classification != null) add("\"classification\":\"$classification\"")
        }
        return "{${fields.joinToString(",")}}"
    }

    protected fun updatePartyBody(phone: String? = null, legalName: String? = null, email: String? = null): String {
        val fields = buildList {
            if (phone != null) add("\"phone\":\"$phone\"")
            if (legalName != null) add("\"legalName\":\"$legalName\"")
            if (email != null) add("\"email\":\"$email\"")
        }
        return "{${fields.joinToString(",")}}"
    }

    protected fun payeeBody(name: String, iban: String, bic: String? = null): String {
        val fields = buildList {
            add("\"name\":\"$name\"")
            add("\"iban\":\"$iban\"")
            if (bic != null) add("\"bic\":\"$bic\"")
        }
        return "{${fields.joinToString(",")}}"
    }

    protected fun mandateBody(
        agentPartyId: UUID,
        role: String = "AUTHORISED_SIGNATORY",
        authority: String = "SOLE",
        requiredSignatures: Int? = null,
    ): String {
        val fields = buildList {
            add("\"agentPartyId\":\"$agentPartyId\"")
            add("\"role\":\"$role\"")
            add("\"authority\":\"$authority\"")
            if (requiredSignatures != null) add("\"requiredSignatures\":$requiredSignatures")
        }
        return "{${fields.joinToString(",")}}"
    }

    protected fun directoryLookupBody(vararg hashes: String?): String {
        val elements = hashes.joinToString(",") { if (it == null) "null" else "\"$it\"" }
        return "{\"phoneHashes\":[$elements]}"
    }

    // ----------------------------------------------------------------------------------------------
    // JDBC readers
    // ----------------------------------------------------------------------------------------------

    protected fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    protected fun <T> withQuery(sql: String, vararg params: Any, map: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { query ->
                params.forEachIndexed { index, param -> query.setObject(index + 1, param) }
                query.executeQuery().use { rows -> map(rows) }
            }
        }

    protected fun queryString(sql: String, vararg params: Any): String? =
        withQuery(sql, *params) { rows -> if (rows.next()) rows.getString(1) else null }

    protected fun queryLong(sql: String, vararg params: Any): Long =
        withQuery(sql, *params) { rows -> rows.also { check(it.next()) }.getLong(1) }

    /** One column of the party row; [column] is always a literal from the test, never request data. */
    protected fun partyField(partyId: UUID, column: String): String? =
        queryString("SELECT $column FROM parties WHERE party_id = ?", partyId)

    /**
     * Insert a party row directly for journeys whose reading identity may not create one
     * (ROLE_DPO, ROLE_RISK, ROLE_VIEWER hold no party-create role). Same minimal shape
     * GdprSubjectAccessViaEdgeIT uses; defaults cover the remaining columns.
     */
    protected fun insertParty(partyId: UUID, email: String, legalName: String, phone: String? = null) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO parties (id, party_id, party_type, legal_name, email, phone) " +
                    "VALUES (nextval('parties_seq'), ?, 'INDIVIDUAL', ?, ?, ?)",
            ).use { query ->
                query.setObject(1, partyId)
                query.setString(2, legalName)
                query.setString(3, email)
                query.setString(4, phone)
                check(query.executeUpdate() == 1)
            }
        }
    }

    protected fun outboxCount(aggregateId: UUID, eventType: String): Long = queryLong(
        "SELECT count(*) FROM party_outbox WHERE aggregate_id = ? AND event_type = ?",
        aggregateId,
        eventType,
    )

    protected fun outboxPayload(aggregateId: UUID, eventType: String): JsonNode =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT payload FROM party_outbox WHERE aggregate_id = ? AND event_type = ? " +
                    "ORDER BY created_at DESC LIMIT 1",
            ).use { query ->
                query.setObject(1, aggregateId)
                query.setObject(2, eventType)
                query.executeQuery().use { rows ->
                    check(rows.next()) { "no $eventType outbox row for $aggregateId" }
                    objectMapper.readTree(rows.getString(1))
                }
            }
        }
}
