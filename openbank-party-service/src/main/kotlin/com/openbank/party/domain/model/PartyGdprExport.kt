// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import java.time.Instant

/**
 * GDPR Art. 15 (Right of Access) export of all PII held for a data subject (ADR-0118 §6).
 *
 * Aggregates party-service PII (party record + documents), KYC PII (kyc-service),
 * and card PII (card-issuance-service). KYC and card data are fetched best-effort:
 * null/empty means the downstream was unavailable, not that no data exists.
 */
data class PartyGdprExport(
    val party: Party,
    val documents: List<PartyDocument>,
    val exportedAt: Instant,
    val kycData: Map<String, Any?>? = null,
    val cardData: List<Map<String, Any?>> = emptyList(),
)
