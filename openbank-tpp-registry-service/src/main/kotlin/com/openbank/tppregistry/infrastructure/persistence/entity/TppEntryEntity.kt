// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "tpp_entries")
class TppEntryEntity : PanacheEntity() {
    // The domain's TppEntry.id is a UUID; this table's real primary key (inherited `id: Long`
    // from PanacheEntity, BIGSERIAL) is internal only — never exposed via REST or referenced by
    // another service. entryUuid is what the domain id actually maps to; DB-generated default,
    // see V6__tpp_entries_entry_uuid.sql (issue #2340).
    @Column(name = "entry_uuid", nullable = false)
    lateinit var entryUuid: UUID

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
