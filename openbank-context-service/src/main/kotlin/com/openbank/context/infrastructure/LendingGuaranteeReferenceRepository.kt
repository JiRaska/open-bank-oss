// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.context.infrastructure

import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Duration

/** Append-only pointer ledger, never a graph edge or an authorization source. */
@ApplicationScoped
class LendingGuaranteeReferenceRepository(
    private val sessions: Mutiny.SessionFactory,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val timeoutMs: Int,
) {
    suspend fun append(reference: LendingGuaranteeReference) {
        require(reference.bankScope == bankScope) { "cross-bank Lending reference" }
        sessions.withTransaction { session, _ ->
            session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                .setParameter("bank", bankScope).singleResult.flatMap {
                    session.createNativeQuery(
                        "select set_config('statement_timeout', :timeout, true)",
                        String::class.java,
                    )
                        .setParameter("timeout", "${timeoutMs}ms").singleResult
                }.flatMap {
                    session.createNativeMutationQuery(
                        """INSERT INTO context_lending_guarantee_references
                           (bank_scope, event_id, guarantee_id, loan_id, revision, occurred_at)
                           VALUES (:bank, :event, :guarantee, :loan, :revision, :occurredAt)
                           ON CONFLICT DO NOTHING""",
                    ).setParameter("bank", bankScope).setParameter("event", reference.eventId)
                        .setParameter("guarantee", reference.guaranteeId).setParameter("loan", reference.loanId)
                        .setParameter("revision", reference.revision).setParameter("occurredAt", reference.occurredAt)
                        .executeUpdate()
                }.flatMap {
                    session.createNativeQuery(
                        """SELECT event_id, guarantee_id, loan_id, revision, occurred_at
                           FROM context_lending_guarantee_references
                           WHERE bank_scope = :bank
                             AND (event_id = :event OR (guarantee_id = :guarantee AND revision = :revision))""",
                        Array<Any>::class.java,
                    ).setParameter("bank", bankScope).setParameter("event", reference.eventId)
                        .setParameter("guarantee", reference.guaranteeId).setParameter("revision", reference.revision)
                        .resultList
                }.invoke { rows ->
                    check(rows.size == 1) { "conflicting Lending reference identity" }
                    val row = rows.single()
                    check(
                        row[EVENT_ID] == reference.eventId &&
                            row[GUARANTEE_ID] == reference.guaranteeId &&
                            row[LOAN_ID] == reference.loanId &&
                            row[REVISION] == reference.revision &&
                            (row[OCCURRED_AT] as java.time.OffsetDateTime).toInstant() == reference.occurredAt,
                    ) { "conflicting Lending reference replay" }
                }
        }.ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail().awaitSuspending()
    }

    private companion object {
        // Positions in the native SELECT above.
        const val EVENT_ID = 0
        const val GUARANTEE_ID = 1
        const val LOAN_ID = 2
        const val REVISION = 3
        const val OCCURRED_AT = 4
    }
}
