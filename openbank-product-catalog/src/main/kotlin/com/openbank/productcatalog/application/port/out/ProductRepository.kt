// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application.port.out

import com.openbank.productcatalog.domain.Product
import java.util.UUID

/**
 * Outbound persistence port for the product catalogue (hexagonal, ADR-0002).
 *
 * The catalogue is the authority for canonical product identity (ADR-0105): each product has a
 * stable canonical [UUID] (the `id`), a semantic `code` (e.g. `SAVINGS_STANDARD`), and a `prod-NNN`
 * legacy alias. Identity is durable across restarts because it is Postgres-backed (ADR-0009),
 * seeded by a Flyway migration carrying the canonical UUIDs — not an in-memory map.
 *
 * Methods are `suspend`: the adapter is reactive Panache (the fleet/libs standard) and bridges its
 * Mutiny `Uni` results onto coroutines.
 */
interface ProductRepository {
    suspend fun findAll(): List<Product>

    /** Resolve by the canonical product UUID (ADR-0105 `GET /api/v1/products/{uuid}`). */
    suspend fun findById(id: UUID): Product?

    /** Resolve by semantic `code` OR the `prod-NNN` legacy alias (`GET …/products/by-code/{code}`). */
    suspend fun findByCode(code: String): Product?

    /**
     * Persist a new product. [legacyCode] is the `prod-NNN` alias for seeded catalogue products
     * (null for API-created ones, which have no legacy alias).
     */
    suspend fun save(product: Product, legacyCode: String? = null, actorId: String): Product

    suspend fun update(product: Product, actorId: String): Product

    /** Row count — used to seed the catalogue only on an empty store (idempotent first-boot seed). */
    suspend fun count(): Long
}
