// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

/**
 * Persisted catalogue product (ADR-0105 P1). Reactive Panache entity (the fleet standard — see
 * devops-agent). The full [com.openbank.productcatalog.domain.Product] is stored as a single JSONB
 * `doc` (the catalogue is document-shaped: ~10 nested config value-objects), with the identity and
 * filter attributes promoted to indexed scalar columns so list/lookup queries stay relational.
 *
 * Three identifiers (ADR-0105): [id] is the durable canonical UUID account-service references;
 * [code] is the semantic code (e.g. SAVINGS_STANDARD); [legacyCode] is the `prod-NNN` alias.
 * The id is assigned in the domain (canonical UUID), never DB-generated.
 */
@Entity
@Table(name = "products")
class ProductEntity : PanacheEntityBase {

    companion object : PanacheCompanionBase<ProductEntity, UUID>

    @Id
    @Column(columnDefinition = "uuid")
    lateinit var id: UUID

    @Column(name = "code", nullable = false, unique = true)
    lateinit var code: String

    @Column(name = "legacy_code", unique = true)
    var legacyCode: String? = null

    @Column(name = "type", nullable = false)
    lateinit var type: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "doc", columnDefinition = "jsonb", nullable = false)
    lateinit var doc: String

    @Version
    @Column(name = "row_version", nullable = false)
    var revision: Long = 0
}
