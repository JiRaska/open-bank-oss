// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.tppregistry.domain.model

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * TPP (Third Party Provider) registered in EBA/CNB register.
 * Represents a licensed payment institution or account information service provider.
 */
data class TppEntry(
    val id: UUID,
    val tppId: String,              // EBA/CNB unique identifier (e.g. "CZ-CNB-123456")
    val name: String,
    val countryCode: String,        // ISO 3166-1 alpha-2
    val nca: String,                // National Competent Authority (e.g. "CNB", "BaFin")
    val roles: Set<TppRole>,
    val status: TppStatus,
    val qwacSubjectDn: String?,     // eIDAS QWAC certificate Subject DN
    val qsealSubjectDn: String?,    // eIDAS QSeal certificate Subject DN
    val qwacExpiresAt: LocalDate?,
    val qsealExpiresAt: LocalDate?,
    val registeredAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val blacklistedAt: OffsetDateTime?,
    val blacklistReason: String?
)

enum class TppRole {
    AISP,   // Account Information Service Provider (PSD2 Art. 67)
    PISP,   // Payment Initiation Service Provider (PSD2 Art. 66)
    PIISP,  // Payment Instrument Issuer Service Provider
    ASPSP   // Account Servicing Payment Service Provider (us)
}

enum class TppStatus {
    ACTIVE,
    SUSPENDED,
    REVOKED,
    BLACKLISTED
}

/**
 * Result of TPP authorization check — returned to psd2-service per request.
 */
data class TppAuthorizationResult(
    val tppId: String,
    val authorized: Boolean,
    val roles: Set<TppRole>,
    val reason: String?
)

/**
 * EBA Register sync metadata.
 */
data class EbaRegisterSyncState(
    val lastSyncAt: OffsetDateTime?,
    val lastSuccessAt: OffsetDateTime?,
    val totalEntries: Int,
    val errorMessage: String?
)
