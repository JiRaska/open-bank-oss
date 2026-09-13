// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.infrastructure.catalog

import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.domain.model.InterestRateType
import com.openbank.interest.infrastructure.client.CatalogEventClientResponse
import com.openbank.interest.infrastructure.client.ProductCatalogClient
import com.openbank.interest.infrastructure.persistence.entity.CatalogInterestEventReceiptEntity
import com.openbank.interest.infrastructure.persistence.entity.CatalogInterestRateSnapshotEntity
import com.openbank.interest.infrastructure.persistence.entity.CatalogInterestSyncStateEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestRateConfigEntity
import com.openbank.interest.infrastructure.persistence.mapper.InterestMapper
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.hibernate.reactive.mutiny.Mutiny
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

internal enum class CatalogInterestSyncOutcome { IDLE, APPLIED, SKIPPED, REJECTED, FAILED }

/**
 * One-event-at-a-time durable consumer for catalog revisions.
 *
 * Catalog's cursor points after a page, not each element inside it.  Reading with `limit=1` is
 * intentional: the rate write, receipt and cursor acknowledgement can then commit in one local
 * transaction. A crash retries the same immutable event; it can never skip the remainder of a
 * fetched page.
 */
@ApplicationScoped
internal class CatalogInterestProfileSynchronizer @Inject constructor(
    @RestClient private val catalog: ProductCatalogClient,
    private val repository: CatalogInterestSyncRepository,
) {
    private val log = Logger.getLogger(CatalogInterestProfileSynchronizer::class.java)

    fun synchronizeOne(): Uni<CatalogInterestSyncOutcome> = repository.cursor().flatMap { cursor ->
        catalog.events(cursor, 1)
    }.flatMap { page ->
        if (page.items.isEmpty()) {
            Uni.createFrom().item(CatalogInterestSyncOutcome.IDLE)
        } else {
            require(page.items.size == 1) { "catalog event page must contain one item" }
            val nextCursor = requireNotNull(page.nextCursor) { "catalog event page has item without nextCursor" }
            resolve(page.items.single()).flatMap { resolution -> repository.record(resolution, nextCursor) }
        }
    }

    private fun resolve(event: CatalogEventClientResponse): Uni<CatalogInterestEventResolution> {
        if (event.eventType != REVISION_PUBLISHED) {
            return Uni.createFrom().item(
                CatalogInterestEventResolution.skipped(event, "event is not a published revision"),
            )
        }
        return catalog.revision(event.aggregateId).flatMap { revision ->
            catalog.offering(revision.offeringId).map { offering ->
                try {
                    CatalogInterestEventResolution.applied(
                        event,
                        CatalogInterestProfileParser.parse(revision, offering),
                    )
                } catch (e: IllegalArgumentException) {
                    CatalogInterestEventResolution.rejected(event, e.message ?: "catalog profile is not executable")
                }
            }
        }.onFailure(CatalogInterestProfileRejected::class.java).recoverWithItem { e ->
            // Parser rejections are durable operational outcomes; transport/authorization failures
            // deliberately remain failures so the cursor is not advanced and the event retries.
            log.warnf("catalog revision %s rejected: %s", event.aggregateId, e.message)
            CatalogInterestEventResolution.rejected(event, e.message ?: "catalog profile is not executable")
        }
    }

    private companion object {
        const val REVISION_PUBLISHED = "com.openbank.catalog.revision_published"
    }
}

internal data class CatalogInterestEventResolution(
    val event: CatalogEventClientResponse,
    val profile: CatalogInterestProfile?,
    val outcome: CatalogInterestSyncOutcome,
    val reason: String?,
) {
    companion object {
        fun applied(event: CatalogEventClientResponse, profile: CatalogInterestProfile) =
            CatalogInterestEventResolution(event, profile, CatalogInterestSyncOutcome.APPLIED, null)
        fun skipped(event: CatalogEventClientResponse, reason: String) =
            CatalogInterestEventResolution(event, null, CatalogInterestSyncOutcome.SKIPPED, reason)
        fun rejected(event: CatalogEventClientResponse, reason: String) =
            CatalogInterestEventResolution(event, null, CatalogInterestSyncOutcome.REJECTED, reason)
    }
}

