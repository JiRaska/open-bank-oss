// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.application.port.out.CatalogProduct
import com.openbank.account.application.port.out.ProductCatalogPort
import com.openbank.account.application.port.out.ProductLookupResult
import com.openbank.account.application.port.out.PublishedRevision
import com.openbank.account.application.port.out.RevisionResolution
import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/**
 * In-process product-catalog for every `@QuarkusTest`. Since ADR-0341 D1 an account cannot open
 * without a pinned published revision, so the suite needs a catalog that deterministically answers —
 * the old fail-open adapter let ITs pass with no catalog at all, and the only stub that existed
 * (`ProductCatalogHostHeaderIT.CatalogStub`) is up only when that class happens to run first.
 *
 * Every product is ACTIVE with a PUBLISHED revision; the EUR products the ITs open are EUR. A test
 * that needs a refusal sets [mode] and resets it in `@AfterEach` (one JVM, sequential test classes).
 * The adapter's own fault mapping is unit-tested in `ProductCatalogAdapterTest`.
 */
@Mock
@ApplicationScoped
class TestProductCatalogPort : ProductCatalogPort {

    enum class Mode { PUBLISHED, CATALOG_UNREACHABLE, NO_PUBLISHED_REVISION }

    override suspend fun findById(productId: UUID): ProductLookupResult = when (mode) {
        Mode.CATALOG_UNREACHABLE -> ProductLookupResult.Unavailable
        else -> ProductLookupResult.Found(
            CatalogProduct(productId, "BEZNY_UCET", "ACTIVE", if (productId in EUR_PRODUCTS) "EUR" else "CZK"),
        )
    }

    override suspend fun resolvePublishedRevision(productId: UUID, effectiveAt: Instant): RevisionResolution =
        when (mode) {
            Mode.PUBLISHED -> RevisionResolution.Published(revisionOf(productId))
            Mode.CATALOG_UNREACHABLE -> RevisionResolution.Unavailable
            Mode.NO_PUBLISHED_REVISION -> RevisionResolution.NoPublishedRevision
        }

    companion object {
        @Volatile
        var mode: Mode = Mode.PUBLISHED

        private val EUR_PRODUCTS = setOf(
            UUID.fromString("00000000-2222-0000-0000-000000000002"), // AccountApiIT
            UUID.fromString("d4275d2a-1343-3052-a6c0-8a99149b6c62"), // business onboarding product
        )

        /** Deterministic per product, so an IT can assert the exact pin it should see. */
        fun revisionOf(productId: UUID) = PublishedRevision(
            revisionId = UUID.nameUUIDFromBytes("revision:$productId".toByteArray()),
            offeringId = UUID.nameUUIDFromBytes("offering:$productId".toByteArray()),
            number = 7,
            contentHash = "c".repeat(64),
            irRevisionHash = "d".repeat(64),
            irVersion = 1,
        )
    }
}
