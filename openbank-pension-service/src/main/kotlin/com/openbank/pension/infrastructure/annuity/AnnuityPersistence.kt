// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity

import com.openbank.pension.application.annuity.AnnuityConcurrentUpdateException
import com.openbank.pension.application.annuity.AnnuityProviderRepository
import com.openbank.pension.application.annuity.AnnuityPurchaseRepository
import com.openbank.pension.domain.annuity.AnnuityProvider
import com.openbank.pension.domain.annuity.AnnuityProviderStatus
import com.openbank.pension.domain.annuity.AnnuityPurchase
import com.openbank.pension.domain.annuity.AnnuityPurchaseStatus
import com.openbank.pension.infrastructure.exit.persistence.ExitDocumentEntity
import com.openbank.pension.infrastructure.exit.persistence.ExitJson
import com.openbank.pension.infrastructure.exit.persistence.load
import com.openbank.pension.infrastructure.exit.persistence.page
import com.openbank.pension.infrastructure.exit.persistence.upsert
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/** One purchase per ANNUITY payout; same document layout and optimistic lock as the exit aggregates. */
@Entity
@Table(name = "pension_annuity_purchases")
class AnnuityPurchaseEntity : ExitDocumentEntity()

/** The partner registry row. Every column is named explicitly (entity-column-names). */
@Entity
@Table(name = "pension_annuity_providers")
class AnnuityProviderEntity : PanacheEntity() {
    @Column(name = "partner_id", nullable = false, unique = true)
    lateinit var partnerId: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "body", nullable = false)
    lateinit var body: String

    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Int = 0

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

@ApplicationScoped
class AnnuityPurchaseRepositoryImpl :
    AnnuityPurchaseRepository,
    PanacheRepository<AnnuityPurchaseEntity> {

    override suspend fun save(purchase: AnnuityPurchase): AnnuityPurchase {
        val version = upsert(
            purchase.id,
            purchase.contractId,
            purchase.status.name,
            null,
            purchase,
            purchase.updatedAt,
            purchase.version,
            ::AnnuityPurchaseEntity,
        )
        return purchase.copy(version = version)
    }

    override suspend fun findById(id: UUID): AnnuityPurchase? =
        load<AnnuityPurchaseEntity, AnnuityPurchase>("aggregateId", id).firstOrNull()

    override suspend fun list(status: AnnuityPurchaseStatus?, limit: Int): List<AnnuityPurchase> =
        page<AnnuityPurchaseEntity, AnnuityPurchase>(status?.name, null, limit)
}

@ApplicationScoped
class AnnuityProviderRepositoryImpl :
    AnnuityProviderRepository,
    PanacheRepository<AnnuityProviderEntity> {

    /** Upsert under the optimistic lock: a stale [AnnuityProvider.version] is refused, never overwritten. */
    override suspend fun save(provider: AnnuityProvider): AnnuityProvider {
        val version = Panache.withTransaction {
            find("partnerId", provider.partnerId).firstResult().flatMap { existing ->
                if (existing != null && existing.rowVersion != provider.version) {
                    throw AnnuityConcurrentUpdateException(
                        "annuity partner ${provider.partnerId} changed concurrently; re-read and retry",
                    )
                }
                if (existing == null && provider.version != 0) {
                    throw AnnuityConcurrentUpdateException("annuity partner ${provider.partnerId} vanished")
                }
                val entity = existing ?: AnnuityProviderEntity().also { it.partnerId = provider.partnerId }
                entity.status = provider.status.name
                entity.body = ExitJson.mapper.writeValueAsString(provider)
                entity.updatedAt = provider.updatedAt
                val stored = if (existing == null) persist(entity) else Uni.createFrom().item(entity)
                stored.flatMap { Panache.getSession() }.flatMap { it.flush() }.map { entity.rowVersion }
            }
        }.awaitSuspending()
        return provider.copy(version = version)
    }

    override suspend fun find(partnerId: String): AnnuityProvider? =
        Panache.withSession { find("partnerId", partnerId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun list(status: AnnuityProviderStatus?): List<AnnuityProvider> {
        val rows = Panache.withSession {
            if (status == null) findAll().list() else find("status = ?1 order by partnerId", status.name).list()
        }.awaitSuspending()
        return rows.map { it.toDomain() }.sortedBy { it.partnerId }
    }

    private fun AnnuityProviderEntity.toDomain(): AnnuityProvider {
        val tree = ExitJson.mapper.readTree(body) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.put("version", rowVersion)
        return ExitJson.mapper.treeToValue(tree, AnnuityProvider::class.java)
    }
}
