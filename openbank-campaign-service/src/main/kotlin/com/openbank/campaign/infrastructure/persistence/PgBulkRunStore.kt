// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.persistence

import com.openbank.campaign.application.port.out.SegmentPage
import com.openbank.campaign.application.usecase.BulkRun
import com.openbank.campaign.application.usecase.BulkRunState
import com.openbank.campaign.application.usecase.BulkRunStore
import com.openbank.campaign.application.usecase.ClaimedBulkRun
import com.openbank.campaign.application.usecase.EnrolmentPageOutcome
import com.openbank.campaign.application.usecase.RecipientAdmissionState
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
@Suppress("TooManyFunctions") // Run, snapshot and recipient writes share one database lease.
class PgBulkRunStore(private val pool: PgPool) : BulkRunStore {
    private companion object {
        const val FIRST_PARTY_PARAMETER = 3
    }
    override suspend fun claimManual(owner: UUID): Boolean = pool.preparedQuery(
        """
        UPDATE campaign_admission_budget
        SET lease_owner = $1, lease_until = now() + interval '1 hour',
            next_available_at = now() + interval '1 minute'
        WHERE id = 1 AND next_available_at <= now()
          AND (lease_until IS NULL OR lease_until < now())
        RETURNING id
        """.trimIndent(),
    ).execute(Tuple.of(owner)).awaitSuspending().rowCount() == 1

    override suspend fun releaseManual(owner: UUID) {
        pool.preparedQuery(
            "UPDATE campaign_admission_budget SET lease_owner = NULL, lease_until = NULL WHERE id = 1 AND lease_owner = $1",
        ).execute(Tuple.of(owner)).awaitSuspending()
    }

    override suspend fun create(id: UUID, campaignId: UUID, pageSize: Int, actor: String): BulkRun {
        val row = pool.preparedQuery(
            """
            INSERT INTO campaign_bulk_runs
                (id, campaign_id, state, page_size, created_by, created_at, updated_at)
            VALUES ($1, $2, 'PREPARING', $3, $4, now(), now())
            ON CONFLICT DO NOTHING
            RETURNING *
            """.trimIndent(),
        ).execute(Tuple.of(id, campaignId, pageSize, actor)).awaitSuspending().firstOrNull()
            ?: error("campaign already has an active bulk run")
        return row.toBulkRun()
    }

    override suspend fun find(id: UUID): BulkRun? = pool.preparedQuery(
        "SELECT * FROM campaign_bulk_runs WHERE id = $1",
    ).execute(Tuple.of(id)).awaitSuspending().firstOrNull()?.toBulkRun()

    override suspend fun list(campaignId: UUID): List<BulkRun> = pool.preparedQuery(
        """
        SELECT * FROM campaign_bulk_runs WHERE campaign_id = $1
        ORDER BY CASE state WHEN 'PREPARING' THEN 0 WHEN 'RUNNING' THEN 1 WHEN 'HELD' THEN 2 ELSE 3 END,
                 created_at DESC
        LIMIT 20
        """.trimIndent(),
    ).execute(Tuple.of(campaignId)).awaitSuspending().map { it.toBulkRun() }

    /**
     * The budget row and run row are claimed in one SQL statement. PostgreSQL row locks serialize
     * this across replicas; a completed page releases the budget lease, but its next-available time
     * still enforces the one-minute spacing. An abandoned claim recovers after one hour.
     */
    override suspend fun claim(owner: UUID): ClaimedBulkRun? {
        val row = pool.preparedQuery(
            """
            WITH candidate AS (
                SELECT id FROM campaign_bulk_runs
                WHERE state IN ('PREPARING', 'RUNNING')
                  AND (lease_until IS NULL OR lease_until < now())
                ORDER BY updated_at, id
                FOR UPDATE SKIP LOCKED LIMIT 1
            ), budget AS (
                UPDATE campaign_admission_budget
                SET lease_owner = $1, lease_until = now() + interval '1 hour',
                    next_available_at = now() + interval '1 minute'
                WHERE id = 1 AND next_available_at <= now()
                  AND (lease_until IS NULL OR lease_until < now())
                  AND EXISTS (SELECT 1 FROM candidate)
                RETURNING id
            )
            UPDATE campaign_bulk_runs AS run
            SET lease_owner = $1, lease_until = now() + interval '1 hour'
            WHERE run.id = (SELECT id FROM candidate) AND EXISTS (SELECT 1 FROM budget)
            RETURNING run.*
            """.trimIndent(),
        ).execute(Tuple.of(owner)).awaitSuspending().firstOrNull() ?: return null
        return ClaimedBulkRun(row.toBulkRun(), owner)
    }

