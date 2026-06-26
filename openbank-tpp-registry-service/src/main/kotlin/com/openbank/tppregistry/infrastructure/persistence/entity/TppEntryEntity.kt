// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.tppregistry.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "tpp_entries")
class TppEntryEntity : PanacheEntity() {
    @Column(name = "tpp_id", unique = true, nullable = false)
    lateinit var tppId: String

    @Column(nullable = false)
    lateinit var name: String

    @Column(name = "country_code", nullable = false, length = 2)
    lateinit var countryCode: String

    @Column(nullable = false, length = 20)
    lateinit var nca: String

    @Column(nullable = false)
    lateinit var roles: String

    @Column(nullable = false, length = 20)
    lateinit var status: String

    @Column(name = "qwac_subject_dn")
    var qwacSubjectDn: String? = null

    @Column(name = "qseal_subject_dn")
    var qsealSubjectDn: String? = null

    @Column(name = "qwac_expires_at")
    var qwacExpiresAt: LocalDate? = null

    @Column(name = "qseal_expires_at")
    var qsealExpiresAt: LocalDate? = null

    @Column(name = "registered_at", nullable = false)
    lateinit var registeredAt: OffsetDateTime

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: OffsetDateTime

    @Column(name = "blacklisted_at")
    var blacklistedAt: OffsetDateTime? = null

    @Column(name = "blacklist_reason")
    var blacklistReason: String? = null
}

@Entity
@Table(name = "eba_sync_state")
class EbaSyncStateEntity : PanacheEntity() {
    @Column(name = "last_sync_at")
    var lastSyncAt: OffsetDateTime? = null

    @Column(name = "last_success_at")
    var lastSuccessAt: OffsetDateTime? = null

    @Column(name = "total_entries")
    var totalEntries: Int = 0

    @Column(name = "error_message")
    var errorMessage: String? = null
}
