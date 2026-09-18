// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.application

import com.openbank.context.domain.ContextNamespace
import com.openbank.context.domain.ContextNeighborhood
import com.openbank.libs.domain.identifiers.Ids
import java.time.Instant
import java.util.UUID

interface ContextGraphPort {
    suspend fun neighborhood(
        namespace: ContextNamespace,
        root: String,
        asOf: Instant,
        maxNodes: Int,
        maxEdges: Int,
    ): ContextNeighborhood?
}

interface CaseAssignmentPort {
    suspend fun isAssignedToRoot(
        principalId: String,
        caseId: String,
        purpose: String,
        root: String,
        at: Instant,
    ): Boolean = false
    suspend fun isAssigned(principalId: String, caseId: String, purpose: String, at: Instant): Boolean
}

interface ContextReadAuditPort {
    suspend fun record(entry: ContextReadAudit)
    suspend fun recordDisclosure(entry: ContextDisclosureAudit)
}

data class ContextReadAudit(
    val principalId: String,
    val caseId: String,
    val purpose: String,
    val action: String,
    val rootRef: String,
    val decision: String,
    val policyVersion: String?,
    val reasonCode: String,
    val occurredAt: Instant,
    val effectiveAt: Instant? = null,
    val knownAt: Instant? = null,
    val id: UUID = Ids.newId(),
)

/** Records materialized responses; candidate reads and failed queries are not disclosures. */
data class ContextDisclosure(
    val evidenceRefs: List<String>,
    val evidenceCount: Int,
    val truncated: Boolean,
    val projectionGeneration: Long? = null,
)

data class ContextDisclosureAudit(
    val decisionAuditId: UUID,
    val queryHash: String,
    val disclosure: ContextDisclosure,
    val occurredAt: Instant,
)

data class ContextReadResult<T>(val value: T, val disclosure: ContextDisclosure?)

class ContextAccessDenied : RuntimeException()
class ContextAuthorizationUnavailable : RuntimeException()
