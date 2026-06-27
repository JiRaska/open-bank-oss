// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.persistence.entity

import com.openbank.consent.domain.model.*
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "consents")
class ConsentEntity : PanacheEntityBase() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "grantee_id", nullable = false)
    lateinit var granteeId: String

    @Enumerated(EnumType.STRING)
    @Column(name = "grantee_type", nullable = false)
    lateinit var granteeType: GranteeType

    @Column(name = "grantee_name", nullable = false)
    lateinit var granteeName: String

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "consent_scopes", joinColumns = [JoinColumn(name = "consent_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "scope")
    var scopes: MutableSet<ConsentScope> = mutableSetOf()

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "consent_accounts", joinColumns = [JoinColumn(name = "consent_id")])
    @Column(name = "iban")
    var accountIbans: MutableList<String>? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    lateinit var status: ConsentStatus

    @Column(name = "valid_from", nullable = false)
    lateinit var validFrom: OffsetDateTime

    @Column(name = "valid_to", nullable = false)
    lateinit var validTo: OffsetDateTime

    @Column(name = "sca_session_id")
    var scaSessionId: UUID? = null

    @Column(name = "redirect_uri")
    var redirectUri: String? = null

    @Column(name = "tpp_transaction_id")
    var tppTransactionId: String? = null

    @Column(name = "ip_address")
    var ipAddress: String? = null

    @Column(name = "user_agent")
    var userAgent: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: OffsetDateTime

    @Column(name = "revoked_at")
    var revokedAt: OffsetDateTime? = null

    @Column(name = "revoked_reason")
    var revokedReason: String? = null

    fun toDomain(): Consent = Consent(
        id = id,
        partyId = partyId,
        granteeId = granteeId,
        granteeType = granteeType,
        granteeName = granteeName,
        scopes = scopes.toSet(),
        accountIbans = accountIbans?.toList(),
        status = status,
        validFrom = validFrom,
        validTo = validTo,
        scaSessionId = scaSessionId,
        redirectUri = redirectUri,
        tppTransactionId = tppTransactionId,
        ipAddress = ipAddress,
        userAgent = userAgent,
        createdAt = createdAt,
        updatedAt = updatedAt,
        revokedAt = revokedAt,
        revokedReason = revokedReason
    )

    companion object {
        fun fromDomain(c: Consent): ConsentEntity = ConsentEntity().apply {
            id = c.id
            partyId = c.partyId
            granteeId = c.granteeId
            granteeType = c.granteeType
            granteeName = c.granteeName
            scopes = c.scopes.toMutableSet()
            accountIbans = c.accountIbans?.toMutableList()
            status = c.status
            validFrom = c.validFrom
            validTo = c.validTo
            scaSessionId = c.scaSessionId
            redirectUri = c.redirectUri
            tppTransactionId = c.tppTransactionId
            ipAddress = c.ipAddress
            userAgent = c.userAgent
            createdAt = c.createdAt
            updatedAt = c.updatedAt
            revokedAt = c.revokedAt
            revokedReason = c.revokedReason
        }
    }
}
