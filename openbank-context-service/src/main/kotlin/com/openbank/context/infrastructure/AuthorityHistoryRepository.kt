// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.context.domain.AuthorityEvidence
import com.openbank.context.domain.AuthorityHistory
import com.openbank.context.domain.RecordedAuthorityEvidence
import com.openbank.libs.domain.identifiers.Ids
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "context_authority_history")
class AuthorityHistoryEntity {
    @Id
    @Column(name = "observation_id")
    lateinit var id: UUID

    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Column(name = "delegation_id")
    lateinit var delegationId: UUID

    @Column(name = "revision")
    var revision: Long = 0

    @Column(name = "event_type")
    lateinit var eventType: String

    @Column(name = "evidence")
    lateinit var evidence: String

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant

    @Column(name = "recorded_at")
    lateinit var recordedAt: Instant

    @Column(name = "evidence_ref")
    lateinit var evidenceRef: String

    @Column(name = "content_hash")
    lateinit var contentHash: String
}

@ApplicationScoped
class AuthorityHistoryRepository(
    private val sessions: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val timeoutMs: Int,
) {
    suspend fun append(evidence: AuthorityEvidence) {
        val json = mapper.writeValueAsString(evidence)
        val hash = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        transaction { session ->
            session.createNativeMutationQuery(
                """INSERT INTO context_authority_history
                    (observation_id, bank_scope, delegation_id, revision, event_type, evidence, occurred_at,
                     evidence_ref, content_hash)
                    VALUES (:id, :bank, :delegation, :revision, :type, :evidence, :occurred,
                     :ref, :hash) ON CONFLICT (bank_scope, delegation_id, revision) DO NOTHING
                """.trimIndent(),
            ).setParameter("id", Ids.newId()).setParameter("bank", bankScope)
                .setParameter("delegation", evidence.delegationId).setParameter("revision", evidence.revision)
                .setParameter("type", evidence.eventType).setParameter("evidence", json)
                .setParameter("occurred", evidence.occurredAt)
                .setParameter("ref", "delegation:${evidence.delegationId}:${evidence.revision}")
                .setParameter("hash", hash).executeUpdate().flatMap {
                    session.createQuery(
                        "select contentHash from AuthorityHistoryEntity where bankScope = :bank and " +
                            "delegationId = :delegation and revision = :revision",
                        String::class.java,
                    ).setParameter("bank", bankScope).setParameter("delegation", evidence.delegationId)
                        .setParameter("revision", evidence.revision).singleResult
                }.invoke { stored -> check(stored == hash) { "conflicting authority evidence revision" } }
        }.bounded().awaitSuspending()
    }

    /** PostgreSQL owns recordedAt; use its clock for an omitted knownAt cutoff. */
    suspend fun databaseNow(): Instant = transaction { session ->
        session.createNativeQuery("select clock_timestamp()", OffsetDateTime::class.java).singleResult
    }.bounded().awaitSuspending().toInstant()

    suspend fun history(id: UUID, effectiveAt: Instant, knownAt: Instant): AuthorityHistory {
        val rows = transaction { session ->
            session.createQuery(
                "from AuthorityHistoryEntity where bankScope = :bank and delegationId = :id " +
                    "and occurredAt <= :effectiveAt and recordedAt <= :knownAt order by revision desc",
                AuthorityHistoryEntity::class.java,
            ).setParameter("bank", bankScope).setParameter("id", id).setParameter("effectiveAt", effectiveAt)
                .setParameter("knownAt", knownAt).setMaxResults(MAX_OBSERVATIONS + 1).resultList
        }.bounded().awaitSuspending()
        return AuthorityHistory(
            "delegation:$id",
            effectiveAt,
            knownAt,
            rows.take(MAX_OBSERVATIONS).map {
                RecordedAuthorityEvidence(
                    mapper.readValue(it.evidence, AuthorityEvidence::class.java),
                    it.recordedAt,
                    it.evidenceRef,
                    it.contentHash,
                )
            },
            rows.size > MAX_OBSERVATIONS,
        )
    }

    private fun <T> transaction(block: (Mutiny.Session) -> Uni<T>): Uni<T> = sessions.withTransaction { session, _ ->
        session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
            .setParameter("bank", bankScope).singleResult.flatMap {
                session.createNativeQuery("select set_config('statement_timeout', :timeout, true)", String::class.java)
                    .setParameter("timeout", "${timeoutMs}ms").singleResult
            }.flatMap { block(session) }
    }

    private fun <T> Uni<T>.bounded(): Uni<T> = ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail()

    private companion object {
        const val MAX_OBSERVATIONS = 100
    }
}
