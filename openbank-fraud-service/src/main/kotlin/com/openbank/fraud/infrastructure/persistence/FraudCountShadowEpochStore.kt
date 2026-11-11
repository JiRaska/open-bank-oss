// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fraud.infrastructure.persistence.entity.FraudCountShadowEpochEntity
import com.openbank.fraud.infrastructure.persistence.entity.FraudCountShadowEpochHeadEntity
import com.openbank.libs.fincrime.pack.CountShadowAppendOutcome
import com.openbank.libs.fincrime.pack.CountShadowEpoch
import com.openbank.libs.fincrime.pack.CountShadowEpochStore
import com.openbank.libs.fincrime.pack.PackDomain
import com.openbank.libs.fincrime.pack.RecordedCountShadowEpoch
import com.openbank.libs.fincrime.pack.SignedCountShadowEpoch
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant
import java.time.temporal.ChronoUnit

@ApplicationScoped
class FraudCountShadowEpochStore(private val mapper: ObjectMapper) : CountShadowEpochStore {
    override suspend fun latest(domain: PackDomain): RecordedCountShadowEpoch? {
        requireFraud(domain)
        return Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.find(FraudCountShadowEpochHeadEntity::class.java, HEAD_ID)
                    .flatMap { head -> epoch(session, requireNotNull(head).number) }
            }
        }.awaitSuspending()?.toRecord()
    }

    override suspend fun recorded(domain: PackDomain, number: Long): RecordedCountShadowEpoch? {
        requireFraud(domain)
        return Panache.withSession {
            Panache.getSession().flatMap { session -> epoch(session, number) }
        }.awaitSuspending()?.toRecord()
    }

    override suspend fun effectiveAt(domain: PackDomain, at: Instant): RecordedCountShadowEpoch? {
        requireFraud(domain)
        return Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createSelectionQuery(
                    "from FraudCountShadowEpochEntity where effectiveAt <= :at order by number desc",
                    FraudCountShadowEpochEntity::class.java,
                ).setParameter("at", at.truncatedTo(ChronoUnit.MICROS)).setMaxResults(1).singleResultOrNull
            }
        }.awaitSuspending()?.toRecord()
    }

    override suspend fun append(record: RecordedCountShadowEpoch): CountShadowAppendOutcome {
        requireFraud(record.domain)
        val incoming = record.envelope.epoch
        require(incoming.effectiveAt == incoming.effectiveAt.truncatedTo(ChronoUnit.MICROS)) {
            "shadow epoch timestamps must fit the database precision"
        }
        val exactRecord = record.copy(recordedAt = record.recordedAt.truncatedTo(ChronoUnit.MICROS))
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.find(FraudCountShadowEpochHeadEntity::class.java, HEAD_ID, LockModeType.PESSIMISTIC_WRITE)
                    .flatMap { head -> appendLocked(session, requireNotNull(head), exactRecord) }
            }
        }.awaitSuspending()
    }

    private fun appendLocked(
        session: Mutiny.Session,
        head: FraudCountShadowEpochHeadEntity,
        record: RecordedCountShadowEpoch,
    ): Uni<CountShadowAppendOutcome> {
        val incoming = record.envelope.epoch
        return epoch(session, incoming.number).flatMap { stored ->
            when {
                stored != null -> Uni.createFrom().item(
                    if (stored.toRecord().envelope == record.envelope) {
                        CountShadowAppendOutcome.REPLAYED
                    } else {
                        CountShadowAppendOutcome.CONFLICT
                    },
                )

                head.number != record.previousNumber -> Uni.createFrom().item(CountShadowAppendOutcome.CONFLICT)

                else -> appendFollowingHead(session, head, record)
            }
        }
    }

    private fun appendFollowingHead(
        session: Mutiny.Session,
        head: FraudCountShadowEpochHeadEntity,
        record: RecordedCountShadowEpoch,
    ): Uni<CountShadowAppendOutcome> {
        val incoming = record.envelope.epoch
        return epoch(session, head.number).flatMap { previous ->
            if (previous != null) {
                val last = previous.toRecord().envelope.epoch
                require(incoming.effectiveAt > last.effectiveAt && incoming.replay.baseline == last.replay.candidate) {
                    "fraud shadow publication must follow the locked epoch and artifact"
                }
            }
            session.createSelectionQuery(
                "from FraudCountShadowEpochEntity where proposalId = :id",
                FraudCountShadowEpochEntity::class.java,
            ).setParameter("id", incoming.proposalId).singleResultOrNull
        }.flatMap { duplicateProposal ->
            if (duplicateProposal != null) return@flatMap Uni.createFrom().item(CountShadowAppendOutcome.CONFLICT)
            session.persist(record.toEntity()).flatMap {
                head.number = incoming.number
                session.flush().replaceWith(CountShadowAppendOutcome.APPENDED)
            }
        }
    }

    private fun epoch(session: Mutiny.Session, number: Long?): Uni<FraudCountShadowEpochEntity?> = if (number == null) {
        Uni.createFrom().nullItem()
    } else {
        session.find(FraudCountShadowEpochEntity::class.java, number)
    }

    private fun RecordedCountShadowEpoch.toEntity() = FraudCountShadowEpochEntity().also {
        it.number = envelope.epoch.number
        it.previousNumber = previousNumber
        it.proposalId = envelope.epoch.proposalId
        it.digest = envelope.digest
        it.envelopeJson = mapper.writeValueAsString(StoredEnvelope(envelope.epoch, envelope.signature))
        it.recordedAt = recordedAt
        it.effectiveAt = envelope.epoch.effectiveAt
    }

    private fun FraudCountShadowEpochEntity.toRecord(): RecordedCountShadowEpoch {
        val stored = mapper.readValue(envelopeJson, StoredEnvelope::class.java)
        val signed = SignedCountShadowEpoch(stored.epoch, stored.signature)
        val matchingEpoch = signed.epoch.number == number && signed.epoch.proposalId == proposalId
        val matchingEvidence = signed.epoch.effectiveAt == effectiveAt && signed.digest == digest
        require(matchingEpoch && matchingEvidence) {
            "fraud shadow epoch row does not match its exact publisher envelope"
        }
        return RecordedCountShadowEpoch(PackDomain.FRAUD, previousNumber, signed, recordedAt)
    }

    private fun requireFraud(domain: PackDomain) = require(domain == PackDomain.FRAUD) {
        "only fraud shadow epochs belong in the fraud ledger"
    }

    private data class StoredEnvelope(val epoch: CountShadowEpoch, val signature: String)

    private companion object {
        const val HEAD_ID = 1
    }
}
