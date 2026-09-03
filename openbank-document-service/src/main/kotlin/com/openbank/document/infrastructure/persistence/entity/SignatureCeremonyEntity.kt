// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.entity

import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.SignatureLevel
import com.openbank.libs.domain.identifiers.Ids
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "signature_ceremonies")
class SignatureCeremonyEntity {
    @Id
    var id: UUID = Ids.newId()

    // Optimistic locking: recordDecision does a read-then-write across two separate
    // transactions (order-enforcement is checked in application memory in between), so without
    // a version check two concurrent decisions would silently lost-update each other.
    @Version
    var version: Int = 0

    @field:Column(name = "document_id")
    var documentId: UUID = Ids.newId()

    @field:Column(name = "signers_json", columnDefinition = "TEXT")
    var signersJson: String = "[]"

    @Enumerated(EnumType.STRING)
    var status: CeremonyStatus = CeremonyStatus.DRAFT

    @Enumerated(EnumType.STRING)
    @field:Column(name = "signature_level")
    var signatureLevel: SignatureLevel = SignatureLevel.ADVANCED

    @field:Column(name = "created_at")
    var createdAt: Instant = Instant.now()
}
