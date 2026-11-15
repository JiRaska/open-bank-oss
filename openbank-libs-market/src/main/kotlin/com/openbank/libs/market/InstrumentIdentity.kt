// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.market

import java.time.Instant
import java.util.UUID

/** Opaque references assigned by the legal intake, scoped to the immutable instrument and its subject. */
data class InstrumentSubject(val instrumentRef: String, val subjectRef: String) {
    init {
        require(instrumentRef.isNotBlank() && subjectRef.isNotBlank()) {
            "Instrument and subject references are required"
        }
    }
}

sealed interface IdentityVerification {
    data class Verified(
        val subject: InstrumentSubject,
        val partyId: UUID,
        val evidenceRef: String,
        val verifiedAt: Instant,
        val version: Long,
    ) : IdentityVerification {
        init {
            require(evidenceRef.isNotBlank() && version > 0) {
                "Verified evidence requires a reference and positive version"
            }
        }
    }

    data object Unverified : IdentityVerification

    data object Ambiguous : IdentityVerification
}

/**
 * Compares the intake's verification with an authoritative observation immediately before applying
 * legal effect. C4 supplies that observation. The owning service must fence its write against
 * revocation and merges. A caller-supplied copy cannot serve as the current observation.
 */
fun IdentityVerification.Verified.isCurrentFor(
    subject: InstrumentSubject,
    current: IdentityVerification,
    now: Instant,
    oldestAllowedVerification: Instant,
): Boolean = this.subject == subject && this == current && verifiedAt in oldestAllowedVerification..now
