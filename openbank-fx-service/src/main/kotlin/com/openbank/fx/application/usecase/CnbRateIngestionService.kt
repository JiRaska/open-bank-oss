// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.usecase

import com.openbank.fx.application.port.`in`.CnbIngestionResult
import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.IngestCnbFixingCommand
import com.openbank.fx.application.port.out.CnbRateProvider
import com.openbank.fx.application.port.out.FxRateRepository
import com.openbank.fx.domain.cnb.CnbFixingParser
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Fetches the ČNB central-bank fixing, parses it (pure [CnbFixingParser]), and upserts the
 * configured currencies as `source = CNB` [FxRate]s quoted in CZK.
 *
 * Per ADR-0046: the fixing is a mid rate with no bank spread, so it is stored with
 * `bid = ask = mid = ratePerUnit`, `rateType = INDICATIVE`, valid for the fixing's business day
 * (`validFrom = fixing date 00:00 Europe/Prague`, `validTo = next day 00:00`). Ingestion is
 * idempotent on `(source = CNB, pair, validFrom)`, so re-running the same day is a no-op.
 */
@ApplicationScoped
class CnbRateIngestionService(
    private val provider: CnbRateProvider,
    private val rateRepo: FxRateRepository,
    @ConfigProperty(name = "openbank.cnb.currencies", defaultValue = "EUR,USD,GBP")
    private val enabledCurrencies: String,
    private val clock: Clock,
) : CnbRateIngestionUseCase {

    /**
     * The ČNB **publication** calendar, deliberately NOT the accounting day
     * ([com.openbank.libs.domain.calendar.AccountingClock.BANK_ZONE]) — issue #2963 asked which of
     * the two this means, and the answer is the publication one.
     *
     * The ČNB declares a fixing for a named business day and publishes it at 14:30 Prague time;
     * that day's boundaries are properties of the *publisher*, so `validFrom`/`validTo` here mark
     * the window a published fixing is the current one, not a window in the bank's books. The
     * distinction is invisible today because the ČNB is a Prague institution and this bank keeps
     * its books in Prague, so the two constants hold the same value — but they are answers to
     * different questions and would have to move independently if either ever changed (a
     * non-Czech entity consuming the ČNB fixing; a ČNB calendar change). Binding this to
     * `AccountingClock` would make the coincidence look like a dependency.
     *
     * This is why the `check-accounting-clock.py` ALLOWLIST entry for this file is KEPT rather
     * than removed: the gate's rule ("the accounting zone is declared once") is correct and this
     * value is not the accounting zone. The entry's reason now records a decision instead of an
     * open question.
     */
    private val zone: ZoneId = ZoneId.of("Europe/Prague")

    private val enabled: Set<String>
        get() = enabledCurrencies.split(',')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    override suspend fun ingest(cmd: IngestCnbFixingCommand): CnbIngestionResult {
        val fixing = CnbFixingParser.parse(provider.fetchFixing(cmd.date))
        val validFrom = fixing.date.atStartOfDay(zone).toInstant()
        val validTo = fixing.date.plusDays(CNB_VALIDITY_DAYS).atStartOfDay(zone).toInstant()
        val wanted = enabled

        var ingested = 0
        var skipped = 0
        val stored = mutableListOf<String>()

        for (rate in fixing.rates) {
            if (rate.code !in wanted) continue
            if (rateRepo.findBySourceAndValidFrom(rate.code, QUOTE, RateSource.CNB, validFrom) != null) {
                skipped++
                continue
            }
            val perUnit = rate.ratePerUnit
            rateRepo.save(
                FxRate(
                    id = UUID.randomUUID(),
                    baseCurrency = rate.code,
                    quoteCurrency = QUOTE,
                    bidRate = perUnit,
                    askRate = perUnit,
                    rateType = RateType.INDICATIVE,
                    source = RateSource.CNB,
                    validFrom = validFrom,
                    validTo = validTo,
                    createdAt = Instant.now(clock),
                ),
            )
            ingested++
            stored += rate.code
        }

        return CnbIngestionResult(fixing.date, fixing.sequence, ingested, skipped, stored)
    }

    override suspend fun getCnbRate(base: String, quote: String, asOf: LocalDate?): FxRate? {
        val b = base.uppercase()
        val q = quote.uppercase()
        // No asOf: the live daily path, unchanged — "the latest fixing still valid right now".
        if (asOf == null) return rateRepo.findLatestBySource(b, q, RateSource.CNB)
        // With asOf: the fixing that was in effect at the START of that business day, in the ČNB
        // publication zone the validity bounds above are written in. Start-of-day, not end-of-day:
        // a fixing published for day D carries validFrom = D 00:00 Prague, so `validFrom <= at`
        // admits D's own fixing while `validTo > at` still admits the Friday fixing that carries a
        // Saturday and a Sunday. End-of-day would additionally admit a fixing whose window closed
        // during the day, which is the stale-mark this is meant to prevent (#3921).
        return rateRepo.findBySourceAsOf(b, q, RateSource.CNB, asOf.atStartOfDay(zone).toInstant())
    }

    private companion object {
        const val QUOTE = "CZK"

        // 3-day window covers weekends and public holidays until the next scheduler run (14:40 Prague)
        const val CNB_VALIDITY_DAYS = 3L
    }
}
