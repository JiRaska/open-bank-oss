// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.infrastructure

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class KybObservationReferenceKey(var bankScope: String = "", var eventId: UUID = UUID(0, 0)) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

@Entity
@IdClass(KybObservationReferenceKey::class)
@Table(name = "context_kyb_observation_references")
class KybObservationReferenceEntity {
    @Id
    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Id
    @Column(name = "event_id")
    lateinit var eventId: UUID

    @Column(name = "case_id")
    lateinit var caseId: UUID

    @Column(name = "observation_id")
    lateinit var observationId: UUID

    @Column(name = "revision")
    var revision: Long = 0

    @Column(name = "source_sha256")
    lateinit var sourceSha256: String

    @Column(name = "recorded_at")
    lateinit var recordedAt: Instant
}

data class KybObservationRestrictionKey(var bankScope: String = "", var observationId: UUID = UUID(0, 0)) :
    Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/** A durable tombstone; arrival before the recorded event still hides the observation. */
@Entity
@IdClass(KybObservationRestrictionKey::class)
@Table(name = "context_kyb_observation_restrictions")
class KybObservationRestrictionEntity {
    @Id
    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Id
    @Column(name = "observation_id")
    lateinit var observationId: UUID

    @Column(name = "event_id")
    lateinit var eventId: UUID

    @Column(name = "case_id")
    lateinit var caseId: UUID

    @Column(name = "revision")
    var revision: Long = 0

    @Column(name = "source_sha256")
    lateinit var sourceSha256: String
}

/** Bounded reference history. recordedAt is Context ingestion time, not KYB finding time. */
data class KybObservationHistory(
    val root: String,
    val knownAt: Instant,
    val observations: List<KybObservationSummary>,
    val truncated: Boolean,
)

data class KybObservationSummary(
    val observationId: UUID,
    val revision: Long,
    val sourceSha256: String,
    val recordedAt: Instant,
)

@ApplicationScoped
class KybObservationReferenceRepository(
    private val sessions: Mutiny.SessionFactory,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val timeoutMs: Int,
) {
    suspend fun history(caseId: UUID, knownAt: Instant): KybObservationHistory {
        val rows = transaction { session ->
            session.createQuery(
                "from KybObservationReferenceEntity r where r.bankScope = :bank and r.caseId = :caseId " +
                    "and r.recordedAt <= :knownAt and not exists (" +
                    "select 1 from KybObservationRestrictionEntity t where t.bankScope = r.bankScope " +
                    "and t.observationId = r.observationId) order by r.revision desc, r.recordedAt desc",
                KybObservationReferenceEntity::class.java,
            ).setParameter("bank", bankScope).setParameter("caseId", caseId).setParameter("knownAt", knownAt)
                .setMaxResults(MAX_OBSERVATIONS + 1).resultList
        }.ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail().awaitSuspending()
        return KybObservationHistory(
            root = "kyb-case:$caseId",
            knownAt = knownAt,
            observations = rows.take(MAX_OBSERVATIONS).map {
                KybObservationSummary(it.observationId, it.revision, it.sourceSha256, it.recordedAt)
            },
            truncated = rows.size > MAX_OBSERVATIONS,
        )
    }

    suspend fun append(reference: KybObservationReference) {
        transaction { session ->
            session.createNativeMutationQuery(
                """INSERT INTO context_kyb_observation_references
                   (bank_scope, event_id, case_id, observation_id, revision, source_sha256)
                   VALUES (:bank, :event, :case, :observation, :revision, :hash)
                   ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).setParameter("bank", bankScope).setParameter("event", reference.eventId)
                .setParameter("case", reference.caseId).setParameter("observation", reference.observationId)
                .setParameter("revision", reference.revision).setParameter("hash", reference.sourceSha256)
                .executeUpdate().flatMap {
                    session.createQuery(
                        "from KybObservationReferenceEntity where bankScope = :bank and eventId = :event",
                        KybObservationReferenceEntity::class.java,
                    ).setParameter("bank", bankScope).setParameter("event", reference.eventId).singleResultOrNull
                }.invoke { row ->
                    check(
                        row != null &&
                            row.caseId == reference.caseId &&
                            row.observationId == reference.observationId &&
                            row.revision == reference.revision &&
                            row.sourceSha256 == reference.sourceSha256,
                    ) {
                        "conflicting KYB observation reference"
                    }
                }
        }.ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail().awaitSuspending()
    }

    suspend fun restrict(reference: KybObservationReference) {
        transaction { session ->
            session.createNativeMutationQuery(
                """INSERT INTO context_kyb_observation_restrictions
                   (bank_scope, observation_id, event_id, case_id, revision, source_sha256)
                   VALUES (:bank, :observation, :event, :case, :revision, :hash)
                   ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).setParameter("bank", bankScope).setParameter("observation", reference.observationId)
                .setParameter("event", reference.eventId).setParameter("case", reference.caseId)
                .setParameter("revision", reference.revision).setParameter("hash", reference.sourceSha256)
                .executeUpdate().flatMap {
                    session.createQuery(
                        "from KybObservationRestrictionEntity where bankScope = :bank and observationId = :observation",
                        KybObservationRestrictionEntity::class.java,
                    ).setParameter(
                        "bank",
                        bankScope,
                    ).setParameter("observation", reference.observationId).singleResultOrNull
                }.invoke { row ->
                    check(
                        row != null &&
                            row.eventId == reference.eventId &&
                            row.caseId == reference.caseId &&
                            row.revision == reference.revision &&
                            row.sourceSha256 == reference.sourceSha256,
                    ) { "conflicting KYB observation restriction" }
                }
        }.ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail().awaitSuspending()
    }

    private fun <T> transaction(block: (Mutiny.Session) -> Uni<T>): Uni<T> = sessions.withTransaction { session, _ ->
        session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
            .setParameter("bank", bankScope).singleResult.flatMap {
                session.createNativeQuery("select set_config('statement_timeout', :timeout, true)", String::class.java)
                    .setParameter("timeout", "${timeoutMs}ms").singleResult
            }.flatMap { block(session) }
    }

    private companion object {
        const val MAX_OBSERVATIONS = 50
    }
}
