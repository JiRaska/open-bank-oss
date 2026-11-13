// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ADR-0375 D5. The three absences this use case collapses on purpose are the whole test: a missing
 * document, a document with no owner, and a stored owner that is not a UUID all mean "definitively
 * not this grantor's", and the caller's gate must not see any of them as UNVERIFIABLE — that
 * verdict reads as an outage and would be the wrong refusal reason.
 */
class DocumentOwnershipServiceTest {

    private val repo = mockk<DocumentRepositoryPort>()
    private val service = DocumentOwnershipService(repo)

    private fun document(partyRef: String?) = Document(
        id = UUID.randomUUID(),
        templateCode = "FRAMEWORK_AGREEMENT",
        templateVersion = "1",
        sha256 = "a".repeat(64),
        storageKey = "k",
        contentType = "application/pdf",
        sizeBytes = 1,
        status = DocumentStatus.GENERATED,
        metadata = emptyMap(),
        partyRef = partyRef,
        caseRef = null,
        productRef = null,
        retainUntil = null,
        createdAt = Instant.parse("2026-11-12T00:00:00Z"),
    )

    @Test
    fun `returns the owning party`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val owner = UUID.randomUUID()
        coEvery { repo.findById(id) } returns document(owner.toString())

        assertThat(service.ownerOf(id)).isEqualTo(owner)
    }

    @Test
    fun `a document that does not exist has no owner`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns null

        assertThat(service.ownerOf(id)).isNull()
    }

    @Test
    fun `a document with no partyRef has no owner`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns document(null)

        assertThat(service.ownerOf(id)).isNull()
    }

    @Test
    fun `a malformed partyRef is not evidence of ownership and does not throw`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns document("not-a-uuid")

        assertThat(service.ownerOf(id)).isNull()
    }
}