    override suspend fun clearAudience(claim: ClaimedBulkRun) {
        check(leaseValid(claim)) { "audience snapshot lease was lost" }
        pool.preparedQuery(
            """
            DELETE FROM campaign_bulk_recipients AS recipient
            USING campaign_bulk_runs AS run
            WHERE recipient.run_id = run.id AND run.id = $1 AND run.state = 'PREPARING'
              AND run.lease_owner = $2 AND run.lease_until > now()
            """.trimIndent(),
        ).execute(Tuple.of(claim.run.id, claim.leaseOwner)).awaitSuspending()
    }

    override suspend fun appendAudience(claim: ClaimedBulkRun, partyIds: List<UUID>) {
        require(partyIds.size in 1..SegmentPage.MAX_PAGE_SIZE)
        val values = partyIds.indices.joinToString(", ") { "($${it + FIRST_PARTY_PARAMETER}::uuid)" }
        val sql = """
            INSERT INTO campaign_bulk_recipients (run_id, party_id)
            SELECT $1, value.party_id FROM (VALUES $values) AS value(party_id)
            WHERE EXISTS (
                SELECT 1 FROM campaign_bulk_runs
                WHERE id = $1 AND state = 'PREPARING' AND lease_owner = $2 AND lease_until > now()
            )
            ON CONFLICT (run_id, party_id) DO NOTHING
        """.trimIndent()
        val args = Tuple.tuple().addValue(claim.run.id).addValue(claim.leaseOwner)
        partyIds.forEach { args.addValue(it) }
        pool.preparedQuery(sql).execute(args).awaitSuspending()
        check(leaseValid(claim)) { "audience snapshot lease was lost" }
    }

    override suspend fun completeAudience(claim: ClaimedBulkRun) {
        val result = pool.preparedQuery(
            """
            WITH settled AS (
                UPDATE campaign_bulk_runs
                SET state = 'RUNNING', snapshot_at = now(),
                    audience_count = (SELECT count(*) FROM campaign_bulk_recipients WHERE run_id = $1),
                    lease_owner = NULL, lease_until = NULL, updated_at = now()
                WHERE id = $1 AND state = 'PREPARING' AND lease_owner = $2 AND lease_until > now()
                  AND EXISTS (SELECT 1 FROM campaign_admission_budget
                              WHERE id = 1 AND lease_owner = $2 AND lease_until > now())
                RETURNING id
            )
            UPDATE campaign_admission_budget SET lease_owner = NULL, lease_until = NULL
            WHERE id = 1 AND lease_owner = $2 AND EXISTS (SELECT 1 FROM settled)
            RETURNING id
            """.trimIndent(),
        ).execute(Tuple.of(claim.run.id, claim.leaseOwner)).awaitSuspending()
        check(result.rowCount() == 1) { "audience snapshot lease was lost" }
    }

    override suspend fun audiencePage(runId: UUID, after: UUID?, limit: Int): SegmentPage {
        require(limit in 1..SegmentPage.MAX_PAGE_SIZE)
        val rows = pool.preparedQuery(
            """
            SELECT party_id FROM campaign_bulk_recipients
            WHERE run_id = $1 AND ($2::uuid IS NULL OR party_id > $2::uuid)
            ORDER BY party_id LIMIT $3
            """.trimIndent(),
        ).execute(Tuple.of(runId, after, limit)).awaitSuspending()
        val ids = rows.map { it.getUUID("party_id") }
        return SegmentPage(ids, ids.lastOrNull())
    }

    override suspend fun markRecipient(claim: ClaimedBulkRun, partyId: UUID, state: RecipientAdmissionState) {
        val result = pool.preparedQuery(
            """
            UPDATE campaign_bulk_recipients AS recipient
            SET state = CASE
                WHEN recipient.state = 'ADMITTED' THEN 'ADMITTED'
                WHEN $3 = 'SKIPPED' AND recipient.state IN ('STARTING', 'FAILED')
                     AND EXISTS (
                        SELECT 1 FROM enrolments AS enrolment
                        JOIN campaign_bulk_runs AS run ON run.id = $1
                        WHERE enrolment.campaign_id = run.campaign_id
                          AND enrolment.party_id = $2
                          AND enrolment.started_at >= run.snapshot_at
                     ) THEN 'ADMITTED'
                ELSE $3 END,
                updated_at = now()
            WHERE run_id = $1 AND party_id = $2 AND EXISTS (
                SELECT 1 FROM campaign_bulk_runs
                WHERE id = $1 AND state = 'RUNNING' AND lease_owner = $4 AND lease_until > now()
            )
            """.trimIndent(),
        ).execute(Tuple.of(claim.run.id, partyId, state.name, claim.leaseOwner)).awaitSuspending()
        check(result.rowCount() == 1) { "recipient admission lease was lost" }
    }

