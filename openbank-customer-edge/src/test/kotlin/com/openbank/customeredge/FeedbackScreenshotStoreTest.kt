// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.feedback.FeedbackScreenshotStore
import com.openbank.libs.storage.ObjectStorePort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [FeedbackScreenshotStore] (ADR-0192).
 *
 * Two invariants: the key is opaque and PII-free (ADR-0161's key rule — a bucket listing must
 * reveal nothing about who a screenshot belongs to), and a storage failure degrades instead of
 * failing the customer's already-consented submission.
 */
class FeedbackScreenshotStoreTest {

    private val objectStore = mockk<ObjectStorePort>()
    private val store = FeedbackScreenshotStore(objectStore)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

    @Test
    fun `stores under an opaque PII-free key as image slash png`() {
        val id = UUID.randomUUID()
        val key = slot<String>()
        val contentType = slot<String>()
        coEvery { objectStore.put(capture(key), any(), capture(contentType), any()) } returns Unit

        val result = store.store(id, "FB-3F9A21C0B7D4", png)

        assertThat(key.captured).isEqualTo("customer-edge/feedback/$id.png")
        assertThat(contentType.captured).isEqualTo("image/png")
        assertThat(result.key).isEqualTo(key.captured)
        assertThat(result.status).isEqualTo(FeedbackScreenshotStore.STATUS_STORED)
    }

    @Test
    fun `object metadata carries the reference but never the party`() {
        val metadata = slot<Map<String, String>>()
        coEvery { objectStore.put(any(), any(), any(), capture(metadata)) } returns Unit

        store.store(UUID.randomUUID(), "FB-3F9A21C0B7D4", png)

        assertThat(metadata.captured).containsExactly(java.util.Map.entry("reference", "FB-3F9A21C0B7D4"))
    }

    @Test
    fun `a storage failure degrades to STORE_FAILED instead of throwing`() {
        coEvery { objectStore.put(any(), any(), any(), any()) } throws IllegalStateException("no bucket")

        val result = store.store(UUID.randomUUID(), "FB-3F9A21C0B7D4", png)

        assertThat(result.key).isNull()
        assertThat(result.status).isEqualTo(FeedbackScreenshotStore.STATUS_STORE_FAILED)
        coVerify(exactly = 1) { objectStore.put(any(), any(), any(), any()) }
    }

    @Test
    fun `isPng accepts a real PNG signature and rejects anything else`() {
        assertThat(FeedbackScreenshotStore.isPng(png)).isTrue()
        assertThat(FeedbackScreenshotStore.isPng("%PDF-1.7 not a screenshot".toByteArray())).isFalse()
        // A bare signature with no image data is not a usable screenshot either.
        assertThat(FeedbackScreenshotStore.isPng(png.copyOfRange(0, 8))).isFalse()
        assertThat(FeedbackScreenshotStore.isPng(ByteArray(0))).isFalse()
    }
}
