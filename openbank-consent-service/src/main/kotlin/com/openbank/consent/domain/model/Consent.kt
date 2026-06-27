// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.domain.model

import java.time.OffsetDateTime
import java.util.UUID

// ─── Enums ───────────────────────────────────────────────────────────────────

enum class ConsentScope {
    // AISP — Account Information Service Provider (PSD2 Art. 67 / ČOBS 2.x)
    ACCOUNTS_READ,
    BALANCES_READ,
    TRANSACTIONS_READ,
    STATEMENTS_READ,
    PAYMENT_ACCOUNTS_READ, // ČOBS: platební účty
    STANDING_ORDERS_READ, // ČOBS: trvalé příkazy
    DIRECT_DEBITS_READ, // ČOBS: inkasa

    // PISP — Payment Initiation Service Provider (PSD2 Art. 66 / ČOBS 2.x)
    PAYMENTS_INITIATE,
    PAYMENTS_STATUS_READ,
    DOMESTIC_PAYMENT_INITIATE, // ČOBS: domácí platba CZ
    SIPO_PAYMENT_INITIATE, // ČOBS: SIPO

    // CBPII — Card Based Payment Instrument Issuer (PSD2 Art. 65)
    FUNDS_CONFIRMATION,

    // AI Agent scopes (bank extension, requires explicit consent)
    AGENT_QUERY, // read-only queries via MCP
    AGENT_INITIATE, // payment initiation via agent (SCA per-transaction)
    AGENT_NOTIFY, // push notifications to agent
    AGENT_ANALYZE, // spending analysis, ML features

    // Telemetry — GDPR data-processing consent (ADR-0088 D4b), NOT a PSD2
    // account-access consent and NOT SCA-gated. The customer's opt-in to the
    // bank collecting mobile Real-User-Monitoring telemetry; off by default.
    // This record is the demonstrable consent (GDPR Art. 7) that the public RUM
    // ingest gateway is gated on. Falls in the 365-day (non-AISP) validity
    // bucket — it is not subject to the PSD2 RTS Art. 10 90-day cap.
    TELEMETRY_RUM,
}

enum class GranteeType {
    TPP, // Third Party Provider with eIDAS certificate
    BANK_AGENT, // Bank-operated AI agent (internal)
    CUSTOMER_AGENT, // Customer-created AI agent (delegated)
    INTERNAL_SERVICE, // Internal bank service (service-to-service)
}

enum class ConsentStatus {
    PENDING_SCA, // Created, awaiting SCA completion
    ACTIVE, // SCA completed, consent valid
    EXPIRED, // Past validTo (PSD2: 90 days max)
    REVOKED, // Explicitly revoked by customer
    REJECTED, // SCA failed or customer declined
    SUPERSEDED, // Replaced by a newer consent for same grantee+scopes
}

// ─── Aggregate ───────────────────────────────────────────────────────────────

data class Consent(
    val id: UUID = UUID.randomUUID(),
    val partyId: UUID, // Customer who grants consent
    val granteeId: String, // TPP eIDAS org ID or agent ID
    val granteeType: GranteeType,
    val granteeName: String, // Human-readable name for UI
    val scopes: Set<ConsentScope>,
    val accountIbans: List<String>?, // null = all accounts
    val status: ConsentStatus,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime, // PSD2 RTS Art. 10: max 90 days
    val scaSessionId: UUID?, // Reference to SCA challenge
    val redirectUri: String?, // TPP redirect after SCA
    val tppTransactionId: String?, // TPP's own reference
    val ipAddress: String?, // Client IP at consent creation
    val userAgent: String?, // Client UA at consent creation
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val revokedAt: OffsetDateTime? = null,
    val revokedReason: String? = null,
) {
    init {
        require(scopes.isNotEmpty()) { "Consent must have at least one scope" }
        require(validTo.isAfter(validFrom)) { "validTo must be after validFrom" }
        // PSD2 RTS Art. 10: max 90 days for AISP
        val maxDays = if (scopes.any {
                it in setOf(
                    ConsentScope.ACCOUNTS_READ,
                    ConsentScope.BALANCES_READ,
                    ConsentScope.TRANSACTIONS_READ,
                    ConsentScope.STATEMENTS_READ,
                )
            }
        ) {
            90L
        } else {
            365L
        }
        require(!validTo.isAfter(validFrom.plusDays(maxDays))) {
            "Consent validity exceeds maximum allowed $maxDays days"
        }
    }

    fun isActive(now: OffsetDateTime): Boolean = status == ConsentStatus.ACTIVE &&
        now.isBefore(validTo)

    fun hasScope(scope: ConsentScope): Boolean = scope in scopes

    fun coversAccount(iban: String): Boolean = accountIbans == null || iban in accountIbans

    fun revoke(reason: String, now: OffsetDateTime): Consent = copy(
        status = ConsentStatus.REVOKED,
        revokedAt = now,
        revokedReason = reason,
        updatedAt = now,
    )

    fun activate(scaSessionId: UUID, now: OffsetDateTime): Consent = copy(
        status = ConsentStatus.ACTIVE,
        scaSessionId = scaSessionId,
        updatedAt = now,
    )

    fun reject(now: OffsetDateTime): Consent = copy(
        status = ConsentStatus.REJECTED,
        updatedAt = now,
    )
}

// ─── Consent Validation Result ───────────────────────────────────────────────

sealed class ConsentValidationResult {
    data class Valid(val consent: Consent) : ConsentValidationResult()
    data class Invalid(val reason: String, val code: String) : ConsentValidationResult()
}
