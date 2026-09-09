// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.Disclosure
import com.openbank.delegation.domain.model.DisclosureStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "delegation_disclosures")
class DisclosureEntity {
    @Id lateinit var id: UUID

    @Column(name = "request_id")
    lateinit var requestId: UUID

    @Column(name = "delegation_id")
    lateinit var delegationId: UUID

    @Column(name = "grantor_party_id")
    lateinit var grantorPartyId: UUID

    @Column(name = "source_document_id")
    lateinit var sourceDocumentId: UUID

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "snapshot_id")
    var snapshotId: UUID? = null

    @Column(name = "source_sha256")
    var sourceSha256: String? = null

    @Column(name = "snapshot_sha256")
    var snapshotSha256: String? = null

    @Column(name = "size_bytes")
    var sizeBytes: Long? = null

    @Column(name = "rejection_reason")
    var rejectionReason: String? = null

    @Column(name = "created_at")
    lateinit var createdAt: Instant

    @Column(name = "updated_at")
    lateinit var updatedAt: Instant

    fun toDomain() = Disclosure(
        id,
        requestId,
        delegationId,
        grantorPartyId,
        sourceDocumentId,
        DisclosureStatus.valueOf(status),
        snapshotId,
        sourceSha256,
        snapshotSha256,
        sizeBytes,
        rejectionReason,
        createdAt,
        updatedAt,
    )
}
