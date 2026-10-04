// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fx.application.port.`in`.CnbPolicyRateUseCase
import com.openbank.fx.application.port.out.CnbPolicyRateFeed
import com.openbank.fx.application.port.out.CnbPolicyRateProvenance
import com.openbank.fx.application.port.out.CnbPolicyRateRepository
import com.openbank.fx.application.port.out.CnbPolicyRateUpsertOutcome
import com.openbank.fx.domain.cnb.CnbMinimumReserveParser
import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.fx.domain.cnb.CnbPolicyRateFact
import com.openbank.fx.domain.cnb.CnbPolicyRateParser
import com.openbank.fx.domain.event.CnbPolicyRatePublished
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/**
 * ČNB policy rates (2W repo, discount, lombard — text history files) and the minimum-reserve
 * ratio and remuneration (the `PMR_historie_zmen.xlsx` workbook), all downloaded periodically.
 *
 * A feed is ingested all-or-nothing: the bytes are hashed for provenance, decoded as strict UTF-8
 * (a mis-decoded file fails rather than storing mojibake), parsed by [CnbPolicyRateParser] — which
 * rejects the whole file on any malformed row — and upserted with the outbox rows for every new or
 * revised fact in one transaction. The ČNB does not revise history, so a revision is logged at
 * WARN and counted rather than applied silently.
 */
@ApplicationScoped
class CnbPolicyRateService(
    private val feed: CnbPolicyRateFeed,
    private val repository: CnbPolicyRateRepository,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : CnbPolicyRateUseCase {

    private val log = Logger.getLogger(CnbPolicyRateService::class.java)

    override suspend fun ingest(instrument: CnbPolicyInstrument): CnbPolicyRateUpsertOutcome {
        require(instrument.feedHeader != null) { "$instrument has no machine-readable feed" }
        val document = feed.fetch(instrument)
        val observations = CnbPolicyRateParser.parse(decode(document.body, instrument), instrument)
        val provenance = CnbPolicyRateProvenance(document.sourceUrl, Instant.now(clock), sha256(document.body))
        return repository.upsertAndPublish(instrument, observations, provenance, ::event).also { warnRevisions(it) }
    }

    private fun warnRevisions(outcome: CnbPolicyRateUpsertOutcome) {
        outcome.revisions.forEach {
            log.warnf(
                "ČNB %s rate effective %s REVISED from %s to %s — the ČNB does not normally revise history",
                it.instrument,
                it.effectiveFrom,
                it.previousRate.toPlainString(),
                it.rate.toPlainString(),
            )
        }
    }

    override suspend fun ingestMinimumReserves(): Map<CnbPolicyInstrument, CnbPolicyRateUpsertOutcome> {
        val document = feed.fetchMinimumReserves()
        // Parsed in full BEFORE any write: an anomaly anywhere in the workbook stores nothing.
        val parsed = CnbMinimumReserveParser.parse(document.body)
        val provenance = CnbPolicyRateProvenance(document.sourceUrl, Instant.now(clock), sha256(document.body))
        return mapOf(
            CnbPolicyInstrument.MIN_RESERVE_RATIO to parsed.ratio,
            CnbPolicyInstrument.MIN_RESERVE_REMUNERATION to parsed.remuneration,
        ).mapValues { (instrument, observations) ->
            repository.upsertAndPublish(instrument, observations, provenance, ::event).also { warnRevisions(it) }
        }
    }

    override suspend fun effectiveAt(instrument: CnbPolicyInstrument, asOf: LocalDate): CnbPolicyRateFact? =
        repository.findEffective(instrument, asOf)

    private fun event(fact: CnbPolicyRateFact): OutboxMessage {
        val now = Instant.now(clock)
        val payload = CnbPolicyRatePublished(
            instrument = fact.instrument.name,
            effectiveFrom = fact.effectiveFrom,
            rate = fact.rate,
            sourceUrl = fact.sourceUrl,
            fetchedAt = fact.fetchedAt,
            contentSha256 = fact.contentSha256,
            note = fact.note,
            revised = fact.previousRate != null,
            previousRate = fact.previousRate,
            occurredAt = now,
        )
        return OutboxMessage(
            // Deterministic per (instrument, effectiveFrom): the partition and compaction key, so a
            // revision lands after — and compacts over — the original.
            aggregateId = aggregateId(fact.instrument, fact.effectiveFrom),
            eventType = CnbPolicyRatePublished.EVENT_TYPE,
            payload = objectMapper.writeValueAsString(payload),
            createdAt = now,
        )
    }

    private fun decode(body: ByteArray, instrument: CnbPolicyInstrument): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(body))
            .toString()
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("ČNB $instrument history is not valid UTF-8", e)
    }

    companion object {
        fun aggregateId(instrument: CnbPolicyInstrument, effectiveFrom: LocalDate): UUID =
            UUID.nameUUIDFromBytes("CNB-POLICY:$instrument:$effectiveFrom".toByteArray())

        fun sha256(body: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
    }
}
