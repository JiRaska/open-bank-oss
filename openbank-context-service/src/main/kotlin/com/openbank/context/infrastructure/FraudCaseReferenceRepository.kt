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

data class FraudCaseReferenceKey(var bankScope: String = "", var eventId: UUID = UUID(0, 0)) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

@Entity
@IdClass(FraudCaseReferenceKey::class)
@Table(name = "context_fraud_case_references")
class FraudCaseReferenceEntity {
    @Id
    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Id
    @Column(name = "event_id")
    lateinit var eventId: UUID

    @Column(name = "case_id")
    lateinit var caseId: UUID

    @Column(name = "event_type")
    lateinit var eventType: String

    @Column(name = "revision")
    var revision: Long = 0

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant

    @Column(name = "recorded_at")
    lateinit var recordedAt: Instant
}

/** Internal reference ledger. Read APIs must first authorize the live source case and assignment. */
@ApplicationScoped
class FraudCaseReferenceRepository(
    private val sessions: Mutiny.SessionFactory,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val timeoutMs: Int,
) {
    suspend fun append(reference: FraudCaseReference) {
        transaction { session ->
            session.createNativeMutationQuery(
                """INSERT INTO context_fraud_case_references
                   (bank_scope, event_id, case_id, event_type, revision, occurred_at)
                   VALUES (:bank, :event, :case, :type, :revision, :occurredAt)
                   ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).setParameter("bank", bankScope).setParameter("event", reference.eventId)
                .setParameter("case", reference.caseId).setParameter("type", reference.eventType)
                .setParameter("revision", reference.revision).setParameter("occurredAt", reference.occurredAt)
                .executeUpdate().flatMap {
                    session.createQuery(
                        "from FraudCaseReferenceEntity where bankScope = :bank and eventId = :event",
                        FraudCaseReferenceEntity::class.java,
                    ).setParameter("bank", bankScope).setParameter("event", reference.eventId).singleResultOrNull
                }.invoke { row ->
                    check(
                        row != null &&
                            row.caseId == reference.caseId &&
                            row.eventType == reference.eventType &&
                            row.revision == reference.revision &&
                            row.occurredAt == reference.occurredAt,
                    ) { "conflicting Fraud case reference" }
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
}
