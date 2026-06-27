// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Panache entity for `eudi_credential_offer` (V9) — an in-flight OpenID4VCI pre-authorized-code offer
 * (ADR-0094). [preAuthCode] is the natural primary key; the verified offered claims are stored as
 * JSON in [claimsJson]. Status is the enum name; the access token is indexed (unique) for the
 * credential-endpoint lookup.
 */
@Entity
@Table(name = "eudi_credential_offer")
class CredentialOfferEntity : PanacheEntityBase {
    @Id
    @Column(name = "pre_auth_code")
    lateinit var preAuthCode: String

    @Column(name = "access_token")
    var accessToken: String? = null

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "claims_json", nullable = false)
    lateinit var claimsJson: String

    @Column(name = "c_nonce")
    var cNonce: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant
}
