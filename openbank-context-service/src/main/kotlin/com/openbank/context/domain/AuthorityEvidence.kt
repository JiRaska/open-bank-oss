// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.domain

import java.time.Instant
import java.util.UUID

/** An assertion from delegation-service, not proof that a particular business action was allowed. */
data class AuthorityEvidence(
    val delegationId: UUID,
    val revision: Long,
    val eventType: String,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: String?,
    val resourceId: UUID?,
    val capabilities: List<String>,
    val approvalPolicy: String?,
    val requiredApprovals: Int?,
    val validFrom: Instant?,
    val validTo: Instant?,
    val occurredAt: Instant,
)

data class RecordedAuthorityEvidence(
    val evidence: AuthorityEvidence,
    val recordedAt: Instant,
    val evidenceRef: String,
    val contentHash: String,
)

data class AuthorityHistory(
    val root: String,
    val effectiveAt: Instant,
    val knownAt: Instant,
    val observations: List<RecordedAuthorityEvidence>,
    val truncated: Boolean,
    val actionAuthorization: String = "UNKNOWN",
)