    private suspend fun leaseValid(claim: ClaimedBulkRun): Boolean = pool.preparedQuery(
        """
        SELECT id FROM campaign_bulk_runs WHERE id = $1 AND lease_owner = $2 AND lease_until > now()
        """.trimIndent(),
    ).execute(Tuple.of(claim.run.id, claim.leaseOwner)).awaitSuspending().rowCount() == 1

    override suspend fun finish(claim: ClaimedBulkRun, outcome: EnrolmentPageOutcome) {
        settle(
            claim,
            if (outcome.complete) BulkRunState.COMPLETED else BulkRunState.RUNNING,
            outcome.nextCursor,
            outcome.enrolled,
            0,
            null,
        )
    }

    override suspend fun hold(claim: ClaimedBulkRun, reason: String, outcome: EnrolmentPageOutcome?) {
        settle(
            claim,
            BulkRunState.HELD,
            outcome?.nextCursor ?: claim.run.cursor,
            outcome?.enrolled ?: 0,
            outcome?.failed ?: 0,
            reason,
        )
    }

    private suspend fun settle(
        claim: ClaimedBulkRun,
        state: BulkRunState,
        cursor: UUID?,
        admitted: Int,
        failures: Int,
        reason: String?,
    ) {
        val updated = pool.preparedQuery(
            """
            WITH valid_budget_lease AS (
                SELECT id FROM campaign_admission_budget
                WHERE id = 1 AND lease_owner = $2 AND lease_until > now()
                FOR UPDATE
            ), settled AS (
                UPDATE campaign_bulk_runs
                SET state = $3, cursor_party_id = $4,
                    admitted = CASE WHEN snapshot_at IS NULL THEN admitted + $5 ELSE
                        (SELECT count(*) FROM campaign_bulk_recipients
                         WHERE run_id = $1 AND state = 'ADMITTED') END,
                    failures = CASE WHEN snapshot_at IS NULL THEN failures + $6 ELSE
                        (SELECT count(*) FROM campaign_bulk_recipients
                         WHERE run_id = $1 AND state = 'FAILED') END,
                    last_error = $7,
                    lease_owner = NULL, lease_until = NULL, updated_at = now()
                WHERE id = $1 AND lease_owner = $2 AND lease_until > now()
                  AND EXISTS (SELECT 1 FROM valid_budget_lease)
                RETURNING id
            )
            UPDATE campaign_admission_budget
            SET lease_owner = NULL, lease_until = NULL
            WHERE id = 1 AND lease_owner = $2 AND EXISTS (SELECT 1 FROM settled)
            RETURNING id
            """.trimIndent(),
        ).execute(
            Tuple.tuple()
                .addValue(claim.run.id)
                .addValue(claim.leaseOwner)
                .addValue(state.name)
                .addValue(cursor)
                .addValue(admitted)
                .addValue(failures)
                .addValue(reason),
        ).awaitSuspending()
        check(updated.rowCount() == 1) { "bulk admission lease was lost" }
    }

    override suspend fun resume(id: UUID, actor: String): BulkRun? = pool.preparedQuery(
        """
        UPDATE campaign_bulk_runs
        SET state = CASE WHEN snapshot_at IS NULL THEN 'PREPARING' ELSE 'RUNNING' END,
            last_error = NULL, last_resumed_by = $2,
            last_resumed_at = now(), updated_at = now()
        WHERE id = $1 AND state = 'HELD' AND created_by <> $2
        RETURNING *
        """.trimIndent(),
    ).execute(Tuple.of(id, actor)).awaitSuspending().firstOrNull()?.toBulkRun()
}

private fun Row.toBulkRun(): BulkRun = BulkRun(
    id = getUUID("id"),
    campaignId = getUUID("campaign_id"),
    state = BulkRunState.valueOf(getString("state")),
    cursor = getUUID("cursor_party_id"),
    pageSize = getInteger("page_size"),
    admitted = getLong("admitted"),
    failures = getLong("failures"),
    lastError = getString("last_error"),
    createdBy = getString("created_by"),
    createdAt = getOffsetDateTime("created_at").toInstant(),
    updatedAt = getOffsetDateTime("updated_at").toInstant(),
    snapshotAt = getOffsetDateTime("snapshot_at")?.toInstant(),
    audienceCount = getLong("audience_count"),
    lastResumedBy = getString("last_resumed_by"),
    lastResumedAt = getOffsetDateTime("last_resumed_at")?.toInstant(),
)
