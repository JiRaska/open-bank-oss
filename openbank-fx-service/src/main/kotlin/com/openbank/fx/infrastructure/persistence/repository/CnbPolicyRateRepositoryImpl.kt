// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.persistence.repository

import com.openbank.fx.application.port.out.CnbPolicyRateProvenance
import com.openbank.fx.application.port.out.CnbPolicyRateRepository
import com.openbank.fx.application.port.out.CnbPolicyRateRevision
import com.openbank.fx.application.port.out.CnbPolicyRateUpsertOutcome
import com.openbank.fx.application.port.out.FxOutboxRepository
import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.fx.domain.cnb.CnbPolicyRateFact
import com.openbank.fx.domain.cnb.CnbPolicyRateObservation
import com.openbank.fx.domain.cnb.CnbPolicyRateUpsert
import com.openbank.fx.infrastructure.persistence.entity.CnbPolicyRateEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `cnb_policy_rate` with its outbox rows. Updates go through MANAGED entities loaded in the same
 * session (dirty checking), and inserts through `persist` of fresh ids, so the
 * persist-is-INSERT-only trap of an application-assigned id never applies to an existing row.
 */
@ApplicationScoped
class CnbPolicyRateRepositoryImpl(private val clock: Clock) : CnbPolicyRateRepository {
    @Inject
    lateinit var sf: Mutiny.SessionFactory

    @Inject
    lateinit var outboxRepo: FxOutboxRepository

    override suspend fun upsertAndPublish(
        instrument: CnbPolicyInstrument,
        observations: List<CnbPolicyRateObservation>,
        provenance: CnbPolicyRateProvenance,
        event: (CnbPolicyRateFact) -> OutboxMessage,
    ): CnbPolicyRateUpsertOutcome {
        val now = Instant.now(clock)
        return sf.withTransaction { s, _ ->
            s.createQuery("from CnbPolicyRateEntity where instrument = :i", CnbPolicyRateEntity::class.java)
                .setParameter("i", instrument.name)
                .resultList
                .chain { existing ->
                    val byDate = existing.associateBy { it.effectiveFrom }
                    val fresh = mutableListOf<CnbPolicyRateEntity>()
                    val revisions = mutableListOf<CnbPolicyRateRevision>()
                    var unchanged = 0
                    for (o in observations) {
                        val row = byDate[o.effectiveFrom]
                        when {
                            row == null -> fresh += newRow(instrument, o, provenance, now)
                            row.rate.compareTo(o.rate) == 0 -> unchanged++
                            else -> {
                                revisions += CnbPolicyRateRevision(instrument, o.effectiveFrom, row.rate, o.rate)
                                row.previousRate = row.rate
                                row.rate = o.rate
                                row.revisedAt = now
                                row.sourceUrl = provenance.sourceUrl
                                row.fetchedAt = provenance.fetchedAt
                                row.contentSha256 = provenance.contentSha256
                                row.publishedAt = null
                            }
                        }
                    }
                    fresh.fold(Uni.createFrom().voidItem()) { acc, e -> acc.chain { _ -> s.persist(e) } }
                        .chain { _ -> s.flush() }
                        .chain { _ -> publishPendingIn(s, listOf(instrument), event, now) }
                        .map { published ->
                            CnbPolicyRateUpsertOutcome(
                                CnbPolicyRateUpsert(fresh.size, unchanged, revisions.size),
                                revisions,
                                published,
                            )
                        }
                }
        }.awaitSuspending()
    }

    override suspend fun findEffective(instrument: CnbPolicyInstrument, asOf: LocalDate): CnbPolicyRateFact? =
        sf.withSession { s ->
            s.createQuery(
                "from CnbPolicyRateEntity where instrument = :i and effectiveFrom <= :d order by effectiveFrom desc",
                CnbPolicyRateEntity::class.java,
            ).setParameter("i", instrument.name).setParameter("d", asOf).setMaxResults(1).singleResultOrNull
        }.awaitSuspending()?.toFact()

    /** One outbox row per unpublished row, in effective-date order, then marks them published. */
    private fun publishPendingIn(
        s: Mutiny.Session,
        instruments: Collection<CnbPolicyInstrument>,
        event: (CnbPolicyRateFact) -> OutboxMessage,
        now: Instant,
    ): Uni<Int> = s.createQuery(
        "from CnbPolicyRateEntity where instrument in (:is) and publishedAt is null order by instrument, effectiveFrom",
        CnbPolicyRateEntity::class.java,
    ).setParameter("is", instruments.map { it.name })
        .resultList
        .chain { pending ->
            pending.fold(Uni.createFrom().voidItem()) { acc, row ->
                acc.chain { _ -> outboxRepo.persistInTransaction(event(row.toFact())) }
                    .invoke { _ -> row.publishedAt = now }
            }.map { pending.size }
        }

    private fun newRow(
        instrument: CnbPolicyInstrument,
        o: CnbPolicyRateObservation,
        provenance: CnbPolicyRateProvenance,
        now: Instant,
    ) = CnbPolicyRateEntity().also {
        it.id = UUID.randomUUID()
        it.instrument = instrument.name
        it.effectiveFrom = o.effectiveFrom
        it.rate = o.rate
        it.sourceUrl = provenance.sourceUrl
        it.fetchedAt = provenance.fetchedAt
        it.contentSha256 = provenance.contentSha256
        it.createdAt = now
    }

    private fun CnbPolicyRateEntity.toFact() = CnbPolicyRateFact(
        instrument = CnbPolicyInstrument.valueOf(instrument),
        effectiveFrom = effectiveFrom,
        rate = rate,
        sourceUrl = sourceUrl,
        fetchedAt = requireNotNull(fetchedAt) { "cnb_policy_rate $id has no fetched_at" },
        contentSha256 = contentSha256,
        note = note,
        previousRate = previousRate,
        revisedAt = revisedAt,
    )
}