/** Owns the local all-or-nothing acknowledgement boundary. */
@ApplicationScoped
@Suppress("TooManyFunctions") // Transactional substeps intentionally stay co-located with the cursor acknowledgement.
internal class CatalogInterestSyncRepository @Inject constructor(
    private val sessions: Mutiny.SessionFactory,
    private val mapper: InterestMapper,
    private val clock: Clock,
) {
    @WithSession
    fun cursor(): Uni<String?> = sessions.withSession { session ->
        session.find(CatalogInterestSyncStateEntity::class.java, CONSUMER).map { it?.cursor }
    }

    fun record(resolution: CatalogInterestEventResolution, nextCursor: String): Uni<CatalogInterestSyncOutcome> =
        sessions.withTransaction { session ->
            val now = OffsetDateTime.now(clock)
            session.find(CatalogInterestEventReceiptEntity::class.java, resolution.event.id).flatMap { receipt ->
                if (receipt != null) {
                    acknowledge(session, nextCursor, now).replaceWith(receipt.outcome.toSyncOutcome())
                } else {
                    persistResolution(session, resolution, now).flatMap { outcome ->
                        acknowledge(session, nextCursor, now).replaceWith(outcome)
                    }
                }
            }
        }

    private fun persistResolution(
        session: Mutiny.Session,
        resolution: CatalogInterestEventResolution,
        now: OffsetDateTime,
    ): Uni<CatalogInterestSyncOutcome> {
        val profile = resolution.profile
        if (profile == null) return persistReceipt(session, resolution, now, resolution.outcome)
        return session.find(CatalogInterestRateSnapshotEntity::class.java, profile.revisionId).flatMap { existing ->
            if (existing != null) {
                persistReceipt(session, resolution, now, existing.outcome.toSyncOutcome())
            } else {
                applyProfile(session, resolution, profile, now)
            }
        }
    }

    private fun applyProfile(
        session: Mutiny.Session,
        resolution: CatalogInterestEventResolution,
        profile: CatalogInterestProfile,
        now: OffsetDateTime,
    ): Uni<CatalogInterestSyncOutcome> = findOverlappingConfigs(session, profile).flatMap { configs ->
        catalogConfigIds(session, profile).flatMap { ids ->
            applyProfileAgainstExisting(session, resolution, profile, now, configs, ids.toSet())
        }
    }

    private fun catalogConfigIds(session: Mutiny.Session, profile: CatalogInterestProfile): Uni<List<UUID>> =
        session.createQuery(
            "SELECT s.configId FROM CatalogInterestRateSnapshotEntity s WHERE s.specificationId = :spec AND s.currency = :currency AND s.outcome = 'APPLIED'",
            UUID::class.java,
        ).setParameter("spec", profile.specificationId).setParameter("currency", profile.currency).resultList

    private fun applyProfileAgainstExisting(
        session: Mutiny.Session,
        resolution: CatalogInterestEventResolution,
        profile: CatalogInterestProfile,
        now: OffsetDateTime,
        configs: List<InterestRateConfigEntity>,
        catalogIds: Set<UUID>,
    ): Uni<CatalogInterestSyncOutcome> {
        val rejection = when {
            configs.any { it.id !in catalogIds } -> "overlaps an operator-managed rate configuration"
            configs.any { it.effectiveFrom >= profile.effectiveFrom } ->
                "overlaps a catalog rate at the same or later effective date"
            else -> null
        }
        if (rejection != null) return persistRejectedProfile(session, resolution, profile, now, rejection)
        configs.forEach { config ->
            config.effectiveTo = profile.effectiveFrom.minusDays(1)
            config.updatedAt = now
        }
        return persistAppliedProfile(session, resolution, profile, now)
    }

    private fun persistAppliedProfile(
        session: Mutiny.Session,
        resolution: CatalogInterestEventResolution,
        profile: CatalogInterestProfile,
        now: OffsetDateTime,
    ): Uni<CatalogInterestSyncOutcome> {
        val config = mapper.toEntity(
            InterestRateConfig(
                productId = profile.specificationId.toString(),
                currency = profile.currency,
                rateType = InterestRateType.FIXED,
                annualRate = profile.annualRate,
                dayCount = profile.dayCount,
                effectiveFrom = profile.effectiveFrom,
                effectiveTo = profile.effectiveTo,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return session.persist(config).flatMap {
            val snapshot = CatalogInterestRateSnapshotEntity().also {
                it.revisionId = profile.revisionId
                it.offeringId = profile.offeringId
                it.specificationId = profile.specificationId
                it.configId = config.id
                it.schemaId = profile.schemaId
                it.schemaVersion = profile.schemaVersion
                it.contentHash = profile.contentHash
                it.currency = profile.currency
                it.annualRate = profile.annualRate
                it.dayCount = profile.dayCount.name
                it.effectiveFrom = profile.effectiveFrom
                it.effectiveTo = profile.effectiveTo
                it.outcome = "APPLIED"
                it.createdAt = now
            }
            session.persist(snapshot).flatMap {
                persistReceipt(session, resolution, now, CatalogInterestSyncOutcome.APPLIED)
            }
        }
    }

    private fun findOverlappingConfigs(
        session: Mutiny.Session,
        profile: CatalogInterestProfile,
    ): Uni<List<InterestRateConfigEntity>> = session.createQuery(
        "FROM InterestRateConfigEntity WHERE productId = :product AND accountId IS NULL AND currency = :currency " +
            "AND active = true AND effectiveFrom <= :end AND (effectiveTo IS NULL OR effectiveTo >= :start)",
        InterestRateConfigEntity::class.java,
    ).setParameter("product", profile.specificationId.toString())
        .setParameter("currency", profile.currency)
        .setParameter("start", profile.effectiveFrom)
        .setParameter("end", profile.effectiveTo ?: LocalDate.MAX)
        .resultList

    private fun persistRejectedProfile(
        session: Mutiny.Session,
        resolution: CatalogInterestEventResolution,
        profile: CatalogInterestProfile,
        now: OffsetDateTime,
        reason: String,
    ): Uni<CatalogInterestSyncOutcome> {
        val snapshot = CatalogInterestRateSnapshotEntity().also {
            it.revisionId = profile.revisionId
            it.offeringId = profile.offeringId
            it.specificationId = profile.specificationId
            it.schemaId = profile.schemaId
            it.schemaVersion = profile.schemaVersion
            it.contentHash = profile.contentHash
            it.outcome = "REJECTED"
            it.reason = reason
            it.createdAt = now
        }
        return session.persist(snapshot).flatMap {
            persistReceipt(session, resolution.copy(reason = reason), now, CatalogInterestSyncOutcome.REJECTED)
        }
    }

    private fun persistReceipt(
        session: Mutiny.Session,
        resolution: CatalogInterestEventResolution,
        now: OffsetDateTime,
        outcome: CatalogInterestSyncOutcome,
    ): Uni<CatalogInterestSyncOutcome> {
        val receipt = CatalogInterestEventReceiptEntity().also {
            it.eventId = resolution.event.id
            it.eventType = resolution.event.eventType
            it.outcome = outcome.name
            it.reason = resolution.reason?.take(MAX_REASON_LENGTH)
            it.processedAt = now
        }
        return session.persist(receipt).replaceWith(outcome)
    }

    private fun acknowledge(session: Mutiny.Session, cursor: String, now: OffsetDateTime): Uni<Void> =
        session.find(CatalogInterestSyncStateEntity::class.java, CONSUMER).flatMap { state ->
            val mutable = state ?: CatalogInterestSyncStateEntity().also { it.consumer = CONSUMER }
            mutable.cursor = cursor
            mutable.updatedAt = now
            if (state == null) session.persist(mutable) else Uni.createFrom().voidItem()
        }

    private fun String.toSyncOutcome(): CatalogInterestSyncOutcome = when (this) {
        "APPLIED" -> CatalogInterestSyncOutcome.APPLIED
        "REJECTED" -> CatalogInterestSyncOutcome.REJECTED
        else -> CatalogInterestSyncOutcome.SKIPPED
    }

    private companion object {
        const val CONSUMER = "interest-rate-snapshots"
        const val MAX_REASON_LENGTH = 512
    }
}
