// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.persistence.entity

import com.openbank.vop.domain.model.VopNoDataReason
import com.openbank.vop.domain.model.VopOutcome
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Evidence that a VoP check ran (ADR-0171 §6).
 *
 * Note what is NOT here: the payee name and the IBAN in plaintext. Only their SHA-256 hashes are
 * stored. An evidence record needs to prove the control ran and what it decided — it does not need
 * to retain every name anyone ever typed into a payment form (GDPR Art. 5(1)(c)). The hashes still
 * support the one query that matters: "did we check this name against this IBAN, and what did we
 * say?", asked with the inputs in hand during a fraud claim.
 */
@Entity
@Table(name = "vop_verification")
class VopVerificationEntity : PanacheEntityBase {

    @Id
    @Column(columnDefinition = "uuid")
    lateinit var id: UUID

    @Column(name = "iban_hash", length = 64, nullable = false)
    lateinit var ibanHash: String

    @Column(name = "supplied_name_hash", length = 64, nullable = false)
    lateinit var suppliedNameHash: String

    @Column(name = "outcome", length = 16, nullable = false)
    @Enumerated(EnumType.STRING)
    lateinit var outcome: VopOutcome

    @Column(name = "no_data_reason", length = 32)
    @Enumerated(EnumType.STRING)
    var noDataReason: VopNoDataReason? = null

    @Column(name = "requested_by", length = 255, nullable = false)
    lateinit var requestedBy: String

    @Column(name = "verified_at", nullable = false)
    lateinit var verifiedAt: Instant
}
