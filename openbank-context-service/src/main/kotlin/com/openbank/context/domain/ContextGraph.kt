// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.domain

import java.time.Instant

enum class ContextNamespace { COMPLAINT, INCIDENT, AUTHORIZATION, AML, KYB }
enum class DataClassification { INTERNAL, CONFIDENTIAL, RESTRICTED }

data class ContextNode(
    val key: String,
    val namespace: ContextNamespace,
    val type: String,
    val sourceSystem: String,
    val sourceRef: String,
    val label: String,
    val classification: DataClassification,
    val validFrom: Instant,
    val validTo: Instant?,
    val recordedAt: Instant,
    val sourceVersion: Long,
)

data class ContextEdge(
    val id: String,
    val namespace: ContextNamespace,
    val from: String,
    val to: String,
    val relation: String,
    val evidenceRef: String,
    val validFrom: Instant,
    val validTo: Instant?,
    val recordedAt: Instant,
    val sourceVersion: Long,
)

data class ContextNeighborhood(
    val root: String,
    val nodes: List<ContextNode>,
    val edges: List<ContextEdge>,
    val truncated: Boolean,
)
enum class ImpactProjectionStatus { MISSING, PARTIAL, AVAILABLE }

data class IncidentImpact(
    val incidentRef: String,
    val affectedByType: Map<String, Int>,
    val total: Int,
    val drilldownAvailable: Boolean,
    val projectionStatus: ImpactProjectionStatus,
)
data class Investigator(val id: String, val roles: List<String>)
data class InvestigationContext(
    val caseId: String,
    val purpose: String,
    val asOf: Instant,
    val knownAt: Instant? = null,
)
