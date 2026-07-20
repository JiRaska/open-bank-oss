// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DocumentTest {

    @Test
    fun `sha256 is the deterministic lower-case hex digest`() {
        val digest = Document.sha256("hello".toByteArray())

        assertThat(digest).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        assertThat(Document.sha256("hello".toByteArray())).isEqualTo(digest)
    }

    @Test
    fun `sha256 differs for different content`() {
        assertThat(Document.sha256("a".toByteArray()))
            .isNotEqualTo(Document.sha256("b".toByteArray()))
    }

    @Test
    fun `markPendingSignature transitions GENERATED to PENDING_SIGNATURE`() {
        val updated = document(status = DocumentStatus.GENERATED).markPendingSignature()

        assertThat(updated.status).isEqualTo(DocumentStatus.PENDING_SIGNATURE)
    }

    @Test
    fun `markSigned requires PENDING_SIGNATURE`() {
        assertThatThrownBy { document(status = DocumentStatus.GENERATED).markSigned() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `markSigned transitions PENDING_SIGNATURE to SIGNED`() {
        val updated = document(status = DocumentStatus.PENDING_SIGNATURE).markSigned()

        assertThat(updated.status).isEqualTo(DocumentStatus.SIGNED)
    }

    @Test
    fun `archive is idempotency-guarded`() {
        val archived = document(status = DocumentStatus.SIGNED).archive()

        assertThat(archived.status).isEqualTo(DocumentStatus.ARCHIVED)
        assertThatThrownBy { archived.archive() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `archive releases the idempotency key so the replacement can claim it`() {
        val live = document(status = DocumentStatus.PENDING_SIGNATURE)
            .copy(idempotencyKey = "onboarding-agreement:party-1")

        val archived = live.archive()

        // The key names the party's one LIVE artifact. Holding it on a superseded row would make
        // the partial unique index reject the very re-render that supersedes it (e.g. an agreement
        // re-issued in another language), so archiving must hand it back.
        assertThat(archived.idempotencyKey).isNull()
    }

    private fun document(status: DocumentStatus) = Document(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        templateCode = "LOAN_AGREEMENT",
        templateVersion = "1.0.0",
        sha256 = "deadbeef",
        storageKey = "documents/1",
        contentType = "application/pdf",
        sizeBytes = 10L,
        status = status,
        metadata = mapOf("k" to "v"),
        partyRef = "party-1",
        caseRef = null,
        productRef = null,
        retainUntil = null,
        createdAt = FIXED_NOW,
    )

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-01-15T10:15:30Z")
    }
}
