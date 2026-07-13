// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain.model

import java.time.Instant
import java.util.UUID

/** Per-signer decision state within a ceremony. */
enum class SignerStatus { PENDING, SIGNED, DECLINED }

/** Aggregate lifecycle of a signing ceremony. */
enum class CeremonyStatus { DRAFT, PENDING, PARTIALLY_SIGNED, COMPLETED, DECLINED, EXPIRED }

/**
 * Assurance level of the ceremony (eIDAS). ADVANCED is the phase-1 default; QUALIFIED (QES with a
 * qualified signature-creation device) is phase-2.
 */
enum class SignatureLevel { ADVANCED, QUALIFIED }

/** A single expected signer and their current decision. Value object within [SignatureCeremony]. */
data class Signer(val partyRef: String, val order: Int, val status: SignerStatus, val signedAt: Instant?)

/**
 * Orchestrates an e-signature ceremony over a [Document]. Separable from the document aggregate:
 * a document may exist without a ceremony. Pure domain aggregate: no framework imports (ADR-0002).
 */
data class SignatureCeremony(
    val id: UUID,
    val documentId: UUID,
    val signers: List<Signer>,
    val status: CeremonyStatus,
    val signatureLevel: SignatureLevel,
    val createdAt: Instant,
    // JPA optimistic-lock counter, carried through from the persisted entity (0 for a
    // not-yet-persisted ceremony). recordDecision reads a ceremony, mutates it in memory, then
    // saves it in a separate transaction — this value is what lets the repository detect that
    // another decision committed in between and reject the stale write, rather than silently
    // overwriting it. A plain Int, not a framework type, so it does not violate the
    // framework-free domain layer (ADR-0002).
    val version: Int = 0,
) {
    fun open(): SignatureCeremony {
        require(status == CeremonyStatus.DRAFT) { "Only a DRAFT ceremony can be opened" }
        require(signers.isNotEmpty()) { "A ceremony needs at least one signer" }
        return copy(status = CeremonyStatus.PENDING)
    }

    /**
     * Records a signer's decision. Signers must decide **strictly in [Signer.order]**: a decision
     * is only accepted from the signer holding the lowest `order` among those still `PENDING` —
     * this enforces the multi-signer sequencing ADR-0162 D4 calls for (e.g. a contract two
     * clients sign days apart, one after the other).
     */
    fun recordDecision(partyRef: String, decision: SignerStatus, now: Instant): SignatureCeremony {
        require(status == CeremonyStatus.PENDING || status == CeremonyStatus.PARTIALLY_SIGNED) {
            "Cannot record a decision on a ceremony in status $status"
        }
        require(decision == SignerStatus.SIGNED || decision == SignerStatus.DECLINED) {
            "A decision must be SIGNED or DECLINED"
        }
        val signer = signers.find { it.partyRef == partyRef }
        require(signer != null) { "Unknown signer: $partyRef" }
        require(signer.status == SignerStatus.PENDING) {
            "Signer $partyRef has already decided (${signer.status})"
        }
        val nextExpectedOrder = signers.filter { it.status == SignerStatus.PENDING }.minOf { it.order }
        require(signer.order == nextExpectedOrder) {
            "Signers must decide in order: signer $partyRef has order ${signer.order}, but the next " +
                "expected signer has order $nextExpectedOrder"
        }
        val updatedSigners = signers.map { s ->
            if (s.partyRef == partyRef) {
                s.copy(status = decision, signedAt = if (decision == SignerStatus.SIGNED) now else null)
            } else {
                s
            }
        }
        return copy(signers = updatedSigners, status = recomputeStatus(updatedSigners))
    }

    private fun recomputeStatus(current: List<Signer>): CeremonyStatus = when {
        current.any { it.status == SignerStatus.DECLINED } -> CeremonyStatus.DECLINED
        current.all { it.status == SignerStatus.SIGNED } -> CeremonyStatus.COMPLETED
        current.any { it.status == SignerStatus.SIGNED } -> CeremonyStatus.PARTIALLY_SIGNED
        else -> CeremonyStatus.PENDING
    }
}
