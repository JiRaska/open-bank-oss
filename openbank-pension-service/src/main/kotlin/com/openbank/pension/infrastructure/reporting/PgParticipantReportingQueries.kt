// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.reporting

import com.openbank.pension.application.port.out.ParticipantReportingQueries
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.reporting.ContributionTotals
import com.openbank.pension.domain.reporting.InForceGroup
import com.openbank.pension.domain.reporting.PayoutLine
import com.openbank.pension.domain.reporting.PayoutTotals
import com.openbank.pension.domain.reporting.StateContributionFigures
import com.openbank.pension.domain.reporting.StatusBuckets
import com.openbank.pension.domain.reporting.TransferTotals
import com.openbank.pension.infrastructure.persistence.funding.PgFundingSupport
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Every statement here ends in an aggregate (COUNT/SUM ... GROUP BY): the reporting read model is
 * computed in the database and only numbers cross into the JVM (#12425).
 *
 * Interval convention: [from, to] closed on dates. A TIMESTAMPTZ is compared against
 * `[from 00:00Z, to+1 00:00Z)` so the last day is whole and the answer never depends on the
 * session time zone. Payouts use domestic-payment's persisted SETTLED transition time; legacy
 * SETTLED rows without a persisted event time make the report fail until independently reconciled.
 */
// One query per reported figure, and column indexes into each aggregate row.
@Suppress("TooManyFunctions", "MagicNumber")
@Singleton
class PgParticipantReportingQueries(client: Pool) :
    PgFundingSupport(client),
    ParticipantReportingQueries {

    override suspend fun hasUnknownSettlementTime(to: LocalDate): Boolean = count(
        """
        SELECT COUNT(*) FROM pension_payment_instructions
         WHERE status = 'SETTLED' AND settled_at IS NULL AND created_at < $1
        """.trimIndent(),
        Tuple.of(endExclusive(to)),
    ) > 0

    override suspend fun currencies(from: LocalDate, to: LocalDate): Set<String> = rows(
        """
        SELECT DISTINCT currency FROM pension_contributions WHERE value_date BETWEEN $1 AND $2
        UNION
        SELECT DISTINCT currency FROM pension_payment_instructions
         WHERE settled_at >= $3 AND settled_at < $4 AND status = 'SETTLED'
        """.trimIndent(),
        Tuple.of(from, to, start(from), endExclusive(to)),
    ).map { it.getString(0).trim() }.toSet()

    override suspend fun inForce(asOf: LocalDate): List<InForceGroup> = rows(
        """
        SELECT product_line,
               EXTRACT(YEAR FROM age($1::date, participant_birth_date))::int AS age_years,
               CASE WHEN updated_at < $2 THEN status ELSE '${StatusBuckets.CHANGED_AFTER_PERIOD_END}' END AS status_at_end,
               COUNT(*) AS n
          FROM pension_contracts
         WHERE start_date IS NOT NULL AND start_date <= $1
           AND NOT (status IN ($TERMINAL) AND updated_at < $2)
         GROUP BY 1, 2, 3
        """.trimIndent(),
        Tuple.of(asOf, endExclusive(asOf)),
    ).map { InForceGroup(it.getString(0), it.getInteger(1), it.getString(2), it.getLong(3)) }

    override suspend fun startedBetween(from: LocalDate, to: LocalDate): Long = count(
        "SELECT COUNT(*) FROM pension_contracts WHERE start_date BETWEEN $1 AND $2",
        Tuple.of(from, to),
    )

    override suspend fun exitedBetween(from: LocalDate, to: LocalDate): Long = count(
        """
        SELECT COUNT(*) FROM pension_contracts
         WHERE start_date IS NOT NULL AND status IN ($TERMINAL) AND updated_at >= $1 AND updated_at < $2
        """.trimIndent(),
        Tuple.of(start(from), endExclusive(to)),
    )

    override suspend fun contributingBetween(from: LocalDate, to: LocalDate): Long = count(
        """
        SELECT COUNT(DISTINCT contract_id) FROM pension_contributions
         WHERE source IN ('PARTICIPANT', 'EMPLOYER') AND value_date BETWEEN $1 AND $2
        """.trimIndent(),
        Tuple.of(from, to),
    )

    override suspend fun pensionersBetween(from: LocalDate, to: LocalDate): Long = count(
        """
        SELECT COUNT(DISTINCT contract_id) FROM pension_payment_instructions
         WHERE purpose IN ($PENSION_PURPOSES) AND status <> 'REJECTED' AND created_at >= $1 AND created_at < $2
        """.trimIndent(),
        Tuple.of(start(from), endExclusive(to)),
    )

    override suspend fun contributions(from: LocalDate, to: LocalDate): ContributionTotals {
        val bySource = rows(
            """
            SELECT source, COALESCE(SUM(amount), 0) FROM pension_contributions
             WHERE value_date BETWEEN $1 AND $2 GROUP BY source
            """.trimIndent(),
            Tuple.of(from, to),
        ).associate { it.getString(0) to it.money(1) }
        return ContributionTotals(
            participant = bySource["PARTICIPANT"] ?: ZERO,
            employer = bySource["EMPLOYER"] ?: ZERO,
            state = bySource["STATE"] ?: ZERO,
            transferIn = bySource["TRANSFER_IN"] ?: ZERO,
        )
    }

    override suspend fun stateContributions(from: LocalDate, to: LocalDate): StateContributionFigures {
        val claimed = rows(
            """
            SELECT COALESCE(SUM(claimed_amount), 0) FROM pension_incentive_claims
             WHERE period >= $1 AND period <= $2 AND status <> 'REJECTED'
            """.trimIndent(),
            Tuple.of(month(from), month(to)),
        ).single().money(0)
        val ledger = rows(
            """
            SELECT kind, COALESCE(SUM(amount), 0) FROM pension_incentive_ledger
             WHERE occurred_at >= $1 AND occurred_at < $2 GROUP BY kind
            """.trimIndent(),
            Tuple.of(start(from), endExclusive(to)),
        ).associate { it.getString(0) to it.money(1) }
        return StateContributionFigures(claimed, ledger["RECEIVED"] ?: ZERO, ledger["RETURNED"] ?: ZERO)
    }

    override suspend fun payouts(from: LocalDate, to: LocalDate): PayoutTotals {
        val window = Tuple.of(start(from), endExclusive(to))
        val byForm = rows(
            """
            SELECT purpose, COALESCE(SUM(amount), 0), COUNT(*) FROM pension_payment_instructions
             WHERE purpose IN ($PAYOUT_PURPOSES) AND status = 'SETTLED' AND settled_at >= $1 AND settled_at < $2
             GROUP BY purpose
            """.trimIndent(),
            window,
        ).associate { it.getString(0) to PayoutLine(it.money(1), it.getLong(2)) }
        val withheld = rows(
            """
            SELECT COALESCE(SUM(amount), 0) FROM pension_payment_instructions
             WHERE purpose LIKE '%WITHHOLDING' AND status = 'SETTLED' AND settled_at >= $1 AND settled_at < $2
            """.trimIndent(),
            window,
        ).single().money(0)
        val cases = count(
            """
            SELECT COUNT(DISTINCT contract_id) FROM pension_payment_instructions
             WHERE purpose IN ($PAYOUT_PURPOSES) AND status = 'SETTLED' AND settled_at >= $1 AND settled_at < $2
            """.trimIndent(),
            window,
        )
        return PayoutTotals(byForm.toSortedMap(), withheld, cases)
    }

    override suspend fun transfers(from: LocalDate, to: LocalDate): TransferTotals {
        val byDirection = rows(
            """
            SELECT direction, COUNT(*),
                   COALESCE(SUM(COALESCE((payload::jsonb ->> 'netAmount')::numeric, 0)), 0)
              FROM pension_transfer_requests
             WHERE status = 'COMPLETED' AND updated_at >= $1 AND updated_at < $2
             GROUP BY direction
            """.trimIndent(),
            Tuple.of(start(from), endExclusive(to)),
        ).associate { it.getString(0) to (it.getLong(1) to it.money(2)) }
        val inbound = byDirection["IN"] ?: (0L to ZERO)
        val outbound = byDirection["OUT"] ?: (0L to ZERO)
        return TransferTotals(inbound.first, inbound.second, outbound.first, outbound.second)
    }

    private suspend fun count(sql: String, args: Tuple): Long = rows(sql, args).single().getLong(0)

    private fun Row.money(i: Int): BigDecimal = getBigDecimal(i).setScale(MONEY_SCALE, java.math.RoundingMode.HALF_EVEN)

    private companion object {
        const val MONEY_SCALE = 2
        val ZERO: BigDecimal = BigDecimal.ZERO.setScale(MONEY_SCALE)
        const val TERMINAL = "'PAID_OUT', 'TRANSFERRED_OUT', 'CLOSED'"

        /** Money paid to the participant (or their estate) leaving the contract, by the form it left in. */
        val PAYOUT_PURPOSES = (
            PayoutForm.entries.map { it.name } +
                listOf("EARLY_TERMINATION", "DEATH_BENEFIT", "ANNUITY_PREMIUM")
            )
            .joinToString(", ") { "'$it'" }

        /** Recurring pension: what makes a participant a pensioner rather than a one-off payee. */
        val PENSION_PURPOSES = listOf("PHASED_WITHDRAWAL", "FIXED_PERIOD_PENSION", "ANNUITY", "ANNUITY_PREMIUM")
            .joinToString(", ") { "'$it'" }

        fun start(d: LocalDate): OffsetDateTime = d.atStartOfDay().atOffset(ZoneOffset.UTC)

        fun endExclusive(d: LocalDate): OffsetDateTime = d.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)

        fun month(d: LocalDate): String = "%04d-%02d".format(d.year, d.monthValue)
    }
}
