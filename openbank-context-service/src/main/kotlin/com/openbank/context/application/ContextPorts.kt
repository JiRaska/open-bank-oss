// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.application

import com.openbank.context.domain.ContextNamespace
import com.openbank.context.domain.ContextNeighborhood
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
    suspend fun record(entry: ContextReadAudit): UUID
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
    val accessAuditId: UUID? = null,
    val queryHash: String? = null,
    val projectionGeneration: String? = null,
    val evidenceRefs: List<String>? = null,
    val evidenceCount: Int? = null,
    val responseTruncated: Boolean? = null,
    val responseStatus: Int? = null,
)

/** Bounded evidence that an endpoint has materialized, before its response is released. */
data class DisclosureSummary(
    val evidenceRefs: List<String>,
    val evidenceCount: Int,
    val truncated: Boolean,
    val projectionGeneration: String?,
    val responseStatus: Int,
) {
    init {
        require(evidenceRefs.size <= MAX_EVIDENCE_ITEMS && evidenceCount in 0..MAX_EVIDENCE_ITEMS)
        require(evidenceRefs.all { it.length <= MAX_REF_LENGTH })
        require(responseStatus in HTTP_SUCCESS_MIN..HTTP_STATUS_MAX)
        if (responseStatus in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) requireNotNull(projectionGeneration)
    }

    companion object {
        const val MAX_EVIDENCE_ITEMS = 500
        const val MAX_REF_LENGTH = 300
        const val HTTP_SUCCESS_MIN = 200
        const val HTTP_SUCCESS_MAX = 299
        const val HTTP_STATUS_MAX = 599
        const val HTTP_NOT_FOUND = 404

        fun materialized(
            evidenceRefs: List<String>,
            evidenceCount: Int,
            truncated: Boolean,
            versionTokens: List<String>,
            responseStatus: Int = HTTP_SUCCESS_MIN,
        ) = DisclosureSummary(
            evidenceRefs.sorted(),
            evidenceCount,
            truncated,
            ContextDisclosureFingerprint.of(versionTokens.sorted()),
            responseStatus,
        )

        fun absent(responseStatus: Int) = DisclosureSummary(emptyList(), 0, false, null, responseStatus)
    }
}

class ContextAccessDenied : RuntimeException()
class ContextAuthorizationUnavailable : RuntimeException()
