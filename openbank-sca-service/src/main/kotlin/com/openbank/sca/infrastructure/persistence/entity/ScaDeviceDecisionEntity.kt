// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.sca.infrastructure.persistence.entity

import com.openbank.sca.domain.model.DeviceApprovalDecision
import com.openbank.sca.domain.model.DeviceDecisionType
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "sca_device_decisions")
class ScaDeviceDecisionEntity : PanacheEntityBase() {
    @Id
    @Column(name = "challenge_id", nullable = false)
    lateinit var challengeId: UUID

    @Column(name = "credential_id", nullable = false)
    lateinit var credentialId: String

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var decision: DeviceDecisionType

    @Column(name = "signature_b64", nullable = false)
    lateinit var signatureB64: String

    @Column(name = "signed_payload_b64", nullable = false)
    lateinit var signedPayloadB64: String

    @Column(name = "decided_at", nullable = false)
    lateinit var decidedAt: OffsetDateTime

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: OffsetDateTime

    @Column(name = "challenge_version", nullable = false)
    var challengeVersion: Int = 0

    @Column(name = "deciding_party_id", nullable = false)
    lateinit var decidingPartyId: UUID

    fun toDomain() = DeviceApprovalDecision(
        challengeId = challengeId,
        credentialId = credentialId,
        decision = decision,
        signatureB64 = signatureB64,
        decidedAt = decidedAt,
        challengeVersion = challengeVersion,
        decidingPartyId = decidingPartyId,
    )
}
