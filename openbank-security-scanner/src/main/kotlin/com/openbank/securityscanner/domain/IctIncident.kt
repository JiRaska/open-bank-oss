// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.securityscanner.domain

import java.time.Instant
import java.util.UUID

enum class IncidentSeverity { P1_CRITICAL, P2_HIGH, P3_MEDIUM, P4_LOW }
enum class IncidentStatus { OPEN, INVESTIGATING, CONTAINED, RESOLVED, CLOSED }
enum class IncidentCategory {
    AVAILABILITY, INTEGRITY, CONFIDENTIALITY, AUTHENTICITY,
    UNAUTHORIZED_ACCESS, DATA_BREACH, RANSOMWARE, DDOS,
    INSIDER_THREAT, SUPPLY_CHAIN, OTHER
}

data class IctIncident(
    val id: UUID,
    val title: String,
    val description: String,
    val category: IncidentCategory,
    val severity: IncidentSeverity,
    val status: IncidentStatus,
    val affectedServices: List<String>,
    val detectedAt: Instant,
    val reportedAt: Instant,
    val containedAt: Instant?,
    val resolvedAt: Instant?,
    val rtoMinutes: Int?,
    val rpoMinutes: Int?,
    val reportedToRegulator: Boolean,
    val regulatoryReportId: String?,
    val assignedTo: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
