// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SignatureCeremonyTest {

    @Test
    fun `open transitions DRAFT to PENDING`() {
        val opened = ceremony(status = CeremonyStatus.DRAFT).open()

        assertThat(opened.status).isEqualTo(CeremonyStatus.PENDING)
    }

    @Test
    fun `open rejects an empty signer list`() {
        val draft = ceremony(status = CeremonyStatus.DRAFT, signers = emptyList())

        assertThatThrownBy { draft.open() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a single signer signing completes the ceremony`() {
        val pending = ceremony(status = CeremonyStatus.PENDING)

        val updated = pending.recordDecision("party-1", SignerStatus.SIGNED, FIXED_NOW)

        assertThat(updated.status).isEqualTo(CeremonyStatus.COMPLETED)
        assertThat(updated.signers.single().signedAt).isEqualTo(FIXED_NOW)
    }

    @Test
    fun `partial signing yields PARTIALLY_SIGNED`() {
        val pending = ceremony(
            status = CeremonyStatus.PENDING,
            signers = listOf(
                Signer("party-1", 1, SignerStatus.PENDING, null),
                Signer("party-2", 2, SignerStatus.PENDING, null),
            ),
        )

        val updated = pending.recordDecision("party-1", SignerStatus.SIGNED, FIXED_NOW)

        assertThat(updated.status).isEqualTo(CeremonyStatus.PARTIALLY_SIGNED)
    }

    @Test
    fun `any decline declines the ceremony`() {
        val pending = ceremony(status = CeremonyStatus.PENDING)

        val updated = pending.recordDecision("party-1", SignerStatus.DECLINED, FIXED_NOW)

        assertThat(updated.status).isEqualTo(CeremonyStatus.DECLINED)
    }

    @Test
    fun `recordDecision rejects an unknown signer`() {
        val pending = ceremony(status = CeremonyStatus.PENDING)

        assertThatThrownBy { pending.recordDecision("nobody", SignerStatus.SIGNED, FIXED_NOW) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `recordDecision rejects a later signer deciding before an earlier one`() {
        val pending = ceremony(
            status = CeremonyStatus.PENDING,
            signers = listOf(
                Signer("party-1", 1, SignerStatus.PENDING, null),
                Signer("party-2", 2, SignerStatus.PENDING, null),
            ),
        )

        assertThatThrownBy { pending.recordDecision("party-2", SignerStatus.SIGNED, FIXED_NOW) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("in order")
    }

    @Test
    fun `recordDecision accepts signers strictly in order`() {
        val pending = ceremony(
            status = CeremonyStatus.PENDING,
            signers = listOf(
                Signer("party-1", 1, SignerStatus.PENDING, null),
                Signer("party-2", 2, SignerStatus.PENDING, null),
            ),
        )

        val afterFirst = pending.recordDecision("party-1", SignerStatus.SIGNED, FIXED_NOW)
        assertThat(afterFirst.status).isEqualTo(CeremonyStatus.PARTIALLY_SIGNED)

        val afterSecond = afterFirst.recordDecision("party-2", SignerStatus.SIGNED, FIXED_NOW)
        assertThat(afterSecond.status).isEqualTo(CeremonyStatus.COMPLETED)
    }

    private fun ceremony(
        status: CeremonyStatus,
        signers: List<Signer> = listOf(Signer("party-1", 1, SignerStatus.PENDING, null)),
    ) = SignatureCeremony(
        id = UUID.fromString("00000000-0000-0000-0000-000000000010"),
        documentId = UUID.fromString("00000000-0000-0000-0000-000000000011"),
        signers = signers,
        status = status,
        signatureLevel = SignatureLevel.ADVANCED,
        createdAt = FIXED_NOW,
    )

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-01-15T10:15:30Z")
    }
}
