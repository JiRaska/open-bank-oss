// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.fincrime.pack.CountShadowAppendOutcome
import com.openbank.libs.fincrime.pack.CountShadowEpoch
import com.openbank.libs.fincrime.pack.CountShadowEpochStore
import com.openbank.libs.fincrime.pack.PackDomain
import com.openbank.libs.fincrime.pack.RecordedCountShadowEpoch
import com.openbank.libs.fincrime.pack.SignedCountShadowEpoch
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@ApplicationScoped
class FraudCountShadowEpochStore(private val pool: PgPool, private val mapper: ObjectMapper) : CountShadowEpochStore {
    override suspend fun latest(domain: PackDomain): RecordedCountShadowEpoch? {
        requireFraud(domain)
        return pool.query("SELECT * FROM fraud_count_shadow_epoch ORDER BY epoch_number DESC LIMIT 1")
            .execute().awaitSuspending().firstOrNull()?.toRecord()
    }

    override suspend fun recorded(domain: PackDomain, number: Long): RecordedCountShadowEpoch? {
        requireFraud(domain)
        return pool.preparedQuery("SELECT * FROM fraud_count_shadow_epoch WHERE epoch_number = $1")
            .execute(Tuple.of(number)).awaitSuspending().firstOrNull()?.toRecord()
    }

    override suspend fun effectiveAt(domain: PackDomain, at: Instant): RecordedCountShadowEpoch? {
        requireFraud(domain)
        return pool.preparedQuery(
            "SELECT * FROM fraud_count_shadow_epoch WHERE effective_at <= $1 ORDER BY effective_at DESC LIMIT 1",
        ).execute(Tuple.of(at.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)))
            .awaitSuspending().firstOrNull()?.toRecord()
    }

    override suspend fun append(record: RecordedCountShadowEpoch): CountShadowAppendOutcome {
        requireFraud(record.domain)
        val epoch = record.envelope.epoch
        require(epoch.effectiveAt == epoch.effectiveAt.truncatedTo(ChronoUnit.MICROS)) {
            "shadow epoch timestamps must fit the database precision"
        }
        val exactRecord = record.copy(recordedAt = record.recordedAt.truncatedTo(ChronoUnit.MICROS))
        if (!followsPredecessor(record)) return CountShadowAppendOutcome.CONFLICT

        val stored = StoredEnvelope(epoch, record.envelope.signature)
        val result = pool.preparedQuery(INSERT).execute(
            Tuple.tuple()
                .addLong(epoch.number)
                .addLong(record.previousNumber)
                .addUUID(epoch.proposalId)
                .addString(record.envelope.digest)
                .addString(mapper.writeValueAsString(stored))
                .addOffsetDateTime(exactRecord.recordedAt.atOffset(ZoneOffset.UTC))
                .addOffsetDateTime(epoch.effectiveAt.atOffset(ZoneOffset.UTC)),
        ).awaitSuspending()
        if (result.rowCount() == 1) return CountShadowAppendOutcome.APPENDED
        return if (recorded(record.domain, epoch.number)?.envelope == record.envelope) {
            CountShadowAppendOutcome.REPLAYED
        } else {
            CountShadowAppendOutcome.CONFLICT
        }
    }

    private suspend fun followsPredecessor(record: RecordedCountShadowEpoch): Boolean {
        // Mirror the chain CHECK so a mis-linked record answers CONFLICT instead of a constraint error.
        if (record.previousNumber != (record.envelope.epoch.number - 1).takeIf { it > 0 }) return false
        val previousNumber = record.previousNumber ?: return true
        val previous = recorded(record.domain, previousNumber)?.envelope?.epoch ?: return false
        val epoch = record.envelope.epoch
        return epoch.effectiveAt > previous.effectiveAt && epoch.replay.baseline == previous.replay.candidate
    }

    private fun Row.toRecord(): RecordedCountShadowEpoch {
        val stored = mapper.readValue(getString("envelope_json"), StoredEnvelope::class.java)
        val signed = SignedCountShadowEpoch(stored.epoch, stored.signature)
        val matchingEpoch = stored.epoch.number == getLong("epoch_number") &&
            stored.epoch.proposalId == getUUID("proposal_id")
        val matchingEvidence = stored.epoch.effectiveAt == getOffsetDateTime("effective_at").toInstant() &&
            signed.digest == getString("envelope_digest")
        require(matchingEpoch && matchingEvidence) {
            "fraud shadow epoch row does not match its exact publisher envelope"
        }
        return RecordedCountShadowEpoch(
            PackDomain.FRAUD,
            getLong("previous_epoch_number"),
            signed,
            getOffsetDateTime("recorded_at").toInstant(),
        )
    }

    private fun requireFraud(domain: PackDomain) = require(domain == PackDomain.FRAUD) {
        "only fraud shadow epochs belong in the fraud ledger"
    }

    private data class StoredEnvelope(val epoch: CountShadowEpoch, val signature: String)

    private companion object {
        // The predecessor is immutable. The unique epoch key selects one competing writer, and
        // the predecessor foreign key prevents a gap. A proposal cannot be reused on another epoch.
        const val INSERT = """
            INSERT INTO fraud_count_shadow_epoch (epoch_number, previous_epoch_number, proposal_id, envelope_digest,
                                                envelope_json, recorded_at, effective_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            ON CONFLICT DO NOTHING
        """
    }
}
