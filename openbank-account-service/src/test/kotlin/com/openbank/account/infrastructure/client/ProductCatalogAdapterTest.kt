// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.openbank.account.application.port.out.ProductLookupResult
import com.openbank.account.application.port.out.RevisionResolution
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.ws.rs.ProcessingException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The adapter's fault mapping (ADR-0341 D1): every way the catalog can fail to answer becomes
 * `Unavailable` (so the opening is refused with 503), a confirmed 404 stays its own outcome, and a
 * revision that is not PUBLISHED is never pinned, whatever the endpoint promised.
 */
class ProductCatalogAdapterTest {

    private val reads = mockk<ResilientProductCatalogReads>()
    private val adapter = ProductCatalogAdapter(reads)
    private val productId = UUID.randomUUID()
    private val at = Instant.parse("2026-10-25T10:00:00Z")

    @Test
    fun `a transport fault on the revision read is Unavailable, never a pass`(): Unit = runBlocking {
        coEvery { reads.publishedRevision(productId, at) } throws ProcessingException("connection refused")

        assertThat(adapter.resolvePublishedRevision(productId, at)).isEqualTo(RevisionResolution.Unavailable)
    }

    @Test
    fun `an open circuit is Unavailable`(): Unit = runBlocking {
        coEvery { reads.publishedRevision(productId, at) } throws CircuitBreakerOpenException("open")
        coEvery { reads.product(productId) } throws CircuitBreakerOpenException("open")

        assertThat(adapter.resolvePublishedRevision(productId, at)).isEqualTo(RevisionResolution.Unavailable)
        assertThat(adapter.findById(productId)).isEqualTo(ProductLookupResult.Unavailable)
    }

    @Test
    fun `a refused read (401) is Unavailable, not NotFound`(): Unit = runBlocking {
        coEvery { reads.publishedRevision(productId, at) } returns CatalogRead.Refused(401)
        coEvery { reads.product(productId) } returns CatalogRead.Refused(401)

        assertThat(adapter.resolvePublishedRevision(productId, at)).isEqualTo(RevisionResolution.Unavailable)
        assertThat(adapter.findById(productId)).isEqualTo(ProductLookupResult.Unavailable)
    }

    @Test
    fun `a 404 is NoPublishedRevision`(): Unit = runBlocking {
        coEvery { reads.publishedRevision(productId, at) } returns CatalogRead.NotFound

        assertThat(adapter.resolvePublishedRevision(productId, at)).isEqualTo(RevisionResolution.NoPublishedRevision)
    }

    @Test
    fun `a revision that is not PUBLISHED is never pinned`(): Unit = runBlocking {
        coEvery { reads.publishedRevision(productId, at) } returns CatalogRead.Answered(revision(state = "SUPERSEDED"))

        assertThat(adapter.resolvePublishedRevision(productId, at)).isEqualTo(RevisionResolution.NoPublishedRevision)
    }

    @Test
    fun `a published revision maps to the pin fields, IR hash and version together`(): Unit = runBlocking {
        coEvery { reads.publishedRevision(productId, at) } returns CatalogRead.Answered(
            revision(signature = CatalogIrSignature("e".repeat(64)), behaviour = CatalogBehaviour(irVersion = 1)),
        )

        val published = (adapter.resolvePublishedRevision(productId, at) as RevisionResolution.Published).revision

        assertThat(published.revisionId).isEqualTo(REVISION_ID)
        assertThat(published.offeringId).isEqualTo(OFFERING_ID)
        assertThat(published.number).isEqualTo(4)
        assertThat(published.contentHash).isEqualTo("f".repeat(64))
        assertThat(published.irRevisionHash).isEqualTo("e".repeat(64))
        assertThat(published.irVersion).isEqualTo(1)
    }

    @Test
    fun `a signature without an IR version, or the reverse, yields neither`(): Unit = runBlocking {
        coEvery { reads.publishedRevision(productId, at) } returns
            CatalogRead.Answered(revision(signature = CatalogIrSignature("e".repeat(64))))

        val published = (adapter.resolvePublishedRevision(productId, at) as RevisionResolution.Published).revision

        assertThat(published.irRevisionHash).isNull()
        assertThat(published.irVersion).isNull()
    }

    private fun revision(
        state: String = "PUBLISHED",
        signature: CatalogIrSignature? = null,
        behaviour: CatalogBehaviour? = null,
    ) = CatalogRevisionResponse(
        id = REVISION_ID.toString(),
        offeringId = OFFERING_ID.toString(),
        number = 4,
        state = state,
        contentHash = "f".repeat(64),
        irSignature = signature,
        content = CatalogRevisionContent(behaviour),
    )

    private companion object {
        val REVISION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c001")
        val OFFERING_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c002")
    }
}
