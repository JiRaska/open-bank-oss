// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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

    override suspend fun getCnbRate(base: String, quote: String): FxRate? =
        rateRepo.findLatestBySource(base.uppercase(), quote.uppercase(), RateSource.CNB)

    private companion object {
        const val QUOTE = "CZK"

        // 3-day window covers weekends and public holidays until the next scheduler run (14:40 Prague)
        const val CNB_VALIDITY_DAYS = 3L
    }
}
