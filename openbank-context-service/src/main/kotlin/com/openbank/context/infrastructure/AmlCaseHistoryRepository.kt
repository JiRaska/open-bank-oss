// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.util.UUID

@Entity
@Table(name = "context_aml_case_evidence")
class AmlCaseEvidenceEntity {
    @Id
    @Column(name = "event_id")
    lateinit var eventId: UUID

    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Column(name = "case_id")
    lateinit var caseId: UUID

    @Column(name = "party_id")
    lateinit var partyId: UUID

    @Column(name = "account_id")
    var accountId: UUID? = null

    @Column(name = "transaction_id")
    var transactionId: UUID? = null

    @Column(name = "event_type")
    lateinit var eventType: String

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant

    @Column(name = "recorded_at")
    lateinit var recordedAt: Instant

    @Column(name = "evidence")
    lateinit var evidence: String

    @Column(name = "content_hash")
    lateinit var contentHash: String
}

data class RecordedAmlCaseObservation(
    val evidence: AmlCaseObservation,
    val recordedAt: Instant,
    val evidenceRef: String,
    val contentHash: String,
)

data class AmlCaseHistory(
    val root: String,
    val effectiveAt: Instant,
    val knownAt: Instant,
    val observations: List<RecordedAmlCaseObservation>,
    val truncated: Boolean,
)

@ApplicationScoped
class AmlCaseHistoryRepository(
    private val sessions: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val timeoutMs: Int,
) {
    suspend fun append(evidence: AmlCaseObservation) {
        val json = mapper.writeValueAsString(evidence)
        val hash = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        transaction { session ->
            session.createNativeMutationQuery(
                """INSERT INTO context_aml_case_evidence
                   (bank_scope, event_id, case_id, party_id, account_id, transaction_id,
                    event_type, occurred_at, evidence, content_hash)
                   VALUES (:bank, :event, :case, :party, :account, :transaction, :type, :occurred,
                           :evidence, :hash)
                   ON CONFLICT (bank_scope, event_id) DO NOTHING
                """.trimIndent(),
            ).setParameter("bank", bankScope).setParameter("event", evidence.eventId)
                .setParameter("case", evidence.caseId).setParameter("party", evidence.partyId)
                .setParameter("account", evidence.accountId).setParameter("transaction", evidence.transactionId)
                .setParameter("type", evidence.eventType).setParameter("occurred", evidence.occurredAt)
                .setParameter("evidence", json).setParameter("hash", hash).executeUpdate().flatMap {
                    session.createQuery(
                        "select contentHash from AmlCaseEvidenceEntity where bankScope = :bank and eventId = :event",
                        String::class.java,
                    ).setParameter("bank", bankScope).setParameter("event", evidence.eventId).singleResult
                }.invoke { stored -> check(stored == hash) { "conflicting AML event identifier" } }
        }.bounded().awaitSuspending()
    }

    suspend fun history(id: UUID, effectiveAt: Instant, knownAt: Instant): AmlCaseHistory {
        val rows = transaction { session ->
            session.createQuery(
                "from AmlCaseEvidenceEntity where bankScope = :bank and caseId = :id " +
                    "and occurredAt <= :effectiveAt and recordedAt <= :knownAt " +
                    "order by occurredAt desc, eventId",
                AmlCaseEvidenceEntity::class.java,
            ).setParameter("bank", bankScope).setParameter("id", id)
                .setParameter("effectiveAt", effectiveAt).setParameter("knownAt", knownAt)
                .setMaxResults(MAX_OBSERVATIONS + 1).resultList
        }.bounded().awaitSuspending()
        return AmlCaseHistory(
            "aml-case:$id",
            effectiveAt,
            knownAt,
            rows.take(MAX_OBSERVATIONS).map { row ->
                RecordedAmlCaseObservation(
                    mapper.readValue(row.evidence, AmlCaseObservation::class.java),
                    row.recordedAt,
                    "aml-case:${row.caseId}:${row.eventId}",
                    row.contentHash,
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
