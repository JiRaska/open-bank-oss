// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sca.infrastructure.persistence.entity

import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.SignatureAlgorithm
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "sca_enrolled_devices")
class EnrolledDeviceEntity : PanacheEntityBase() {

    @Id
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "credential_id", nullable = false, unique = true)
    lateinit var credentialId: String

    @Column(name = "public_key_spki", nullable = false, columnDefinition = "text")
    lateinit var publicKeySpki: String

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var algorithm: SignatureAlgorithm

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    fun toDomain(): EnrolledDevice = EnrolledDevice(
        id = id,
        partyId = partyId,
        credentialId = credentialId,
        publicKeySpkiB64 = publicKeySpki,
        algorithm = algorithm,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(d: EnrolledDevice): EnrolledDeviceEntity = EnrolledDeviceEntity().apply {
            id = d.id
            partyId = d.partyId
            credentialId = d.credentialId
            publicKeySpki = d.publicKeySpkiB64
            algorithm = d.algorithm
            createdAt = d.createdAt
        }
    }
}
