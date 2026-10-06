// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.analytics.contract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.analytics.infrastructure.clickhouse.ClickHouseClient
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Shared state for folder and broker replays, written through the real ClickHouse HTTP adapter. */
object CreditProfilePactState {
    const val PROFILE_STATE = "an observed credit profile exists for the pact lending party"
    const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
    const val PARTY_ID = "8a8a8a8a-8a8a-4a8a-8a8a-8a8a8a8a8a8a"

    private const val ACCOUNT_ID = "pact-credit-profile-account"
    private val mapper = jacksonObjectMapper()

    fun seed(client: ClickHouseClient) = runBlocking {
        val firstEvent = "8b8b8b8b-8b8b-4b8b-8b8b-8b8b8b8b8b8b"
        val exists = client.query(
            "SELECT count() FROM openbank_analytics.bronze_events " +
                "WHERE event_id = '$firstEvent' FORMAT TabSeparated",
        ).trim().toLong()
        if (exists == 0L) {
            val month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(1)
            val occurredAt = month.atStartOfDay().toString().replace('T', ' ')
            val rows = listOf(
                event(firstEvent, "ACCOUNT", ACCOUNT_ID, mapOf("partyId" to PARTY_ID), occurredAt),
                event(
                    "8c8c8c8c-8c8c-4c8c-8c8c-8c8c8c8c8c8c",
                    "TRANSACTION",
                    "pact-inbound",
                    mapOf("sourceAccountId" to "external", "targetAccountId" to ACCOUNT_ID, "amount" to "1000.00"),
                    occurredAt,
                ),
                event(
                    "8d8d8d8d-8d8d-4d8d-8d8d-8d8d8d8d8d8d",
                    "TRANSACTION",
                    "pact-outbound",
                    mapOf("sourceAccountId" to ACCOUNT_ID, "targetAccountId" to "external", "amount" to "400.00"),
                    occurredAt,
                ),
            )
            client.insert("bronze_events", rows.joinToString("\n"))
        }
        val profile = client.query(
            "SELECT months_observed, income_monthly, outflow_monthly, net_monthly " +
                "FROM openbank_analytics.gold_party_credit_profile " +
                "WHERE party_id = '$PARTY_ID' FORMAT JSONEachRow",
        ).lineSequence().firstOrNull { it.isNotBlank() }
            ?: error("ClickHouse pact state did not produce a credit profile")
        val row = mapper.readTree(profile)
        check(row["months_observed"].asInt() == 1)
        check(BigDecimal(row["income_monthly"].asText()).compareTo(BigDecimal("1000")) == 0)
        check(BigDecimal(row["outflow_monthly"].asText()).compareTo(BigDecimal("400")) == 0)
        check(BigDecimal(row["net_monthly"].asText()).compareTo(BigDecimal("600")) == 0)
    }

    private fun event(id: String, type: String, aggregate: String, payload: Map<String, String>, at: String): String =
        mapper.writeValueAsString(
            mapOf(
                "event_id" to UUID.fromString(id),
                "aggregate_type" to type,
                "aggregate_id" to aggregate,
                "aggregate_version" to 1,
                "event_type" to "pact.fixture",
                "occurred_at" to at,
                "source_service" to "pact-fixture",
                "schema_version" to 1,
                "payload" to mapper.writeValueAsString(payload),
            ),
        )
}
