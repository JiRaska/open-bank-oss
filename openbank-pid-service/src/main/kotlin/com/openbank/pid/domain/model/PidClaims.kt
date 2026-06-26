// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.domain.model

import java.time.LocalDate

/**
 * The verified Person Identification Data (PID) attributes from an EUDI wallet presentation
 * (eIDAS 2.0, ADR-0094). Produced ONLY after the issuer signature, issuer trust, temporal validity,
 * disclosure-hash binding and (when present) holder key-binding have all passed — an instance of this
 * type means "cryptographically verified", which is why tier-0 may treat [subjectId] as authoritative.
 *
 * Pure domain (no crypto/JOSE types). [subjectId] is the deterministic key (the PID `sub` or
 * `personal_administrative_number`, source-prefixed). [nationalIdentifier] (RČ), if selectively
 * disclosed, is reduced to a blind index by the resolver and never stored in plaintext (ADR-0072).
 */
data class PidClaims(
    val subjectId: String,
    val givenName: String,
    val familyName: String,
    val birthDate: LocalDate,
    val birthPlace: String? = null,
    val nationalities: List<String> = emptyList(),
    val issuingCountry: String,
    val nationalIdentifier: String? = null,
    val issuer: String,
    val levelOfAssurance: String = "HIGH",
    /** Token Status List reference from the credential's `status.status_list` claim, when present. */
    val statusListUri: String? = null,
    val statusListIndex: Long? = null,
)
