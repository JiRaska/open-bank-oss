// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource

/**
 * Base for the notification E2E suite: the real HTTP surface against real Postgres with the
 * durable rows asserted over plain JDBC. Concrete suites carry `@QuarkusTest` +
 * `@TestProfile(NotificationE2EProfile::class)` — the annotations do not inherit, and Quarkus
 * needs them on the concrete class to register the test class as a CDI bean.
 */
abstract class NotificationE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    /** GET /api/v1/preferences/party/{partyId} expecting [expectedStatus]. */
    protected fun getPreferences(partyId: UUID, expectedStatus: Int): JsonNode = parse(
        Given { this } When { get("/api/v1/preferences/party/$partyId") } Then {
            statusCode(expectedStatus)
        } Extract { asString() },
    )

    /** PUT /api/v1/preferences/party/{partyId} expecting [expectedStatus]. */
    protected fun putPreferences(partyId: UUID, body: Map<String, Any?>, expectedStatus: Int): JsonNode = parse(
        Given {
            contentType("application/json")
            body(body)
        } When { put("/api/v1/preferences/party/$partyId") } Then {
            statusCode(expectedStatus)
        } Extract { asString() },
    )

    /** POST on [path] expecting [expectedStatus]. */
    protected fun postReason(path: String, reason: String?, expectedStatus: Int): JsonNode = parse(
        Given {
            contentType("application/json")
            body(mapOf("reason" to reason))
        } When { post(path) } Then {
            statusCode(expectedStatus)
        } Extract { asString() },
    )

    /** GET [path] expecting [expectedStatus]. */
    protected fun getJson(path: String, expectedStatus: Int): JsonNode = parse(
        Given { this } When { get(path) } Then {
            statusCode(expectedStatus)
        } Extract { asString() },
    )

    private fun parse(raw: String): JsonNode =
        if (raw.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(raw)

    /** The stored preference row for the party, or null when never set. */
    protected fun preferenceRow(partyId: UUID): PreferenceRow? = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT payments_push, product_push, marketing_push FROM notification_preferences " +
                "WHERE party_id = ?",
        ).use { ps ->
            ps.setObject(1, partyId)
            val rs = ps.executeQuery()
            if (rs.next()) {
                PreferenceRow(
                    paymentsPush = rs.getBoolean("payments_push"),
                    productPush = rs.getBoolean("product_push"),
                    marketingPush = rs.getBoolean("marketing_push"),
                )
            } else {
                null
            }
        }
    }

    /** The latest dispatch-control snapshot fields the assertions need. */
    data class PreferenceRow(val paymentsPush: Boolean, val productPush: Boolean, val marketingPush: Boolean)
}
