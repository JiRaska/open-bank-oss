// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import io.vertx.core.json.JsonObject
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "catalog_schemas")
class CatalogSchemaEntity {
    @Id lateinit var key: String

    @Column(name = "schema_id")
    lateinit var schemaId: String

    @Column(name = "schema_version")
    var schemaVersion: Int = 0

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    lateinit var document: JsonObject
    lateinit var sha256: String

    @Column(name = "registered_at")
    lateinit var registeredAt: Instant
}

@Entity
@Table(name = "catalog_specifications")
class CatalogSpecificationEntity {
    @Id lateinit var id: UUID
    lateinit var code: String

    @Column(name = "schema_id")
    lateinit var schemaId: String

    @Column(name = "schema_version")
    var schemaVersion: Int = 0

    @Column(name = "created_at")
    lateinit var createdAt: Instant

    @Version
    @Column(name = "lock_version")
    var revision: Long = 0
}

@Entity
@Table(name = "catalog_offerings")
class CatalogOfferingEntity {
    @Id lateinit var id: UUID

    @Column(name = "specification_id")
    lateinit var specificationId: UUID
    lateinit var code: String

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    lateinit var market: JsonObject

    @Version
    @Column(name = "lock_version")
    var revision: Long = 0
}

@Entity
@Table(name = "catalog_revisions")
class CatalogRevisionEntity {
    @Id lateinit var id: UUID

    @Column(name = "offering_id")
    lateinit var offeringId: UUID

    @Column(name = "revision_no")
    var number: Long = 0

    @Column(name = "schema_id")
    lateinit var schemaId: String

    @Column(name = "schema_version")
    var schemaVersion: Int = 0
    lateinit var state: String

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    lateinit var content: JsonObject

    @Column(name = "effective_from")
    var effectiveFrom: Instant? = null

    @Column(name = "effective_to")
    var effectiveTo: Instant? = null

    @Column(name = "maker_id")
    lateinit var makerId: String

    @Column(name = "checker_id")
    var checkerId: String? = null
    var reason: String? = null

    @Column(name = "content_hash")
    var contentHash: String? = null

    @Column(name = "created_at")
    lateinit var createdAt: Instant

    @Column(name = "updated_at")
    lateinit var updatedAt: Instant

    @Version
    @Column(name = "lock_version")
    var revision: Long = 0
}

@Entity
@Table(name = "catalog_price_components")
class CatalogPriceEntity {
    @Id lateinit var id: UUID

    @Column(name = "revision_id")
    lateinit var revisionId: UUID
    lateinit var code: String
    lateinit var kind: String
    lateinit var value: BigDecimal
    var currency: String? = null
    lateinit var unit: String
    lateinit var cadence: String

    @Column(name = "tax_treatment")
    lateinit var taxTreatment: String

    @Column(name = "effective_from")
    var effectiveFrom: Instant? = null

    @Column(name = "effective_to")
    var effectiveTo: Instant? = null
}

@Entity
@Table(name = "catalog_relationships")
class CatalogRelationshipEntity {
    @Id lateinit var id: UUID

    @Column(name = "revision_id")
    lateinit var revisionId: UUID

    @Column(name = "target_offering_id")
    lateinit var targetOfferingId: UUID
    lateinit var kind: String
}

@Entity
@Table(name = "catalog_approvals")
class CatalogApprovalEntity {
    @Id lateinit var id: UUID

    @Column(name = "revision_id")
    lateinit var revisionId: UUID

    @Column(name = "maker_id")
    lateinit var makerId: String

    @Column(name = "checker_id")
    lateinit var checkerId: String
    lateinit var reason: String

    @Column(name = "approved_at")
    lateinit var approvedAt: Instant
}

@Entity
@Table(name = "catalog_audit")
class CatalogAuditEntity {
    @Id lateinit var id: UUID

    @Column(name = "aggregate_type")
    lateinit var aggregateType: String

    @Column(name = "aggregate_id")
    lateinit var aggregateId: UUID
    lateinit var action: String

    @Column(name = "actor_id")
    lateinit var actorId: String

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    lateinit var details: JsonObject
}

@Entity
@Table(name = "catalog_outbox")
class CatalogOutboxEntity {
    @Id lateinit var id: UUID

    @Column(name = "aggregate_type")
    lateinit var aggregateType: String

    @Column(name = "aggregate_id")
    lateinit var aggregateId: UUID

    @Column(name = "event_type")
    lateinit var eventType: String

    @Column(name = "schema_version")
    var schemaVersion: Int = 1

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    lateinit var headers: JsonObject

    @Column(name = "created_at")
    lateinit var createdAt: Instant

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    lateinit var payload: JsonObject

    @Column(name = "published_at")
    var publishedAt: Instant? = null

    @Column(name = "attempt_count")
    var attemptCount: Int = 0
}

@Entity
@Table(name = "bank_v1_product_mapping")
class BankV1ProductMappingEntity {
    @Id
    @Column(name = "product_id")
    lateinit var productId: UUID

    @Column(name = "default_offering_id")
    lateinit var defaultOfferingId: UUID

    @Column(name = "legacy_code")
    var legacyCode: String? = null

    @Column(name = "projected_revision_id")
    var projectedRevisionId: UUID? = null

    @Column(name = "last_synced_product_revision")
    var lastSyncedProductRevision: Long = -1

    @Column(name = "last_synced_draft_revision")
    var lastSyncedDraftRevision: Long = UNINITIALISED_DRAFT_REVISION

    @Column(name = "created_at")
    lateinit var createdAt: Instant

    private companion object {
        const val UNINITIALISED_DRAFT_REVISION = -2L
    }
}
