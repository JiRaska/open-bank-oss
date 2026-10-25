// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.openbank.account.application.port.out.CatalogProduct
import com.openbank.account.application.port.out.ProductCatalogPort
import com.openbank.account.application.port.out.ProductLookupResult
import com.openbank.account.application.port.out.PublishedRevision
import com.openbank.account.application.port.out.RevisionResolution
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/**
 * Adapter over [ResilientProductCatalogReads] (issue #668, ADR-0341 D1).
 *
 * It never throws for a catalog fault: every outage, timeout, open circuit, 5xx or refused 4xx is
 * [ProductLookupResult.Unavailable] / [RevisionResolution.Unavailable], and the use case refuses the
 * opening on it. Before V33 this adapter was deliberately FAIL-OPEN (the use case proceeded on
 * `Unavailable`); it is fail-closed now because an account must carry a pin, and an unreachable
 * catalog yields none. A confirmed 404 stays its own outcome (the caller's error, not an outage).
 */
@ApplicationScoped
class ProductCatalogAdapter(private val reads: ResilientProductCatalogReads) : ProductCatalogPort {

    // TooGenericExceptionCaught: the reads' own retry/timeout/circuit-breaker surface as a family of
    // fault-tolerance exceptions plus whatever the transport throws; all of them mean "no answer".
    @Suppress("TooGenericExceptionCaught")
    override suspend fun findById(productId: UUID): ProductLookupResult = try {
        when (val read = reads.product(productId)) {
            is CatalogRead.Answered -> ProductLookupResult.Found(
                CatalogProduct(UUID.fromString(read.body.id), read.body.code, read.body.status, read.body.currency),
            )
            CatalogRead.NotFound -> ProductLookupResult.NotFound
            is CatalogRead.Refused -> {
                Log.warnf("product-catalog refused product %s with HTTP %d", productId, read.status)
                ProductLookupResult.Unavailable
            }
        }
    } catch (e: Exception) {
        Log.warnf("product-catalog unavailable for product %s: %s", productId, e.toString())
        ProductLookupResult.Unavailable
    }

    @Suppress("TooGenericExceptionCaught") // same reasoning as findById
    override suspend fun resolvePublishedRevision(productId: UUID, effectiveAt: Instant): RevisionResolution = try {
        when (val read = reads.publishedRevision(productId, effectiveAt)) {
            is CatalogRead.Answered -> toResolution(productId, read.body)
            CatalogRead.NotFound -> RevisionResolution.NoPublishedRevision
            is CatalogRead.Refused -> {
                Log.warnf("product-catalog refused the revision of %s with HTTP %d", productId, read.status)
                RevisionResolution.Unavailable
            }
        }
    } catch (e: Exception) {
        Log.warnf("product-catalog unavailable for the revision of %s: %s", productId, e.toString())
        RevisionResolution.Unavailable
    }

    /**
     * The endpoint promises a PUBLISHED revision; this does not take that on trust. Anything else
     * (a DRAFT, a SUPERSEDED one, a malformed id) is no pin, so the opening is refused.
     */
    private fun toResolution(productId: UUID, body: CatalogRevisionResponse): RevisionResolution {
        if (body.state != PUBLISHED) {
            Log.warnf("product-catalog answered revision %s of %s in state %s", body.id, productId, body.state)
            return RevisionResolution.NoPublishedRevision
        }
        val irHash = body.irSignature?.revisionHash
        val irVersion = body.content.behaviour?.irVersion
        return RevisionResolution.Published(
            PublishedRevision(
                revisionId = UUID.fromString(body.id),
                offeringId = UUID.fromString(body.offeringId),
                number = body.number,
                contentHash = body.contentHash,
                // Both or neither: a signature without an IR version (or the reverse) is not a pin we can verify.
                irRevisionHash = irHash.takeIf { irVersion != null },
                irVersion = irVersion.takeIf { irHash != null },
            ),
        )
    }

    private companion object {
        const val PUBLISHED = "PUBLISHED"
    }
}
