// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import java.time.Instant
import java.util.UUID

/** What the agent is to the principal, as a FACT about the world — never a choice the owner made (that is delegation, ADR-0232). */
enum class MandateRole {
    /** A sole trader acting for the business that IS them. */
    OWNER,

    /** A member of the statutory body listed in the public register. */
    LEGAL_REPRESENTATIVE,

    /** A person the statutory body authorised in writing (power of attorney / prokura). */
    AUTHORISED_SIGNATORY,
}

/** Whether the agent binds the principal alone or only together with others (the register's representation rule). */
enum class MandateAuthority { SOLE, JOINT }

enum class MandateSource { REGISTRY, POWER_OF_ATTORNEY, MANUAL }

enum class MandateStatus { ACTIVE, REVOKED, EXPIRED }

/**
 * A representation mandate (ADR-0284 D3): [agentPartyId] (a human) may act for [principalPartyId]
 * (a legal entity). The customer edge switches profiles on ACTIVE mandates, and delegation grants
 * for employees are issued by a mandate holder acting for the entity.
 */
data class PartyMandate(
    val id: UUID,
    val principalPartyId: UUID,
    val agentPartyId: UUID,
    val role: MandateRole,
    val authority: MandateAuthority,
    val source: MandateSource,
    val status: MandateStatus,
    /** Where the fact came from: a kyb case + signature, a register extract, an operator's note. */
    val evidenceRef: String?,
    val validFrom: Instant,
    val validTo: Instant?,
    val revokedAt: Instant? = null,
    val revokeReason: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun isActiveAt(at: Instant): Boolean =
        status == MandateStatus.ACTIVE && !at.isBefore(validFrom) && (validTo == null || at.isBefore(validTo))
}
