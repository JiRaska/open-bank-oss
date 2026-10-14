// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.openbank.pension.infrastructure.reporting.ContributionsDto
import com.openbank.pension.infrastructure.reporting.ParticipantAggregatesResponse
import com.openbank.pension.infrastructure.reporting.ParticipantsDto
import com.openbank.pension.infrastructure.reporting.PayoutsDto
import com.openbank.pension.infrastructure.reporting.StateContributionsDto
import com.openbank.pension.infrastructure.reporting.TransfersDto
import org.eclipse.microprofile.config.ConfigProvider
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Date
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/** Deterministic TEST-ONLY immutable record for the historical-read Pact interaction. */
object ParticipantSnapshotPactFixture {
    private val snapshotId = UUID.fromString("d45ca8df-f9dc-4573-bb7b-513219f33a96")
    private val companyId = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val start = LocalDate.parse("2009-01-01")
    private val end = LocalDate.parse("2009-03-31")
    private val zero = BigDecimal("0.00")

    fun seed(mapper: ObjectMapper) {
        // Match the production serializer configuration, so the advertised digest covers the
        // exact JSON bytes a real immutable read returns. This fixture claims no filing facts.
        val canonical = mapper.copy()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        val contributions = ContributionsDto(zero, zero, zero, zero, zero)
        val payouts = PayoutsDto(zero, 0, zero, emptyMap())
        val payload = canonical.writeValueAsString(
            ParticipantAggregatesResponse(
                start,
                end,
                "CZK",
                ParticipantsDto(0, 0, 0, 0, 0, emptyMap(), emptyMap(), emptyMap()),
                contributions,
                contributions,
                StateContributionsDto(zero, zero, zero),
                payouts,
                payouts,
                TransfersDto(0, zero, 0, zero),
            ),
        )
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { db ->
            db.prepareStatement(
                """INSERT INTO pension_participant_snapshots
                   (snapshot_id, company_id, period_start, period_end, revision, payload_schema_version, payload_json, sha256)
                   VALUES (?, ?, ?, ?, 1, 'participant-period-aggregates/2.0', ?, ?)
                   ON CONFLICT (snapshot_id) DO NOTHING""",
            ).use { statement ->
                statement.setObject(1, snapshotId)
                statement.setObject(2, companyId)
                statement.setDate(3, Date.valueOf(start))
                statement.setDate(4, Date.valueOf(end))
                statement.setString(5, payload)
                statement.setString(6, digest)
                statement.executeUpdate()
            }
        }
    }
}
