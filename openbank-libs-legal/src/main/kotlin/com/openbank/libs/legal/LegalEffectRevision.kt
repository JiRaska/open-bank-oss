// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.legal

import com.openbank.libs.domain.identifiers.CaseId
import com.openbank.libs.domain.identifiers.PartyId
import java.util.UUID

/** Identity shared by the case owner and the operational owner applying one effect (ADR-0381). */
data class LegalEffectIdentity(val effectId: UUID, val caseId: CaseId, val subjectPartyId: PartyId)

/** Desired state of ONE effect. RELEASED is a durable tombstone, not deletion of its revision. */
enum class LegalEffectDisposition { ACTIVE, RELEASED }

/**
 * Version fence for a complete effect snapshot, not a delta or a financial authorization.
 *
 * [revision] is assigned monotonically by the legal case owner, not derived from transport order
 * or a timestamp. [contentSha256] binds the complete canonical snapshot, including scope, legal
 * basis, evidence, effective dates and pack version. The adapter must verify that digest against
 * the received snapshot before using this type; shape validation is not signature verification.
 *
 * No amount, protection formula or legal-capacity decision belongs here: Balance and Party own
 * those interpretations. A country pack never changes the revision protocol.
 */
data class LegalEffectRevision(
    val identity: LegalEffectIdentity,
    val revision: Long,
    val disposition: LegalEffectDisposition,
    val contentSha256: String,
) {
    init {
        require(revision > 0) { "Legal effect revision must be positive" }
        require(SHA256.matches(contentSha256)) { "Legal effect content digest must be lowercase SHA-256" }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** Outcome is explicit: a stale/no-op delivery must not be reported as an applied restriction. */
enum class LegalEffectRevisionOutcome {
    APPLY,
    DUPLICATE,
    STALE,
    CONFLICT,
}

/**
 * Common replay fence for independent effects at Account, Party and Balance (ADR-0381).
 *
 * This function performs no I/O and does not make a legal decision. The caller must load the
 * stored revision, evaluate and persist the accepted complete snapshot plus outbox event in ONE
 * serialized transaction. An APPLY answer alone is not an application acknowledgement.
 *
 * Retain RELEASED snapshots: deleting them lets an old ACTIVE message resurrect an attachment.
 * A higher revision may reactivate an effect only after the case owner has validated its basis.
 * Gaps are allowed because every message is a complete snapshot, never an incremental release.
 */
object LegalEffectRevisionGuard {
    fun evaluate(current: LegalEffectRevision?, incoming: LegalEffectRevision): LegalEffectRevisionOutcome = when {
        current == null -> LegalEffectRevisionOutcome.APPLY
        current.identity != incoming.identity -> LegalEffectRevisionOutcome.CONFLICT
        incoming.revision < current.revision -> LegalEffectRevisionOutcome.STALE
        incoming.revision > current.revision -> LegalEffectRevisionOutcome.APPLY
        incoming == current -> LegalEffectRevisionOutcome.DUPLICATE
        else -> LegalEffectRevisionOutcome.CONFLICT
    }
}
