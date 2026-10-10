// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.statecontribution

import com.openbank.pension.application.port.out.ReturnReport
import com.openbank.pension.application.port.out.StateContributionReturnRepository
import com.openbank.pension.domain.statecontribution.ReturnCause
import com.openbank.pension.domain.statecontribution.ReturnStatus
import com.openbank.pension.domain.statecontribution.StateContributionReturn
import com.openbank.pension.infrastructure.persistence.funding.PgFundingSupport
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import io.vertx.pgclient.PgException
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/**
 * Raw SQL over the reactive pool, the same pattern as the S3 funding stores. The insert is
 * `ON CONFLICT (source_key) DO NOTHING`, so the idempotency guarantee is in the statement itself
 * (V10).
 */
// One repository for the return lifecycle and its reports, which share a transaction (fileReportAtomically).
@Suppress("TooManyFunctions")
@Singleton
class PgStateContributionReturns(client: Pool) :
    PgFundingSupport(client),
    StateContributionReturnRepository {

    override suspend fun bySourceKey(sourceKey: String): StateContributionReturn? =
        rows("$SELECT WHERE source_key = $1", Tuple.of(sourceKey)).firstOrNull()?.toReturn()

    override suspend fun insertWithMonths(item: StateContributionReturn, months: Map<YearMonth, BigDecimal>): Boolean =
        client.withTransaction { conn ->
            conn.preparedQuery("SELECT 1 FROM pension_contracts WHERE contract_id = $1 FOR UPDATE")
                .execute(Tuple.of(item.contractId))
                .flatMap {
                    conn.preparedQuery(
                        """
                        INSERT INTO pension_state_contribution_returns (id, contract_id, claim_id, cause, amount,
                            currency, discovered_on, due_by, source_key, status, report_id, created_at, updated_at)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
                        ON CONFLICT (source_key) DO NOTHING
                        """.trimIndent(),
                    ).execute(
                        Tuple.tuple(
                            listOf(
                                item.id, item.contractId, item.claimId, item.cause.name, item.amount, item.currency,
                                item.discoveredOn, item.dueBy, item.sourceKey, item.status.name, item.reportId,
                                utc(item.createdAt), utc(item.updatedAt),
                            ),
                        ),
                    )
                }
                .flatMap { inserted ->
                    if (inserted.rowCount() != 1) return@flatMap Uni.createFrom().failure<Boolean>(MonthTaken())
                    if (months.isEmpty()) return@flatMap Uni.createFrom().item(true)
                    conn.preparedQuery(
                        "INSERT INTO pension_state_contribution_return_months " +
                            "(contract_id, claim_month, return_id, amount) " +
                            "VALUES ($1, $2, $3, $4)",
                    ).executeBatch(months.map { (m, amt) -> Tuple.of(item.contractId, m.toString(), item.id, amt) })
                        .map { true }
                }
        }.onFailure { it is MonthTaken || (it is PgException && it.sqlState == UNIQUE_VIOLATION) }
            .recoverWithItem(false).awaitSuspending()

    private class MonthTaken : RuntimeException("a contribution month is already owed back by another return")

    override suspend fun coveredMonths(contractId: UUID): Set<YearMonth> = rows(
        "SELECT claim_month FROM pension_state_contribution_return_months WHERE contract_id = $1",
        Tuple.of(contractId),
    ).map { YearMonth.parse(it.getString("claim_month")) }.toSet()

    override suspend fun findById(id: UUID): StateContributionReturn? =
        rows("$SELECT WHERE id = $1", Tuple.of(id)).firstOrNull()?.toReturn()

    override suspend fun byStatus(status: ReturnStatus): List<StateContributionReturn> =
        rows("$SELECT WHERE status = $1 ORDER BY due_by, created_at", Tuple.of(status.name)).map { it.toReturn() }

    override suspend fun byContract(contractId: UUID): List<StateContributionReturn> =
        rows("$SELECT WHERE contract_id = $1 ORDER BY created_at", Tuple.of(contractId)).map { it.toReturn() }

    override suspend fun update(item: StateContributionReturn) {
        exec(
            "UPDATE pension_state_contribution_returns SET status = $2, report_id = $3, updated_at = $4 WHERE id = $1",
            Tuple.of(item.id, item.status.name, item.reportId, utc(item.updatedAt)),
        )
    }

    override suspend fun fileReportAtomically(report: ReturnReport, at: Instant): Boolean {
        val ids = report.returnIds.toTypedArray()
        return client.withTransaction { conn ->
            conn.preparedQuery(
                "INSERT INTO pension_return_reports (id, month, return_ids, payload, channel_reference, " +
                    "result_applied, " +
                    "created_at) VALUES ($1, $2, $3, $4, $5, $6, $7)",
            ).execute(
                Tuple.tuple(
                    listOf(
                        report.id,
                        report.month.toString(),
                        report.returnIds.joinToString(","),
                        report.payload,
                        report.channelReference,
                        report.resultApplied,
                        utc(report.createdAt),
                    ),
                ),
            ).flatMap {
                conn.preparedQuery(
                    "UPDATE pension_state_contribution_returns SET status = 'REPORTED', report_id = $1, " +
                        "updated_at = $2 " +
                        "WHERE id = ANY($3) AND status = 'DUE'",
                ).execute(Tuple.of(report.id, utc(at), ids))
            }.flatMap { result ->
                if (result.rowCount() == ids.size) {
                    Uni.createFrom().item(true)
                } else {
                    Uni.createFrom().failure(LostReportRace())
                }
            }
        }.onFailure(LostReportRace::class.java).recoverWithItem(false).awaitSuspending()
    }

    private class LostReportRace : RuntimeException("return report lost a filing race")

    override suspend fun findReport(id: UUID): ReturnReport? =
        rows("$REPORT_SELECT WHERE id = $1", Tuple.of(id)).firstOrNull()?.toReport()

    override suspend fun reports(): List<ReturnReport> =
        rows("$REPORT_SELECT ORDER BY created_at DESC", Tuple.tuple()).map { it.toReport() }

    override suspend fun markReportResultApplied(id: UUID) {
        exec("UPDATE pension_return_reports SET result_applied = TRUE WHERE id = $1", Tuple.of(id))
    }

    override suspend fun setReportChannelReference(id: UUID, reference: String) {
        exec("UPDATE pension_return_reports SET channel_reference = $2 WHERE id = $1", Tuple.of(id, reference))
    }

    private companion object {
        const val SELECT = "SELECT * FROM pension_state_contribution_returns"
        const val UNIQUE_VIOLATION = "23505"
        const val REPORT_SELECT = "SELECT * FROM pension_return_reports"
    }
}

private fun Row.toReturn() = StateContributionReturn(
    id = getUUID("id"),
    contractId = getUUID("contract_id"),
    claimId = getUUID("claim_id"),
    cause = ReturnCause.valueOf(getString("cause")),
    amount = getBigDecimal("amount"),
    currency = getString("currency"),
    discoveredOn = getLocalDate("discovered_on"),
    dueBy = getLocalDate("due_by"),
    sourceKey = getString("source_key"),
    status = ReturnStatus.valueOf(getString("status")),
    reportId = getUUID("report_id"),
    createdAt = getOffsetDateTime("created_at").toInstant(),
    updatedAt = getOffsetDateTime("updated_at").toInstant(),
)

private fun Row.toReport() = ReturnReport(
    id = getUUID("id"),
    month = YearMonth.parse(getString("month")),
    returnIds = getString("return_ids").split(",").filter { it.isNotBlank() }.map(UUID::fromString),
    payload = getString("payload"),
    channelReference = getString("channel_reference"),
    resultApplied = getBoolean("result_applied"),
    createdAt = getOffsetDateTime("created_at").toInstant(),
)

private fun utc(i: Instant): OffsetDateTime = i.atOffset(ZoneOffset.UTC)
