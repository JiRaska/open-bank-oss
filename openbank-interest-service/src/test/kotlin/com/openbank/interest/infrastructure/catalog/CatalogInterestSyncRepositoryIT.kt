// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.infrastructure.catalog

import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.infrastructure.client.CatalogEventClientResponse
import com.openbank.interest.infrastructure.persistence.entity.CatalogInterestEventReceiptEntity
import com.openbank.interest.infrastructure.persistence.entity.CatalogInterestRateSnapshotEntity
import com.openbank.interest.infrastructure.persistence.entity.CatalogInterestSyncStateEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestRateConfigEntity
import com.openbank.interest.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Proves the local acknowledgement boundary with the real PostgreSQL schema. */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
internal class CatalogInterestSyncRepositoryIT {
    @Inject
    lateinit var repository: CatalogInterestSyncRepository

    @Inject
    lateinit var sessions: Mutiny.SessionFactory

    @Test
    fun `published fixed rate is exact, durable and idempotent`() {
        val eventId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val profile = CatalogInterestProfile(
            revisionId = revisionId,
            offeringId = UUID.randomUUID(),
            specificationId = UUID.randomUUID(),
            schemaId = "org.openbank.banking.deposit",
            schemaVersion = 2,
            contentHash = "a".repeat(64),
            currency = "EUR",
            annualRate = BigDecimal("0.012345678901234567"),
            dayCount = DayCount.ACT_365,
            effectiveFrom = LocalDate.of(2027, 1, 1),
            effectiveTo = null,
        )
        val resolution = CatalogInterestEventResolution.applied(event(eventId, revisionId), profile)

        assertThat(onEventLoop { repository.record(resolution, "cursor-1") })
            .isEqualTo(CatalogInterestSyncOutcome.APPLIED)
        assertThat(onEventLoop { repository.record(resolution, "cursor-2") })
            .isEqualTo(CatalogInterestSyncOutcome.APPLIED)

        val persisted = onEventLoop {
            sessions.withSession { session ->
                // Hibernate Reactive sessions do not permit concurrent queries. The production write
                // transaction is sequential too; this readback deliberately follows that contract.
                session.find(CatalogInterestRateSnapshotEntity::class.java, revisionId).flatMap { snapshot ->
                    session.find(CatalogInterestEventReceiptEntity::class.java, eventId).flatMap { receipt ->
                        session.find(
                            CatalogInterestSyncStateEntity::class.java,
                            "interest-rate-snapshots",
                        ).flatMap { cursor ->
                            session.find(InterestRateConfigEntity::class.java, snapshot.configId).map { config ->
                                PersistedState(snapshot, receipt, cursor, config)
                            }
                        }
                    }
                }
            }
        }

        assertThat(persisted.snapshot.outcome).isEqualTo("APPLIED")
        assertThat(persisted.snapshot.annualRate).isEqualByComparingTo("0.012345678901234567")
        assertThat(persisted.config.productId).isEqualTo(profile.specificationId.toString())
        assertThat(persisted.config.annualRate).isEqualByComparingTo("0.012345678901234567")
        assertThat(persisted.receipt.outcome).isEqualTo("APPLIED")
        assertThat(persisted.cursor.cursor).isEqualTo("cursor-2")
        assertThat(countSnapshots(revisionId)).isEqualTo(1)
        assertThat(countConfigs(profile.specificationId)).isEqualTo(1)
    }

    private fun event(eventId: UUID, revisionId: UUID) = CatalogEventClientResponse(
        id = eventId,
        aggregateType = "catalog.revision",
        eventType = "com.openbank.catalog.revision_published",
        aggregateId = revisionId,
        occurredAt = OffsetDateTime.now(ZoneOffset.UTC),
    )

    private fun countSnapshots(revisionId: UUID): Long = onEventLoop {
        sessions.withSession { session ->
            session.createQuery(
                "SELECT COUNT(s) FROM CatalogInterestRateSnapshotEntity s WHERE s.revisionId = :revision",
                Long::class.javaObjectType,
            ).setParameter("revision", revisionId).singleResult.map { it.toLong() }
        }
    }

    private fun countConfigs(specificationId: UUID): Long = onEventLoop {
        sessions.withSession { session ->
            session.createQuery(
                "SELECT COUNT(c) FROM InterestRateConfigEntity c WHERE c.productId = :product",
                Long::class.javaObjectType,
            ).setParameter("product", specificationId.toString()).singleResult.map { it.toLong() }
        }
    }

    private fun <T> onEventLoop(work: () -> Uni<T>): T = VertxContextSupport.subscribeAndAwait(work)

    private data class PersistedState(
        val snapshot: CatalogInterestRateSnapshotEntity,
        val receipt: CatalogInterestEventReceiptEntity,
        val cursor: CatalogInterestSyncStateEntity,
        val config: InterestRateConfigEntity,
    )
}
