// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.persistence.entity

import com.openbank.swift.domain.institution.InstitutionTransferRail
import com.openbank.swift.domain.institution.InstitutionTransferStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `swift_institution_transfers` (V11). Every column named explicitly (`entity-column-names`). */
@Entity
@Table(name = "swift_institution_transfers")
class InstitutionTransferEntity {
    @Id
    @field:Column(name = "id")
    var id: UUID = UUID.randomUUID()

    @field:Column(name = "payment_id", unique = true)
    var paymentId: UUID = UUID.randomUUID()

    @field:Column(name = "idempotency_key", unique = true)
    var idempotencyKey: String = ""

    @Enumerated(EnumType.STRING)
    @field:Column(name = "rail")
    var rail: InstitutionTransferRail = InstitutionTransferRail.SWIFT

    @field:Column(name = "currency")
    var currency: String = ""

    @field:Column(name = "amount_minor_units")
    var amountMinorUnits: Long = 0

    @field:Column(name = "value_date")
    var valueDate: LocalDate = LocalDate.EPOCH

    @field:Column(name = "debtor_bic")
    var debtorBic: String = ""

    @field:Column(name = "debtor_account")
    var debtorAccount: String = ""

    @field:Column(name = "debtor_gl_accounting_class")
    var debtorGlAccountingClass: String = ""

    @field:Column(name = "creditor_bic")
    var creditorBic: String = ""

    @field:Column(name = "creditor_account")
    var creditorAccount: String = ""

    @field:Column(name = "creditor_name")
    var creditorName: String = ""

    @field:Column(name = "intermediary_bic")
    var intermediaryBic: String? = null

    @field:Column(name = "remittance_information")
    var remittanceInformation: String? = null

    @field:Column(name = "deal_ids", columnDefinition = "TEXT")
    var dealIds: String = ""

    @field:Column(name = "flow_ids", columnDefinition = "TEXT")
    var flowIds: String = ""

    @field:Column(name = "released_by")
    var releasedBy: String = ""

    @field:Column(name = "released_at")
    var releasedAt: Instant = Instant.EPOCH

    @field:Column(name = "synthetic")
    var synthetic: Boolean = false

    @Enumerated(EnumType.STRING)
    @field:Column(name = "status")
    var status: InstitutionTransferStatus = InstitutionTransferStatus.ACCEPTED

    @field:Column(name = "uetr")
    var uetr: String = ""

    @field:Column(name = "reason_code")
    var reasonCode: String? = null

    @field:Column(name = "reason")
    var reason: String? = null

    @field:Column(name = "raw_pacs009", columnDefinition = "TEXT")
    var rawPacs009: String? = null

    @field:Column(name = "raw_mt202", columnDefinition = "TEXT")
    var rawMt202: String? = null

    @field:Column(name = "created_at")
    var createdAt: Instant = Instant.EPOCH

    @field:Column(name = "updated_at")
    var updatedAt: Instant = Instant.EPOCH

    @field:Column(name = "ssi_version")
    var ssiVersion: Long? = null

    @field:Version
    @field:Column(name = "version")
    var version: Long = 0
}
