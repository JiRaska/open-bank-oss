// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import java.time.Instant

/**
 * GDPR Art. 15 (Right of Access) export of all PII party-service holds for a data subject:
 * the party record itself plus the metadata of their identity documents (ADR-0118 §6).
 *
 * Scope is party-service-direct PII only. KYC PII (kyc-service) and card PII
 * (card-issuance-service) are held by those services; aggregating them into a single
 * subject-access response is a tracked follow-up — see ADR-0118 §6.
 */
data class PartyGdprExport(val party: Party, val documents: List<PartyDocument>, val exportedAt: Instant)
