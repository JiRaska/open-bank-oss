// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The debtor SDD mandate row (ADR-0036 §A). Amendments are stored as a JSON array in [amendments]. */
@Entity
@Table(name = "sdd_mandate")
class SddMandateEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "debtor_iban", nullable = false, length = 34)
    lateinit var debtorIban: String

    @Column(name = "creditor_identifier", nullable = false, length = 35)
    lateinit var creditorIdentifier: String

    @Column(name = "umr", nullable = false, length = 35)
    lateinit var umr: String

    @Column(name = "scheme", nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    lateinit var scheme: SddScheme

    @Column(name = "sequence_type", nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    lateinit var sequenceType: SequenceType

    @Column(name = "creditor_name", nullable = false, length = 140)
    lateinit var creditorName: String

    @Column(name = "debtor_name", nullable = false, length = 140)
    lateinit var debtorName: String

    @Column(name = "signature_date", nullable = false)
    lateinit var signatureDate: LocalDate

    @Column(name = "status", nullable = false, length = 24)
    @Enumerated(EnumType.STRING)
    lateinit var status: MandateStatus

    @Column(name = "b2b_confirmed", nullable = false)
    var b2bConfirmed: Boolean = false

    @Column(name = "last_collection_date")
    var lastCollectionDate: LocalDate? = null

    @Column(name = "last_pre_notification_date")
    var lastPreNotificationDate: LocalDate? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "amendments", nullable = false, columnDefinition = "TEXT")
    var amendments: String = "[]"
}

/** Transactional outbox for `sdd.*` events — column definitions inherited from [PanacheOutboxEntity]. */
@Entity
@Table(name = "sdd_outbox")
class SddOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
