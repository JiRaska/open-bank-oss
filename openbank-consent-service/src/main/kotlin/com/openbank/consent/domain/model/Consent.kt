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

    // Marketing communications — GDPR Art. 7 data-processing consent (ADR-0198), NOT a PSD2
    // account-access consent and NOT SCA-gated, same shape as TELEMETRY_RUM. One value per
    // channel, deliberately: a customer who accepted email marketing has not thereby accepted
    // push (ADR-0198 force 4, Act 480/2004 §7(3)'s email-only soft opt-in never covers push).
    // Falls in the 365-day (non-AISP) validity bucket. Default is absent, and absent means
    // denied. Must NOT be added to AISP_SCOPES below — these are consent-service's only
    // Art. 7 marketing-basis scopes, not account-access ones.
    MARKETING_COMMS_EMAIL,
    MARKETING_COMMS_PUSH,
    MARKETING_COMMS_INAPP,

    // Credit offers — GDPR Art. 7 data-processing consent (ADR-0269 rule 1), NOT a PSD2
    // account-access consent and NOT SCA-gated, same shape as the MARKETING_COMMS_* scopes.
    // Deliberately separate from them: a customer who accepted marketing has not thereby agreed to
    // be offered debt, which is the one product where the distance between helpful and harmful is a
    // single nudge. Absent means denied, and absence is the default for every customer.
    //
    // The two are also separate from each other. CREDIT_OFFERS is permission to be *shown* an
    // offer; CREDIT_PROFILE_USE is permission to use the ADR-0210 Customer 360 profile to work out
    // *which* offer. A customer may want an affordability answer they asked for without wanting
    // their spending mined for eligibility, and one checkbox cannot express that.
    CREDIT_OFFERS,
    CREDIT_PROFILE_USE,

    /**
     * ADR-0269 rule 5, L2: the assistant may watch and PREPARE on the customer's behalf — pre-fill
     * an application, scan for a cheaper refinancing, warn that an instalment looks at risk.
     *
     * Separate from CREDIT_PROFILE_USE because the powers are different in kind, not in degree.
     * Reading the profile answers a question the customer asked; acting on it means the assistant
     * does something without being asked each time. It never extends to committing the customer:
     * no level may submit, accept, raise a limit or draw funds.
     */
    CREDIT_AI_AGENT,
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
        val maxDays = if (scopes.any { it in AISP_SCOPES }) 90L else 365L
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

    /**
     * Replaced by a newer consent covering the same grantee and the same scopes (issue #6487).
     *
     * Distinct from [revoke]: the customer did not withdraw anything, so recording this as REVOKED
     * would put a withdrawal in the audit trail that never happened. It is the *bank* retiring a
     * row the customer replaced.
     */
    fun supersede(now: OffsetDateTime): Consent = copy(
        status = ConsentStatus.SUPERSEDED,
        updatedAt = now,
    )

    /**
     * True when [other] grants the same grantee exactly the same scope set.
     *
     * Set EQUALITY, not overlap: a consent for {ACCOUNTS} and one for {ACCOUNTS, PAYMENTS} are
     * different grants, and superseding the narrower one would silently widen what the customer
     * agreed to. Overlap is a different question that needs a customer-facing decision, not a
     * comparison operator.
     */
    fun supersedes(other: Consent): Boolean = other.id != id && other.granteeId == granteeId && other.scopes == scopes

    /**
     * PSD2 RTS Art. 10(2)(b) access-frequency cap for this consent's scopes: an AISP may read the
     * account data at most [AISP_MAX_ACCESSES_PER_DAY] times per day without fresh SCA. Returned by
     * `/validate` so a resource server can cache within that window. null when no AISP scope applies
     * (PISP/CBPII/agent/telemetry consents carry no per-day read cap).
     */
    fun frequencyPerDay(): Int? = if (scopes.any { it in AISP_SCOPES }) AISP_MAX_ACCESSES_PER_DAY else null

    companion object {
        /** PSD2 RTS Art. 10 AISP scopes: SCA-gated, 90-day validity cap, ≤4 reads/day without SCA. */
        val AISP_SCOPES = setOf(
            ConsentScope.ACCOUNTS_READ,
            ConsentScope.BALANCES_READ,
            ConsentScope.TRANSACTIONS_READ,
            ConsentScope.STATEMENTS_READ,
        )

        /**
         * GDPR Art. 7 data-processing scopes with no PSD2 account-access dimension (ADR-0205 D1).
         * A consent request made ENTIRELY of these scopes activates immediately — no SCA challenge —
         * because an SCA ceremony designed for payment authorization is a disproportionate burden on
         * a data-processing opt-in. Disjoint from [AISP_SCOPES] by construction: a scope must never
         * appear in both, or a request could either skip SCA it should require, or force SCA on a
         * request meant to be exempt. A request mixing a GDPR-only scope with any other scope is
         * rejected outright by [com.openbank.consent.application.usecase.ConsentService.createConsent]
         * rather than silently falling back to the SCA-gated path.
         */
        val GDPR_ONLY_SCOPES = setOf(
            ConsentScope.TELEMETRY_RUM,
            ConsentScope.MARKETING_COMMS_EMAIL,
            ConsentScope.MARKETING_COMMS_PUSH,
            ConsentScope.MARKETING_COMMS_INAPP,
            ConsentScope.CREDIT_OFFERS,
            ConsentScope.CREDIT_PROFILE_USE,
            ConsentScope.CREDIT_AI_AGENT,
        )

        /** PSD2 RTS Art. 10(2)(b): max AISP accesses per day without fresh SCA. */
        const val AISP_MAX_ACCESSES_PER_DAY = 4

        init {
            // ADR-0205's own Negative consequences: nothing but code review currently guards
            // AISP_SCOPES/GDPR_ONLY_SCOPES staying disjoint. Fail fast at class-load time instead
            // of trusting review — the two sets encode opposite SCA requirements, so a scope in
            // both would make createConsent's SCA-vs-exempt branch ambiguous by construction.
            check(AISP_SCOPES.intersect(GDPR_ONLY_SCOPES).isEmpty()) {
                "AISP_SCOPES and GDPR_ONLY_SCOPES must be disjoint: " +
                    "${AISP_SCOPES.intersect(GDPR_ONLY_SCOPES)} appear in both"
            }
        }
    }
}

// ─── Consent Validation Result ───────────────────────────────────────────────

sealed class ConsentValidationResult {
    data class Valid(val consent: Consent) : ConsentValidationResult()
    data class Invalid(val reason: String, val code: String) : ConsentValidationResult()
}
