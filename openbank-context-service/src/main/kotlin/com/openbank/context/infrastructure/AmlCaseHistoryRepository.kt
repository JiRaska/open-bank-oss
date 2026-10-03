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
import java.time.Instant
import java.time.OffsetDateTime
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
    /** Candidate discovery is constrained to roots already assigned to this investigator. */
    suspend fun assignedRelatedCases(root: AmlCaseHistory, principalId: String, at: Instant): List<UUID> {
        val observations = root.observations.map { it.evidence }
        if (observations.isEmpty()) return emptyList()
        val parties = observations.map { it.partyId }.distinct()
        val accounts = observations.mapNotNull { it.accountId }.distinct().ifEmpty { listOf(UUID(0, 0)) }
        val transactions = observations.mapNotNull { it.transactionId }.distinct().ifEmpty { listOf(UUID(0, 0)) }
        val ids = transaction { session ->
            session.createNativeQuery(
                """SELECT DISTINCT cast(related.case_id as text)
                   FROM context_aml_case_evidence related
                   JOIN context_case_assignments assignment
                     ON assignment.bank_scope = related.bank_scope
                    AND assignment.case_id = cast(related.case_id as text)
                    AND assignment.root_ref = 'aml-case:' || cast(related.case_id as text)
                   WHERE related.bank_scope = :bank
                     AND related.case_id <> :root
                     AND related.occurred_at <= :effective
                     AND related.recorded_at <= :known
                     AND (related.party_id IN (:parties) OR related.account_id IN (:accounts)
                          OR related.transaction_id IN (:transactions))
                     AND assignment.principal_id = :principal
                     AND assignment.purpose = 'AML_INVESTIGATION'
                     AND assignment.valid_from <= :now AND assignment.valid_to > :now
                   ORDER BY cast(related.case_id as text)
                """.trimIndent(),
                String::class.java,
            ).setParameter("bank", bankScope)
                .setParameter("root", UUID.fromString(root.root.removePrefix("aml-case:")))
                .setParameter("effective", root.effectiveAt).setParameter("known", root.knownAt)
                .setParameter("parties", parties).setParameter("accounts", accounts)
                .setParameter("transactions", transactions)
                .setParameter("principal", principalId).setParameter("now", at)
                .setMaxResults(MAX_RELATED_CASES).resultList
        }.awaitSuspending()
        return ids.map(UUID::fromString)
    }

    suspend fun append(evidence: AmlCaseObservation) {
        val json = mapper.writeValueAsString(evidence)
        val hash = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        scopedOperation { operation ->
            operation.sql { session ->
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
                    .setParameter("evidence", json).setParameter("hash", hash).executeUpdate()
            }.flatMap {
                operation.sql { session ->
                    session.createQuery(
                        "select contentHash from AmlCaseEvidenceEntity where bankScope = :bank and eventId = :event",
                        String::class.java,
                    ).setParameter("bank", bankScope).setParameter("event", evidence.eventId).singleResult
                }
            }.invoke { stored -> check(stored == hash) { "conflicting AML event identifier" } }
        }.awaitSuspending()
    }

    /** PostgreSQL owns recordedAt; use its clock for an omitted knownAt cutoff. */
    suspend fun databaseNow(): Instant = transaction { session ->
        session.createNativeQuery("select clock_timestamp()", OffsetDateTime::class.java).singleResult
    }.awaitSuspending().toInstant()

    suspend fun history(
        id: UUID,
        effectiveAt: Instant,
        knownAt: Instant,
        observationLimit: Int = MAX_OBSERVATIONS,
    ): AmlCaseHistory {
        require(observationLimit in 1..MAX_OBSERVATIONS) { "invalid AML observation limit" }
        val rows = transaction { session ->
            session.createQuery(
                "from AmlCaseEvidenceEntity where bankScope = :bank and caseId = :id " +
                    "and occurredAt <= :effectiveAt and recordedAt <= :knownAt " +
                    "order by occurredAt desc, eventId",
                AmlCaseEvidenceEntity::class.java,
            ).setParameter("bank", bankScope).setParameter("id", id)
                .setParameter("effectiveAt", effectiveAt).setParameter("knownAt", knownAt)
                .setMaxResults(observationLimit + 1).resultList
        }.awaitSuspending()
        return AmlCaseHistory(
            "aml-case:$id",
            effectiveAt,
            knownAt,
            rows.take(observationLimit).map { row ->
                RecordedAmlCaseObservation(
                    mapper.readValue(row.evidence, AmlCaseObservation::class.java),
                    row.recordedAt,
                    "aml-case:${row.caseId}:${row.eventId}",
                    row.contentHash,
                )
            },
            rows.size > observationLimit,
        )
    }

    private fun <T> transaction(statement: (Mutiny.Session) -> Uni<T>): Uni<T> =
        scopedOperation { operation -> operation.sql(statement) }

    private fun <T> scopedOperation(block: (ContextSqlOperation) -> Uni<T>): Uni<T> =
        ContextSqlOperation.execute(sessions, timeoutMs) { operation ->
            operation.sql { session ->
                session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                    .setParameter("bank", bankScope).singleResult
            }.flatMap { block(operation) }
        }

    private companion object {
        const val MAX_OBSERVATIONS = 100
        const val MAX_RELATED_CASES = 4
    }
}
