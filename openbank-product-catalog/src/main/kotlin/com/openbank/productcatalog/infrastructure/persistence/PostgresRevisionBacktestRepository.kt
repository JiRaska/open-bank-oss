// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.productcatalog.application.CatalogPreconditionFailedException
import com.openbank.productcatalog.application.port.out.RevisionBacktestRepository
import com.openbank.productcatalog.domain.catalog.RevisionBacktest
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.LockMode
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

/** V17 `catalog_revision_backtests`; the table's trigger forbids UPDATE and DELETE. */
@ApplicationScoped
class PostgresRevisionBacktestRepository(private val sessions: Mutiny.SessionFactory) : RevisionBacktestRepository {

    override suspend fun record(backtest: RevisionBacktest, expectedRowVersion: Long) {
        sessions.withTransaction { session ->
            session.find(
                CatalogRevisionEntity::class.java,
                backtest.revisionId,
                LockMode.PESSIMISTIC_WRITE,
            ).flatMap { row ->
                if (row == null || row.revision != expectedRowVersion) {
                    throw CatalogPreconditionFailedException(
                        "revision ${backtest.revisionId} changed during the backtest; run it again",
                    )
                }
                persist(session, backtest)
            }
        }.awaitSuspending()
    }

    private fun persist(session: Mutiny.Session, backtest: RevisionBacktest) = session.persist(
        RevisionBacktestEntity().apply {
            id = Ids.newId()
            revisionId = backtest.revisionId
            digest = backtest.digest
            principalName = backtest.principalName
            issuer = backtest.issuer
            subject = backtest.subject
            portfolioName = backtest.portfolioName
            portfolioSha256 = backtest.portfolioSha256
            identical = backtest.identical
            changedAccounts = backtest.changedAccounts
            baseRevisionHash = backtest.baseRevisionHash
            candidateRevisionHash = backtest.candidateRevisionHash
            diffSha256 = backtest.diffSha256
            recordedAt = backtest.recordedAt
        },
    )

    override suspend fun find(revisionId: UUID, digest: String): List<RevisionBacktest> =
        sessions.withSession { session ->
            session.createQuery(
                "FROM RevisionBacktestEntity WHERE revisionId = :id AND digest = :digest ORDER BY recordedAt",
                RevisionBacktestEntity::class.java,
            ).setParameter("id", revisionId).setParameter("digest", digest).resultList
        }.awaitSuspending().map {
            RevisionBacktest(
                it.revisionId, it.digest, it.principalName, it.issuer, it.subject, it.portfolioName,
                it.portfolioSha256, it.identical, it.changedAccounts, it.baseRevisionHash, it.candidateRevisionHash,
                it.diffSha256, it.recordedAt,
            )
        }
}
