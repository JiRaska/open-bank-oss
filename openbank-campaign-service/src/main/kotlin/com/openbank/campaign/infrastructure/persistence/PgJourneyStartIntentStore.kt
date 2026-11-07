// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.persistence

import com.openbank.campaign.application.port.out.JourneyType
import com.openbank.campaign.application.usecase.JourneyStartIntent
import com.openbank.campaign.application.usecase.JourneyStartIntentStore
import com.openbank.campaign.application.usecase.JourneyStartSource
import com.openbank.campaign.domain.model.ContentVariant
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class PgJourneyStartIntentStore(private val pool: PgPool) : JourneyStartIntentStore {
    override suspend fun begin(intent: JourneyStartIntent): JourneyStartIntent {
        pool.preparedQuery(
            """
            INSERT INTO campaign_journey_start_intents
                (campaign_id, party_id, enrolment_id, journey_type, source,
                 experiment_cohort, content_variant, created_at)
            VALUES ($1, $2, $3, $4, $5, 'TREATMENT', $6, $7)
            ON CONFLICT (campaign_id, party_id) DO NOTHING
            """.trimIndent(),
        ).execute(
            Tuple.tuple()
                .addValue(intent.campaignId)
                .addValue(intent.partyId)
                .addValue(intent.enrolmentId)
                .addValue(intent.journeyType.name)
                .addValue(intent.source.name)
                .addValue(intent.contentVariant?.name)
                .addValue(intent.createdAt.atOffset(java.time.ZoneOffset.UTC)),
        ).awaitSuspending()
        return pool.preparedQuery(
            "SELECT * FROM campaign_journey_start_intents WHERE campaign_id = $1 AND party_id = $2",
        ).execute(Tuple.of(intent.campaignId, intent.partyId)).awaitSuspending().first().toIntent()
    }

    override suspend fun complete(campaignId: UUID, partyId: UUID) {
        pool.preparedQuery(
            "DELETE FROM campaign_journey_start_intents WHERE campaign_id = $1 AND party_id = $2",
        ).execute(Tuple.of(campaignId, partyId)).awaitSuspending()
    }

    override suspend fun claimRecovery(owner: UUID): JourneyStartIntent? = pool.preparedQuery(
        """
        WITH candidate AS (
            SELECT intent.campaign_id, intent.party_id
            FROM campaign_journey_start_intents AS intent
            JOIN campaigns AS campaign ON campaign.id = intent.campaign_id
            LEFT JOIN enrolments AS enrolment
              ON enrolment.campaign_id = intent.campaign_id AND enrolment.party_id = intent.party_id
            WHERE intent.created_at < now() - interval '30 seconds'
              AND (intent.lease_until IS NULL OR intent.lease_until < now())
              AND (campaign.state IN ('ACTIVE', 'CLOSED') OR enrolment.id IS NOT NULL)
            ORDER BY intent.created_at, intent.campaign_id, intent.party_id
            FOR UPDATE OF intent SKIP LOCKED LIMIT 1
        ), budget AS (
            UPDATE campaign_admission_budget
            SET lease_owner = $1, lease_until = now() + interval '1 hour',
                next_available_at = now() + interval '1 minute'
            WHERE id = 1 AND next_available_at <= now()
              AND (lease_until IS NULL OR lease_until < now())
              AND EXISTS (SELECT 1 FROM candidate)
            RETURNING id
        )
        UPDATE campaign_journey_start_intents AS intent
        SET lease_owner = $1, lease_until = now() + interval '1 hour'
        WHERE (intent.campaign_id, intent.party_id) IN (SELECT campaign_id, party_id FROM candidate)
          AND EXISTS (SELECT 1 FROM budget)
        RETURNING intent.*
        """.trimIndent(),
    ).execute(Tuple.of(owner)).awaitSuspending().firstOrNull()?.toIntent()

    override suspend fun releaseRecovery(owner: UUID, intent: JourneyStartIntent) {
        pool.preparedQuery(
            """
            -- An unresolved intent backs off so one failing party cannot starve all newer ones.
            UPDATE campaign_journey_start_intents
            SET lease_owner = NULL, lease_until = now() + interval '5 minutes'
            WHERE campaign_id = $1 AND party_id = $2 AND lease_owner = $3
            """.trimIndent(),
        ).execute(Tuple.of(intent.campaignId, intent.partyId, owner)).awaitSuspending()
        pool.preparedQuery(
            "UPDATE campaign_admission_budget SET lease_owner = NULL, lease_until = NULL WHERE id = 1 AND lease_owner = $1",
        ).execute(Tuple.of(owner)).awaitSuspending()
    }

    override suspend fun countStale(): Long = pool.query(
        "SELECT count(*) AS pending FROM campaign_journey_start_intents WHERE created_at < now() - interval '15 minutes'",
    ).execute().awaitSuspending().first().getLong("pending")
}

private fun Row.toIntent() = JourneyStartIntent(
    campaignId = getUUID("campaign_id"),
    partyId = getUUID("party_id"),
    enrolmentId = getUUID("enrolment_id"),
    journeyType = JourneyType.valueOf(getString("journey_type")),
    source = JourneyStartSource.valueOf(getString("source")),
    contentVariant = getString("content_variant")?.let { ContentVariant.valueOf(it) },
    createdAt = get(OffsetDateTime::class.java, "created_at").toInstant(),
)
